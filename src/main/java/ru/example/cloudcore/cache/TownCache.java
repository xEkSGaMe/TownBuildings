package ru.example.cloudcore.cache;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.database.model.BuildingData;
import ru.example.cloudcore.database.model.TownData;

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
 * C-3 — per-town generation для buildings.
 * H6  — evictExpired работает и при TTL=0: используем SAFETY_EVICT_TTL_MS
 *       (24 ч), чтобы «бесконечный» кэш не рос безгранично.
 */
public final class TownCache {

    /** ★ H6 — eviction-порог при TTL=0 («бесконечный» кэш всё равно чистим). */
    private static final long SAFETY_EVICT_TTL_MS = 24L * 60L * 60L * 1000L;

    private final long townTtlMs;
    private final long buildingsTtlMs;

    private final Map<UUID, Entry<TownData>> towns = new ConcurrentHashMap<>();
    private final Map<UUID, Entry<List<BuildingData>>> buildings = new ConcurrentHashMap<>();

    private final Map<UUID, AtomicLong> buildingGenerations = new ConcurrentHashMap<>();

    public TownCache(long townTtlSeconds) {
        this.townTtlMs = townTtlSeconds <= 0 ? 0 : townTtlSeconds * 1000L;
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

    public long buildingGeneration(@NotNull UUID townUuid) {
        return buildingGenerations.computeIfAbsent(townUuid, k -> new AtomicLong(0)).get();
    }

    public void bumpBuildingGeneration(@NotNull UUID townUuid) {
        AtomicLong gen = buildingGenerations.computeIfAbsent(townUuid, k -> new AtomicLong(0));
        synchronized (gen) {
            gen.incrementAndGet();
        }
    }

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

    /**
     * ★ H6 — eviction при TTL=0 всё равно срабатывает: берём SAFETY_EVICT_TTL_MS
     * как «псевдо-TTL» для записей, к которым давно не обращались.
     */
    public void evictExpired() {
        long now = System.currentTimeMillis();

        long townEvictTtl = townTtlMs > 0 ? townTtlMs : SAFETY_EVICT_TTL_MS;
        long townThreshold = townEvictTtl * 3;
        towns.entrySet().removeIf(e ->
                now - e.getValue().lastAccess.get() > townThreshold);

        long bEvictTtl = buildingsTtlMs > 0 ? buildingsTtlMs : SAFETY_EVICT_TTL_MS;
        buildings.entrySet().removeIf(e ->
                now - e.getValue().lastAccess.get() > bEvictTtl);
    }

    private static final class Entry<T> {
        final T value;
        final AtomicLong lastAccess;

        Entry(@NotNull T value) {
            this.value = value;
            this.lastAccess = new AtomicLong(System.currentTimeMillis());
        }
    }
}