package ru.example.townbuildings.cache;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.townbuildings.database.model.BuildingData;
import ru.example.townbuildings.database.model.TownData;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кэш данных городов и зданий.
 *
 * 3.3 — buildings теперь со своим TTL, продлеваемым при чтении.
 *       Не привязан к towns: getBuildings может быть вызван раньше,
 *       чем getOrCreate положит town в кэш — старый evictExpired
 *       удалял «сиротские» buildings и терял живые данные.
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
        if (townTtlMs > 0 && System.currentTimeMillis() > e.expiresAt()) {
            towns.remove(townUuid);
            return null;
        }
        return e.value();
    }

    public void putTown(@NotNull TownData data) {
        long expires = townTtlMs > 0 ? System.currentTimeMillis() + townTtlMs : Long.MAX_VALUE;
        towns.put(data.townUuid(), new Entry<>(data, expires));
    }

    public void invalidate(@NotNull UUID townUuid) {
        towns.remove(townUuid);
        buildings.remove(townUuid);
    }

    // -------- здания --------

    public @Nullable List<BuildingData> getBuildings(@NotNull UUID townUuid) {
        Entry<List<BuildingData>> e = buildings.get(townUuid);
        if (e == null) return null;
        if (buildingsTtlMs > 0 && System.currentTimeMillis() > e.expiresAt()) {
            buildings.remove(townUuid);
            return null;
        }
        // 3.3 — продлеваем timestamp при чтении.
        if (buildingsTtlMs > 0) {
            buildings.put(townUuid, new Entry<>(e.value(),
                    System.currentTimeMillis() + buildingsTtlMs));
        }
        return e.value();
    }

    public void putBuildings(@NotNull UUID townUuid, @NotNull List<BuildingData> list) {
        long expires = buildingsTtlMs > 0
                ? System.currentTimeMillis() + buildingsTtlMs
                : Long.MAX_VALUE;
        buildings.put(townUuid, new Entry<>(List.copyOf(list), expires));
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
        towns.entrySet().removeIf(e -> now - e.getValue().expiresAt() > townThreshold);

        // 3.3 — buildings чистятся по своему TTL, не по «сиротству».
        if (buildingsTtlMs > 0) {
            buildings.entrySet().removeIf(e -> now > e.getValue().expiresAt());
        }
    }

    private record Entry<T>(T value, long expiresAt) {}
}