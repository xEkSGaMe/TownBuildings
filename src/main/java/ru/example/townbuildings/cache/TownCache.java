package ru.example.townbuildings.cache;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.townbuildings.database.model.BuildingData;
import ru.example.townbuildings.database.model.TownData;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * Кэш данных городов и зданий.
 *
 * 3.3 — buildings со своим TTL, продлеваемым при чтении.
 * P8  — мутабельный Entry (AtomicLong lastAccess), без аллокаций на hot-path.
 * P29 — updateBuildings: атомарный get-modify-put под computeIfPresent.
 * C-3 — per-town generation для buildings: async-фетч, начавшийся до
 *       инвалидации/апгрейда, не публикует свой (устаревший) снапшот.
 *       Иначе после CAS-апгрейда с «холодным» кэшем in-flight SELECT мог
 *       затереть актуальные данные результатом, снятым ДО апгрейда.
 */
public final class TownCache {

    private final long townTtlMs;
    private final long buildingsTtlMs;

    private final Map<UUID, Entry<TownData>> towns = new ConcurrentHashMap<>();
    private final Map<UUID, Entry<List<BuildingData>>> buildings = new ConcurrentHashMap<>();

    /**
     * C-3 — счётчик «поколений» buildings per-town. Инкрементируется при
     * любой инвалидации / точечном обновлении (applyCacheLevel). Async-фетч
     * захватывает значение ДО запроса и публикует результат только если
     * поколение не изменилось.
     *
     * Bump и putIfFresh сериализуются на самом AtomicLong (synchronized):
     * без этого был бы race между «прочитал gen == 5» и «записал list» —
     * bump(→6) мог пройти ровно посередине, и устаревший list всё равно бы записался.
     */
    private final Map<UUID, AtomicLong> buildingGenerations = new ConcurrentHashMap<>();

    public TownCache(long townTtlSeconds) {
        this.townTtlMs = townTtlSeconds <= 0 ? 0 : townTtlSeconds * 1000L;
        // 3.3 — buildings живут дольше towns (3×).
        this.buildingsTtlMs = townTtlMs <= 0 ? 0 : townTtlMs * 3;
    }

    // -------- города --------

    public @Nullable TownData getTown(@NotNull UUID townUuid) {
        Entry<TownData> e = towns.get(townUuid);
        if (e == null) return null;
        long now = System.currentTimeMillis();
        if (townTtlMs > 0 && now - e.lastAccess.get() > townTtlMs) {
            towns.remove(townUuid, e);
            return null;
        }
        e.lastAccess.set(now);
        return e.value;
    }

    public void putTown(@NotNull TownData data) {
        towns.put(data.townUuid(), new Entry<>(data));
    }

    public void invalidate(@NotNull UUID townUuid) {
        towns.remove(townUuid);
        // C-3 — bump ДО удаления: любой in-flight фетч buildings после
        // этого не сможет опубликовать устаревший результат.
        bumpBuildingGeneration(townUuid);
        buildings.remove(townUuid);
    }

    // -------- здания --------

    public @Nullable List<BuildingData> getBuildings(@NotNull UUID townUuid) {
        Entry<List<BuildingData>> e = buildings.get(townUuid);
        if (e == null) return null;
        long now = System.currentTimeMillis();
        if (buildingsTtlMs > 0 && now - e.lastAccess.get() > buildingsTtlMs) {
            buildings.remove(townUuid, e);
            return null;
        }
        e.lastAccess.set(now);
        return e.value;
    }

    /**
     * C-3 — текущее «поколение» buildings для города. Захватывается перед
     * async-фетчем; сверяется в {@link #putBuildingsIfFresh}.
     */
    public long buildingGeneration(@NotNull UUID townUuid) {
        return buildingGenerations.computeIfAbsent(townUuid, k -> new AtomicLong(0)).get();
    }

    /**
     * C-3 — bump поколения. Вызывается при любой инвалидации / точечном
     * обновлении: все in-flight фетчи, начавшиеся до bump, будут отклонены
     * при попытке записи.
     */
    public void bumpBuildingGeneration(@NotNull UUID townUuid) {
        AtomicLong gen = buildingGenerations.computeIfAbsent(townUuid, k -> new AtomicLong(0));
        synchronized (gen) {
            gen.incrementAndGet();
        }
    }

    /**
     * C-3 — публикация только если поколение не изменилось с момента старта
     * фетча. Возвращает true, если значение записано.
     *
     * Если вернулось false — данные всё равно возвращаются вызывающему:
     * он получил их из БД (свежие), просто кэш не обновлён. Следующий
     * getBuildings сделает новый SELECT.
     */
    public boolean putBuildingsIfFresh(@NotNull UUID townUuid,
                                       @NotNull List<BuildingData> list,
                                       long expectedGeneration) {
        AtomicLong gen = buildingGenerations.computeIfAbsent(townUuid, k -> new AtomicLong(0));
        synchronized (gen) {
            if (gen.get() != expectedGeneration) return false;
            buildings.put(townUuid, new Entry<>(List.copyOf(list)));
            return true;
        }
    }

    /**
     * P29 — Атомарно обновляет список зданий города, если он есть в кэше.
     * @param fn преобразует текущий список; вернуть null — оставить как есть.
     */
    public void updateBuildings(
            @NotNull UUID townUuid,
            @NotNull UnaryOperator<List<BuildingData>> fn) {
        buildings.computeIfPresent(townUuid, (uuid, entry) -> {
            List<BuildingData> updated = fn.apply(entry.value);
            if (updated == null) return entry;
            return new Entry<>(List.copyOf(updated));
        });
    }

    public void invalidateBuildings(@NotNull UUID townUuid) {
        // C-3 — bump до удаления, см. invalidate(...).
        bumpBuildingGeneration(townUuid);
        buildings.remove(townUuid);
    }

    // -------- служебное --------

    public int size() {
        return towns.size();
    }

    public void invalidateAll() {
        towns.clear();
        buildings.clear();
        buildingGenerations.clear();
    }

    public void evictExpired() {
        if (townTtlMs <= 0) return;
        long now = System.currentTimeMillis();

        long townThreshold = townTtlMs * 3;
        towns.entrySet().removeIf(e -> now - e.getValue().lastAccess.get() > townThreshold);

        if (buildingsTtlMs > 0) {
            buildings.entrySet().removeIf(e -> now - e.getValue().lastAccess.get() > buildingsTtlMs);
        }
    }

    /**
     * P8 — мутабельный Entry: lastAccess обновляется на каждом чтении
     * без аллокации.
     */
    private static final class Entry<T> {
        final T value;
        final AtomicLong lastAccess;

        Entry(@NotNull T value) {
            this.value = value;
            this.lastAccess = new AtomicLong(System.currentTimeMillis());
        }
    }
}