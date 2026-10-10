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

/**
 * Кэш данных городов и зданий.
 *
 * 3.3 — buildings теперь со своим TTL, продлеваемым при чтении.
 *       Не привязан к towns: getBuildings может быть вызван раньше,
 *       чем getOrCreate положит town в кэш — старый evictExpired
 *       удалял «сиротские» buildings и терял живые данные.
 * P8  — Entry стал мутабельным (AtomicLong lastAccess). getBuildings
 *       продлевает TTL через set (volatile store), без put в CHM и
 *       без аллокации нового Entry. Раньше на КАЖДОМ чтении в
 *       hot-path (BonusService/EffectService/LibraryListener) делался
 *       put + new Entry — тысячи аллокаций в секунду.
 */
public final class TownCache {

    private final long townTtlMs;
    private final long buildingsTtlMs;

    private final Map<UUID, Entry<TownData>> towns = new ConcurrentHashMap<>();
    private final Map<UUID, Entry<List<BuildingData>>> buildings = new ConcurrentHashMap<>();

    public TownCache(long townTtlSeconds) {
        this.townTtlMs = townTtlSeconds <= 0 ? 0 : townTtlSeconds * 1000L;
        // 3.3 — buildings живут дольше towns (3×), чтобы редкие города
        //       не теряли здания между апгрейдами.
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
        // P8 — обновляем lastAccess без аллокации (было бы put+new Entry).
        e.lastAccess.set(now);
        return e.value;
    }

    public void putTown(@NotNull TownData data) {
        towns.put(data.townUuid(), new Entry<>(data));
    }

    public void invalidate(@NotNull UUID townUuid) {
        towns.remove(townUuid);
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
        // P8 — раньше здесь был put(new Entry(...)) на КАЖДОМ чтении:
        // в hot-path (BonusService/EffectService/LibraryListener) это
        // давало тысячи аллокаций и записей в CHM в секунду. Теперь —
        // один AtomicLong.set (volatile store), без аллокации.
        e.lastAccess.set(now);
        return e.value;
    }

    public void putBuildings(@NotNull UUID townUuid, @NotNull List<BuildingData> list) {
        buildings.put(townUuid, new Entry<>(List.copyOf(list)));
    }

    public void invalidateBuildings(@NotNull UUID townUuid) {
        buildings.remove(townUuid);
    }

    // -------- служебное --------

    public int size() {
        return towns.size();
    }

    public void invalidateAll() {
        towns.clear();
        buildings.clear();
    }

    public void evictExpired() {
        if (townTtlMs <= 0) return;
        long now = System.currentTimeMillis();

        long townThreshold = townTtlMs * 3;
        towns.entrySet().removeIf(e -> now - e.getValue().lastAccess.get() > townThreshold);

        // 3.3 — buildings чистятся по своему TTL, не по «сиротству».
        // P8 — фильтр по lastAccess, а не по фиксированному expiresAt:
        // иначе горячие города вычищались бы на каждой итерации.
        if (buildingsTtlMs > 0) {
            buildings.entrySet().removeIf(e -> now - e.getValue().lastAccess.get() > buildingsTtlMs);
        }
    }

    /**
     * P8 — мутабельный Entry: lastAccess обновляется на каждом чтении
     * без аллокации. Раньше создавали новый Entry на каждый getBuildings.
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