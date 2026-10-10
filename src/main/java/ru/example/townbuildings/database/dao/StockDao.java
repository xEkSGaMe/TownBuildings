package ru.example.townbuildings.database.dao;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class StockDao {

    private final DatabaseManager db;

    public StockDao(@NotNull DatabaseManager db) {
        this.db = db;
    }

    // -------- load / save --------

    public @NotNull CompletableFuture<Map<Integer, byte[]>> loadPage(
            @NotNull UUID townUuid, int page) {
        return db.supplyAsync(conn -> {
            Map<Integer, byte[]> out = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT slot, item_data FROM tb_stocks WHERE town_uuid = ? AND page = ?"
            )) {
                ps.setString(1, townUuid.toString());
                ps.setInt(2, page);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byte[] data = rs.getBytes("item_data");
                        if (data != null && data.length > 0) out.put(rs.getInt("slot"), data);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return out;
        });
    }

    public @NotNull CompletableFuture<Void> savePageDiff(
            @NotNull UUID townUuid, int page, @NotNull Map<Integer, byte[]> diff) {
        if (diff.isEmpty()) return CompletableFuture.completedFuture(null);
        return db.runAsync(conn -> {
            boolean prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement lock = conn.prepareStatement(
                        "SELECT slot FROM tb_stocks WHERE town_uuid = ? AND page = ? FOR UPDATE"
                )) {
                    lock.setString(1, townUuid.toString());
                    lock.setInt(2, page);
                    lock.executeQuery().close();
                }
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM tb_stocks WHERE town_uuid = ? AND page = ? AND slot = ?"
                );
                     PreparedStatement up = conn.prepareStatement(
                             "INSERT INTO tb_stocks (town_uuid, page, slot, item_data) VALUES (?, ?, ?, ?) " +
                                     "ON DUPLICATE KEY UPDATE item_data = VALUES(item_data)"
                     )) {
                    for (var e : diff.entrySet()) {
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
                throw new RuntimeException(ex);
            } finally {
                try { conn.setAutoCommit(prevAuto); } catch (SQLException ignored) {}
            }
        });
    }

    // -------- tryDeposit --------

    public @NotNull CompletableFuture<Boolean> tryDeposit(
            @NotNull UUID townUuid, int maxPages, @NotNull ItemStack stack) {
        if (stack.getType().isAir() || stack.getAmount() <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        return db.supplyAsync(conn -> depositSync(conn, townUuid, maxPages, stack));
    }

    private boolean depositSync(@NotNull Connection conn, @NotNull UUID townUuid, int maxPages,
                                @NotNull ItemStack stack) throws RuntimeException {
        try {
            boolean prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                Map<Integer, Map<Integer, ItemStack>> byPage = new HashMap<>();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT page, slot, item_data FROM tb_stocks WHERE town_uuid = ? FOR UPDATE"
                )) {
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
                int remaining = stack.getAmount();

                outer:
                for (int page = 1; page <= maxPages; page++) {
                    Map<Integer, ItemStack> pageItems = byPage.get(page);
                    if (pageItems == null) continue;
                    for (var e : pageItems.entrySet()) {
                        ItemStack is = e.getValue();
                        if (is.getType() != stack.getType() || !is.isSimilar(stack)) continue;
                        int free = is.getMaxStackSize() - is.getAmount();
                        if (free <= 0) continue;
                        int add = Math.min(free, remaining);
                        ItemStack copy = is.clone();
                        copy.setAmount(is.getAmount() + add);
                        updates.computeIfAbsent(page, k -> new HashMap<>())
                                .put(e.getKey(), copy.serializeAsBytes());
                        remaining -= add;
                        if (remaining <= 0) break outer;
                    }
                }

                if (remaining > 0) {
                    outer2:
                    for (int page = 1; page <= maxPages; page++) {
                        Set<Integer> used = new HashSet<>();
                        Map<Integer, ItemStack> pageItems = byPage.get(page);
                        if (pageItems != null) used.addAll(pageItems.keySet());
                        Map<Integer, byte[]> planned = updates.get(page);
                        if (planned != null) used.addAll(planned.keySet());

                        for (int slot = 0; slot < 54; slot++) {
                            if (slot == 45 || slot == 53) continue;   // ★ 46 → 45
                            if (used.contains(slot)) continue;

                            int add = Math.min(stack.getMaxStackSize(), remaining);
                            ItemStack chunk = stack.clone();
                            chunk.setAmount(add);
                            updates.computeIfAbsent(page, k -> new HashMap<>())
                                    .put(slot, chunk.serializeAsBytes());
                            remaining -= add;
                            used.add(slot);
                            if (remaining <= 0) break outer2;
                        }
                    }
                }

                if (remaining > 0) {
                    conn.rollback();
                    return false;
                }

                try (PreparedStatement up = conn.prepareStatement(
                        "INSERT INTO tb_stocks (town_uuid, page, slot, item_data) VALUES (?, ?, ?, ?) " +
                                "ON DUPLICATE KEY UPDATE item_data = VALUES(item_data)"
                )) {
                    for (var pEntry : updates.entrySet()) {
                        for (var sEntry : pEntry.getValue().entrySet()) {
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
                throw new RuntimeException(ex);
            } finally {
                try { conn.setAutoCommit(prevAuto); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // -------- stats --------

    public @NotNull CompletableFuture<Map<Material, Integer>> countAll(@NotNull UUID townUuid) {
        return db.supplyAsync(conn -> {
            Map<Material, Integer> out = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT item_data FROM tb_stocks WHERE town_uuid = ?"
            )) {
                ps.setString(1, townUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byte[] data = rs.getBytes("item_data");
                        if (data == null || data.length == 0) continue;
                        ItemStack is = ItemStack.deserializeBytes(data);
                        if (is == null || is.getType().isAir()) continue;
                        out.merge(is.getType(), is.getAmount(), Integer::sum);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return out;
        });
    }

    // -------- 9.5.1 — batch deposit --------

    /**
     * 9.5 — залить в хранилище несколько разных материалов за одну транзакцию.
     * Стратегия на каждый материал:
     *   1) доложить в существующие стеки того же типа;
     *   2) занять пустые слоты.
     * Если хотя бы один материал не влез целиком — rollback, вернуть false.
     */
    public @NotNull CompletableFuture<Boolean> depositBatch(
            @NotNull UUID townUuid, int maxPages, @NotNull Map<Material, Integer> required) {

        if (required.isEmpty()) return CompletableFuture.completedFuture(true);

        return db.supplyAsync(conn -> {
            try {
                boolean prevAuto = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    // 1. Загружаем всё FOR UPDATE (один раз)
                    Map<Integer, Map<Integer, ItemStack>> byPage = new HashMap<>();
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT page, slot, item_data FROM tb_stocks " +
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
                    Map<Integer, Set<Integer>> usedSlots = new HashMap<>();

                    for (var needEntry : required.entrySet()) {
                        Material mat = needEntry.getKey();
                        int remaining = needEntry.getValue();
                        if (remaining <= 0) continue;

                        // 1a. Доложить в существующие стеки
                        outer:
                        for (int page = 1; page <= maxPages; page++) {
                            Map<Integer, ItemStack> pageItems = byPage.get(page);
                            if (pageItems == null) continue;
                            for (var slotEntry : pageItems.entrySet()) {
                                ItemStack is = slotEntry.getValue();
                                if (is.getType() != mat) continue;
                                int max = is.getMaxStackSize();

                                Map<Integer, byte[]> planned = updates.get(page);
                                if (planned != null && planned.containsKey(slotEntry.getKey())) {
                                    byte[] b = planned.get(slotEntry.getKey());
                                    if (b != null) {
                                        is = ItemStack.deserializeBytes(b);
                                        max = is.getMaxStackSize();
                                    }
                                }

                                int free = max - is.getAmount();
                                if (free <= 0) continue;
                                int add = Math.min(free, remaining);

                                ItemStack copy = is.clone();
                                copy.setAmount(is.getAmount() + add);
                                updates.computeIfAbsent(page, k -> new HashMap<>())
                                        .put(slotEntry.getKey(), copy.serializeAsBytes());
                                remaining -= add;
                                if (remaining <= 0) break outer;
                            }
                        }

                        // 1b. Занять пустые слоты
                        if (remaining > 0) {
                            outer2:
                            for (int page = 1; page <= maxPages; page++) {
                                Set<Integer> used = usedSlots.computeIfAbsent(page, k -> new HashSet<>());
                                Map<Integer, ItemStack> pageItems = byPage.get(page);
                                if (pageItems != null) used.addAll(pageItems.keySet());
                                Map<Integer, byte[]> planned = updates.get(page);
                                if (planned != null) used.addAll(planned.keySet());

                                for (int slot = 0; slot < 54; slot++) {
                                    if (slot == 45 || slot == 53) continue;   // ★ 46 → 45
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
                            conn.rollback();
                            return false;
                        }
                    }

                    // 2. Применяем одним batch-ом
                    try (PreparedStatement up = conn.prepareStatement(
                            "INSERT INTO tb_stocks (town_uuid, page, slot, item_data) " +
                                    "VALUES (?, ?, ?, ?) " +
                                    "ON DUPLICATE KEY UPDATE item_data = VALUES(item_data)")) {
                        for (var pEntry : updates.entrySet()) {
                            for (var sEntry : pEntry.getValue().entrySet()) {
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
                    throw new RuntimeException("depositBatch: " + ex.getMessage(), ex);
                } finally {
                    try { conn.setAutoCommit(prevAuto); } catch (SQLException ignored) {}
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }
}