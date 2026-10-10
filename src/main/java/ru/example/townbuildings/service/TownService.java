package ru.example.townbuildings.service;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.townbuildings.cache.TownCache;
import ru.example.townbuildings.database.dao.BuildingDao;
import ru.example.townbuildings.database.dao.TownDao;
import ru.example.townbuildings.database.model.BuildingData;
import ru.example.townbuildings.database.model.TownData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Фасад над TownDao, BuildingDao и TownCache.
 */
public final class TownService {

    private final JavaPlugin plugin;
    private final TownDao townDao;
    private final BuildingDao buildingDao;
    private final TownCache cache;

    public TownService(@NotNull JavaPlugin plugin,
                       @NotNull TownDao townDao,
                       @NotNull BuildingDao buildingDao,
                       @NotNull TownCache cache) {
        this.plugin = plugin;
        this.townDao = townDao;
        this.buildingDao = buildingDao;
        this.cache = cache;
    }

    // ---------- синхронные ----------

    public @Nullable TownData getCachedTown(@NotNull UUID townUuid) {
        return cache.getTown(townUuid);
    }

    public void cacheTown(@NotNull TownData data) {
        cache.putTown(data);
    }

    // ---------- асинхронные ----------

    public @NotNull CompletableFuture<TownData> getOrCreate(@NotNull UUID townUuid,
                                                            @NotNull String townName) {
        TownData cached = cache.getTown(townUuid);
        if (cached != null) return CompletableFuture.completedFuture(cached);

        return townDao.findById(townUuid).thenCompose(opt -> {
            if (opt.isPresent()) {
                TownData data = opt.get();
                cache.putTown(data);
                return CompletableFuture.completedFuture(data);
            }
            return townDao.upsert(townUuid, townName)
                    .thenCompose(v -> townDao.findById(townUuid))
                    .thenApply(o -> {
                        TownData data = o.orElseThrow(() ->
                                new IllegalStateException("Город " + townUuid + " не создан"));
                        cache.putTown(data);
                        return data;
                    });
        });
    }

    public @NotNull CompletableFuture<Optional<TownData>> findByName(@NotNull String name) {
        return townDao.findByName(name);
    }

    public @NotNull CompletableFuture<Void> rename(@NotNull UUID townUuid, @NotNull String newName) {
        return townDao.rename(townUuid, newName).thenRun(() -> {
            TownData cached = cache.getTown(townUuid);
            if (cached != null) {
                cache.putTown(new TownData(cached.townUuid(), newName, cached.cityOnlineSeconds()));
            }
        });
    }

    public @NotNull CompletableFuture<Void> delete(@NotNull UUID townUuid) {
        return townDao.delete(townUuid).thenRun(() -> cache.invalidate(townUuid));
    }

    public @NotNull CompletableFuture<Long> addOnlineSeconds(@NotNull UUID townUuid, long seconds) {
        return townDao.addOnlineSeconds(townUuid, seconds).thenApply(newVal -> {
            TownData cached = cache.getTown(townUuid);
            if (cached != null) {
                cache.putTown(new TownData(cached.townUuid(), cached.townName(), newVal));
            }
            return newVal;
        });
    }

    public @NotNull CompletableFuture<Void> setOnlineSeconds(@NotNull UUID townUuid, long seconds) {
        return townDao.setOnlineSeconds(townUuid, seconds).thenRun(() -> {
            TownData cached = cache.getTown(townUuid);
            if (cached != null) {
                cache.putTown(new TownData(cached.townUuid(), cached.townName(), seconds));
            }
        });
    }

    /**
     * 5.3 — атомарно списать до maxHours часов городского онлайна.
     * После успешного списания инвалидирует кэш города.
     */
    public @NotNull CompletableFuture<Integer> consumeCityHours(@NotNull UUID townUuid, int maxHours) {
        return townDao.consumeCityHours(townUuid, maxHours).thenApply(hours -> {
            if (hours > 0) cache.invalidate(townUuid);
            return hours;
        });
    }

    // ---------- 6.3 — mayor_uuid ----------

    public @NotNull CompletableFuture<Optional<UUID>> findMayorUuid(@NotNull UUID townUuid) {
        return townDao.findMayorUuid(townUuid);
    }

    public @NotNull CompletableFuture<Void> setMayor(@NotNull UUID townUuid,
                                                     @Nullable UUID mayorUuid) {
        return townDao.setMayor(townUuid, mayorUuid);
    }

    // ---------- здания ----------

    public @NotNull CompletableFuture<List<BuildingData>> getBuildings(@NotNull UUID townUuid) {
        List<BuildingData> cached = cache.getBuildings(townUuid);
        if (cached != null) return CompletableFuture.completedFuture(cached);

        return buildingDao.findAllByTown(townUuid).thenApply(list -> {
            cache.putBuildings(townUuid, list);
            return list;
        });
    }

    public @NotNull CompletableFuture<Void> setBuildingLevel(@NotNull UUID townUuid,
                                                             @NotNull String buildingId,
                                                             int level) {
        CompletableFuture<Void> future = (level <= 0)
                ? buildingDao.remove(townUuid, buildingId)
                : buildingDao.setLevel(townUuid, buildingId, level);

        return future.whenComplete((v, ex) -> {
            if (ex != null) {
                // БД упала — инвалидируем кэш, чтобы не осталось "фантома".
                cache.invalidateBuildings(townUuid);
                plugin.getLogger().warning("[Town] Не удалось сохранить уровень здания "
                        + buildingId + " для города " + townUuid + ": " + ex.getMessage());
            } else {
                // ★ Успех: только теперь публикуем новый уровень в кэш.
                applyCacheLevel(townUuid, buildingId, level);
            }
        });
    }

    private void applyCacheLevel(@NotNull UUID townUuid, @NotNull String buildingId, int level) {
        List<BuildingData> cached = cache.getBuildings(townUuid);
        if (cached == null) return;

        List<BuildingData> updated = new ArrayList<>(cached.size() + 1);
        boolean found = false;
        for (BuildingData b : cached) {
            if (b.buildingId().equals(buildingId)) {
                found = true;
                if (level > 0) {
                    updated.add(new BuildingData(townUuid, buildingId, level));
                }
                // level <= 0 — просто не добавляем (удаляем)
            } else {
                updated.add(b);
            }
        }
        if (!found && level > 0) {
            updated.add(new BuildingData(townUuid, buildingId, level));
        }
        cache.putBuildings(townUuid, List.copyOf(updated));
    }

    // ---------- синхронизация имён ----------

    /**
     * Сверка имён городов с БД. Вызывается периодически (TownSyncTask)
     * как замена TownRenameEvent.
     *
     * 4.3 — собираем только изменившиеся, потом один batch UPDATE
     *       вместо N отдельных townDao.rename(...).
     */
    public @NotNull CompletableFuture<Void> syncNames(@NotNull List<TownData> fresh) {
        List<TownData> changed = new ArrayList<>();
        for (TownData t : fresh) {
            TownData cached = cache.getTown(t.townUuid());
            if (cached == null || !cached.townName().equals(t.townName())) {
                cache.putTown(t);
                changed.add(t);
            }
        }
        if (changed.isEmpty()) return CompletableFuture.completedFuture(null);
        return townDao.batchRename(changed);
    }

    public @NotNull TownCache cache() {
        return cache;
    }
}