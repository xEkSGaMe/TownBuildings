package ru.example.townbuildings.database.dao;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.database.DatabaseManager;
import ru.example.townbuildings.service.WithdrawResult;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Доступ к tb_inventories.
 * Каждая строка — один НЕПУСТОЙ слот. Пустые слоты в БД не хранятся.
 *
 * P15 — withdrawIfAvailable возвращает takenFrom (page, slot) каждого
 *       источника; depositBack принимает preferred и сначала пытается
 *       вернуть предметы в исходные слоты.
 */
public final class InventoryDao {

    private final DatabaseManager db;

    public InventoryDao(@NotNull DatabaseManager db) {
        this.db = db;
    }

    /** Загрузить непустые слоты страницы. slot -> serialized bytes. */
    public @NotNull CompletableFuture<Map<Integer, byte[]>> loadPage(
            @NotNull UUID townUuid, int page) {
        return db.supplyAsync(conn -> {
            Map<Integer, byte[]> out = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT slot, item_data FROM tb_inventories WHERE town_uuid = ? AND page = ?"
            )) {
                ps.setString(1, townUuid.toString());
                ps.setInt(2, page);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byte[] data = rs.getBytes("item_data");
                        if (data != null && data.length > 0) {
                            out.put(rs.getInt("slot"), data);
                        }
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("loadPage: " + e.getMessage(), e);
            }
            return out;
        });
    }

    /**
     * Сохранить diff-страницы. Значение null в map → удалить слот.
     * Всё в одной транзакции; строки страницы блокируются FOR UPDATE.
     */
    public @NotNull CompletableFuture<Void> savePageDiff(
            @NotNull UUID townUuid, int page, @NotNull Map<Integer, byte[]> diff) {
        if (diff.isEmpty()) return CompletableFuture.completedFuture(null);
        return db.runAsync(conn -> {
            boolean prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement lock = conn.prepareStatement(
                        "SELECT slot FROM tb_inventories WHERE town_uuid = ? AND page = ? FOR UPDATE"
                )) {
                    lock.setString(1, townUuid.toString());
                    lock.setInt(2, page);
                    lock.executeQuery().close();
                }

                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM tb_inventories WHERE town_uuid = ? AND page = ? AND slot = ?"
                );
                     PreparedStatement up = conn.prepareStatement(
                             "INSERT INTO tb_inventories (town_uuid, page, slot, item_data) VALUES (?, ?, ?, ?) " +
                                     "ON DUPLICATE KEY UPDATE item_data = VALUES(item_data)"
                     )) {
                    for (Map.Entry<Integer, byte[]> e : diff.entrySet()) {
                        if (e.getValue() == null) {
                            del.setString(1, townUuid.toString());
                            del.setInt(2, page);
                            del.setInt(3, e.getKey());
                            del.addBatch();
                        } else {
                            up.setString(1, townUuid.toString());
                            up.setInt(2, page);
                            up.setInt(3, e.getKey());
                            up.setBytes(4, e.getValue());
                            up.addBatch();
                        }
                    }
                    del.executeBatch();
                    up.executeBatch();
                }
                conn.commit();
            } catch (SQLException ex) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw new RuntimeException("savePageDiff: " + ex.getMessage(), ex);
            } finally {
                try { conn.setAutoCommit(prevAuto); } catch (SQLException ignored) {}
            }
        });
    }

    /**
     * Атомарно списать ресурсы с /t inventory.
     *
     * Отличия от старого withdrawAll:
     *  - Возвращает не boolean, а WithdrawResult — можно узнать ЧЕГО не хватает.
     *  - Всё ещё одной транзакцией с FOR UPDATE.
     *  - При неудаче НИЧЕГО не меняется (rollback).
     *  - P15: фиксирует takenFrom (page, slot) каждого источника.
     */
    public @NotNull CompletableFuture<WithdrawResult> withdrawIfAvailable(
            @NotNull UUID townUuid, @NotNull Map<Material, Integer> need) {

        if (need.isEmpty()) {
            return CompletableFuture.completedFuture(WithdrawResult.success());
        }

        return db.supplyAsync(conn -> {
            boolean prevAuto = true;
            try {
                prevAuto = conn.getAutoCommit();
                conn.setAutoCommit(false);

                // 1. SELECT ... FOR UPDATE — блокируем строки города.
                Map<Integer, Map<Integer, ItemStack>> byPage = new HashMap<>();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT page, slot, item_data FROM tb_inventories " +
                                "WHERE town_uuid = ? FOR UPDATE")) {
                    ps.setString(1, townUuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            int pg = rs.getInt("page");
                            int sl = rs.getInt("slot");
                            byte[] data = rs.getBytes("item_data");
                            if (data == null || data.length == 0) continue;
                            ItemStack is = ItemStack.deserializeBytes(data);
                            if (is == null || is.getType().isAir()) continue;
                            byPage.computeIfAbsent(pg, k -> new HashMap<>()).put(sl, is);
                        }
                    }
                }

                // 2. Считаем available.
                Map<Material, Integer> available = new HashMap<>();
                for (Map<Integer, ItemStack> pageItems : byPage.values()) {
                    for (ItemStack is : pageItems.values()) {
                        available.merge(is.getType(), is.getAmount(), Integer::sum);
                    }
                }

                // 3. Проверяем — чего не хватает.
                Map<Material, Integer> missing = new HashMap<>();
                for (Map.Entry<Material, Integer> e : need.entrySet()) {
                    int have = available.getOrDefault(e.getKey(), 0);
                    if (have < e.getValue()) {
                        missing.put(e.getKey(), e.getValue() - have);
                    }
                }
                if (!missing.isEmpty()) {
                    conn.rollback();
                    return WithdrawResult.fail(missing);
                }

                // 4. Списываем — та же логика, что была в withdrawAll.
                Map<Integer, Map<Integer, byte[]>> toUpdate = new HashMap<>();
                // P15 — фиксируем, откуда взяли каждый материал. Понадобится
                // при rollback'е (depositBack попробует вернуть в те же слоты).
                Map<Material, List<WithdrawResult.SlotRef>> takenFrom = new HashMap<>();

                for (Map.Entry<Material, Integer> needEntry : need.entrySet()) {
                    int remaining = needEntry.getValue();
                    outer:
                    for (Map.Entry<Integer, Map<Integer, ItemStack>> pageEntry : byPage.entrySet()) {
                        for (Map.Entry<Integer, ItemStack> slotEntry : pageEntry.getValue().entrySet()) {
                            ItemStack is = slotEntry.getValue();
                            if (is == null || is.getType() != needEntry.getKey()) continue;
                            int take = Math.min(remaining, is.getAmount());
                            int left = is.getAmount() - take;
                            remaining -= take;

                            // P15 — запоминаем источник (page, slot).
                            takenFrom.computeIfAbsent(needEntry.getKey(), k -> new ArrayList<>())
                                    .add(new WithdrawResult.SlotRef(
                                            pageEntry.getKey(), slotEntry.getKey()));

                            Map<Integer, byte[]> pageUpdates =
                                    toUpdate.computeIfAbsent(pageEntry.getKey(), k -> new HashMap<>());
                            if (left <= 0) {
                                pageUpdates.put(slotEntry.getKey(), null);
                            } else {
                                ItemStack copy = is.clone();
                                copy.setAmount(left);
                                pageUpdates.put(slotEntry.getKey(), copy.serializeAsBytes());
                            }
                            if (remaining <= 0) break outer;
                        }
                    }
                }

                // 5. Батч: delete + upsert.
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM tb_inventories WHERE town_uuid = ? AND page = ? AND slot = ?");
                     PreparedStatement up = conn.prepareStatement(
                             "INSERT INTO tb_inventories (town_uuid, page, slot, item_data) " +
                                     "VALUES (?, ?, ?, ?) " +
                                     "ON DUPLICATE KEY UPDATE item_data = VALUES(item_data)")) {
                    for (Map.Entry<Integer, Map<Integer, byte[]>> pEntry : toUpdate.entrySet()) {
                        for (Map.Entry<Integer, byte[]> sEntry : pEntry.getValue().entrySet()) {
                            if (sEntry.getValue() == null) {
                                del.setString(1, townUuid.toString());
                                del.setInt(2, pEntry.getKey());
                                del.setInt(3, sEntry.getKey());
                                del.addBatch();
                            } else {
                                up.setString(1, townUuid.toString());
                                up.setInt(2, pEntry.getKey());
                                up.setInt(3, sEntry.getKey());
                                up.setBytes(4, sEntry.getValue());
                                up.addBatch();
                            }
                        }
                    }
                    del.executeBatch();
                    up.executeBatch();
                }

                conn.commit();
                // P15 — возвращаем takenFrom для rollback'а.
                return WithdrawResult.success(takenFrom);

            } catch (SQLException ex) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw new RuntimeException("withdrawIfAvailable: " + ex.getMessage(), ex);
            } finally {
                try { conn.setAutoCommit(prevAuto); } catch (SQLException ignored) {}
            }
        });
    }

    /**
     * @deprecated используйте {@link #withdrawIfAvailable(UUID, Map)}.
     *             Оставлено для обратной совместимости — делегирует.
     */
    @Deprecated
    public @NotNull CompletableFuture<Boolean> withdrawAll(
            @NotNull UUID townUuid, @NotNull Map<Material, Integer> need) {
        return withdrawIfAvailable(townUuid, need).thenApply(WithdrawResult::ok);
    }

    /**
     * Положить предметы обратно в /t inventory (rollback после неудачного
     * апгрейда).
     *
     * P15 — принимает карту «исходных мест» (page, slot), откуда материал
     * был списан при withdrawIfAvailable. Сначала пытаемся восстановить
     * точно туда; что не влезло — кладём в любые свободные слоты.
     *
     * @deprecated-совместимый overload — см. {@link #depositBack(UUID, int, Map, Map)}.
     */
    public @NotNull CompletableFuture<Boolean> depositBack(
            @NotNull UUID townUuid, int maxPages,
            @NotNull Map<Material, Integer> items) {
        return depositBack(townUuid, maxPages, items, Map.of());
    }

    /**
     * Полная версия с приоритетными слотами (P15).
     */
    public @NotNull CompletableFuture<Boolean> depositBack(
            @NotNull UUID townUuid, int maxPages,
            @NotNull Map<Material, Integer> items,
            @NotNull Map<Material, List<WithdrawResult.SlotRef>> preferred) {

        if (items.isEmpty()) return CompletableFuture.completedFuture(true);

        return db.supplyAsync(conn -> {
            boolean prevAuto = true;
            try {
                prevAuto = conn.getAutoCommit();
                conn.setAutoCommit(false);

                // 1. Загружаем текущее содержимое страниц (FOR UPDATE).
                Map<Integer, Map<Integer, ItemStack>> byPage = new HashMap<>();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT page, slot, item_data FROM tb_inventories " +
                                "WHERE town_uuid = ? FOR UPDATE")) {
                    ps.setString(1, townUuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            int pg = rs.getInt("page");
                            int sl = rs.getInt("slot");
                            byte[] data = rs.getBytes("item_data");
                            if (data == null || data.length == 0) continue;
                            ItemStack is = ItemStack.deserializeBytes(data);
                            if (is == null || is.getType().isAir()) continue;
                            byPage.computeIfAbsent(pg, k -> new HashMap<>()).put(sl, is);
                        }
                    }
                }

                Map<Integer, Map<Integer, byte[]>> updates = new HashMap<>();

                // Остатки к возврату по каждому материалу.
                Map<Material, Integer> remainingByMat = new HashMap<>(items);

                // P15 — фаза 0: возврат в исходные слоты (page, slot) из takenFrom.
                for (Map.Entry<Material, List<WithdrawResult.SlotRef>> pref : preferred.entrySet()) {
                    Material mat = pref.getKey();
                    Integer left = remainingByMat.get(mat);
                    if (left == null || left <= 0) continue;
                    int remaining = left;

                    for (WithdrawResult.SlotRef ref : pref.getValue()) {
                        if (remaining <= 0) break;
                        int page = ref.page();
                        int slot = ref.slot();
                        if (page < 1 || page > maxPages) continue;   // страницы могли исчезнуть

                        Map<Integer, byte[]> planned = updates.computeIfAbsent(page, k -> new HashMap<>());
                        ItemStack existing;
                        if (planned.containsKey(slot)) {
                            byte[] plannedBytes = planned.get(slot);
                            existing = (plannedBytes == null) ? null
                                    : ItemStack.deserializeBytes(plannedBytes);
                        } else {
                            Map<Integer, ItemStack> pageItems = byPage.get(page);
                            existing = (pageItems == null) ? null : pageItems.get(slot);
                        }

                        if (existing == null || existing.getType().isAir()) {
                            int max = new ItemStack(mat).getMaxStackSize();
                            int add = Math.min(max, remaining);
                            ItemStack chunk = new ItemStack(mat, add);
                            planned.put(slot, chunk.serializeAsBytes());
                            remaining -= add;
                        } else if (existing.getType() == mat) {
                            int free = existing.getMaxStackSize() - existing.getAmount();
                            if (free > 0) {
                                int add = Math.min(free, remaining);
                                ItemStack copy = existing.clone();
                                copy.setAmount(existing.getAmount() + add);
                                planned.put(slot, copy.serializeAsBytes());
                                remaining -= add;
                            }
                        }
                        // Слот занят другим материалом — не трогаем, уходим в fallback.
                    }
                    remainingByMat.put(mat, remaining);
                }

                // Фаза 1: остатки — доложить в существующие стеки.
                for (Map.Entry<Material, Integer> needEntry : remainingByMat.entrySet()) {
                    Material mat = needEntry.getKey();
                    int remaining = needEntry.getValue();
                    if (remaining <= 0) continue;

                    outer:
                    for (int page = 1; page <= maxPages; page++) {
                        Map<Integer, ItemStack> pageItems = byPage.get(page);
                        if (pageItems == null) continue;
                        Map<Integer, byte[]> plannedPage = updates.get(page);

                        for (Map.Entry<Integer, ItemStack> slotEntry : pageItems.entrySet()) {
                            ItemStack current = slotEntry.getValue();
                            // P15 — учитываем обновления из фазы 0.
                            if (plannedPage != null && plannedPage.containsKey(slotEntry.getKey())) {
                                byte[] b = plannedPage.get(slotEntry.getKey());
                                if (b == null) continue;
                                current = ItemStack.deserializeBytes(b);
                            }
                            if (current.getType() != mat) continue;
                            int free = current.getMaxStackSize() - current.getAmount();
                            if (free <= 0) continue;
                            int add = Math.min(free, remaining);
                            ItemStack copy = current.clone();
                            copy.setAmount(current.getAmount() + add);
                            updates.computeIfAbsent(page, k -> new HashMap<>())
                                    .put(slotEntry.getKey(), copy.serializeAsBytes());
                            remaining -= add;
                            if (remaining <= 0) break outer;
                        }
                    }

                    // Фаза 2: занять пустые слоты.
                    if (remaining > 0) {
                        outer2:
                        for (int page = 1; page <= maxPages; page++) {
                            Set<Integer> used = new HashSet<>();
                            Map<Integer, ItemStack> pageItems = byPage.get(page);
                            if (pageItems != null) used.addAll(pageItems.keySet());
                            Map<Integer, byte[]> planned = updates.get(page);
                            if (planned != null) used.addAll(planned.keySet());

                            for (int slot = 0; slot < 54; slot++) {
                                if (slot == 45 || slot == 53) continue;
                                if (used.contains(slot)) continue;
                                ItemStack probe = new ItemStack(mat);
                                int add = Math.min(probe.getMaxStackSize(), remaining);
                                ItemStack chunk = new ItemStack(mat, add);
                                updates.computeIfAbsent(page, k -> new HashMap<>())
                                        .put(slot, chunk.serializeAsBytes());
                                remaining -= add;
                                used.add(slot);
                                if (remaining <= 0) break outer2;
                            }
                        }
                    }

                    if (remaining > 0) {
                        Bukkit.getLogger().warning("[Inventory] depositBack: не хватило места для "
                                + remaining + " x " + mat + " (город " + townUuid + ")");
                    }
                }

                // Применяем одним batch.
                try (PreparedStatement up = conn.prepareStatement(
                        "INSERT INTO tb_inventories (town_uuid, page, slot, item_data) " +
                                "VALUES (?, ?, ?, ?) " +
                                "ON DUPLICATE KEY UPDATE item_data = VALUES(item_data)")) {
                    for (Map.Entry<Integer, Map<Integer, byte[]>> pEntry : updates.entrySet()) {
                        for (Map.Entry<Integer, byte[]> sEntry : pEntry.getValue().entrySet()) {
                            if (sEntry.getValue() == null) continue;
                            up.setString(1, townUuid.toString());
                            up.setInt(2, pEntry.getKey());
                            up.setInt(3, sEntry.getKey());
                            up.setBytes(4, sEntry.getValue());
                            up.addBatch();
                        }
                    }
                    up.executeBatch();
                }

                conn.commit();
                return true;

            } catch (SQLException ex) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw new RuntimeException("depositBack: " + ex.getMessage(), ex);
            } finally {
                try { conn.setAutoCommit(prevAuto); } catch (SQLException ignored) {}
            }
        });
    }

    /** Суммарные количества по Material — для проверки перед прокачкой. */
    public @NotNull CompletableFuture<Map<Material, Integer>> countAll(@NotNull UUID townUuid) {
        // P18 — вместо полной десериализации каждого BLOB (ItemStack.deserializeBytes
        // поднимает meta/NBT и аллокирует объект на каждый слот) читаем только
        // «сырые» байты Bukkit-сериализации: material id и количество лежат в
        // заголовке потока (varint-тип + float-количество), остальное не трогаем.
        // Результат идентичен: merge(type) += amount.
        return db.supplyAsync(conn -> {
            Map<Material, Integer> out = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT item_data FROM tb_inventories WHERE town_uuid = ?"
            )) {
                ps.setString(1, townUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byte[] data = rs.getBytes("item_data");
                        Material type = ItemHeader.peekType(data);
                        if (type == null || type.isAir()) continue;
                        out.merge(type, ItemHeader.peekAmount(data), Integer::sum);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("countAll: " + e.getMessage(), e);
            }
            return out;
        });
    }
}