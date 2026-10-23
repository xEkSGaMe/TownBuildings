package ru.example.cloudcore.service;

import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.config.BuildingRewards;
import ru.example.cloudcore.database.model.BuildingData;
import ru.example.cloudcore.CloudCorePlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import ru.example.cloudcore.CloudCorePlugin;
/**
 * Кэш бонусов города: buildings + wonders + institutes.
 *
 * ★ 5a: institutes-вклад в health-бонус теперь прибавляется здесь,
 *        через InstitutesService.contributeHealthBonus(). Остальные
 *        institutes-бонусы (экономика, эффекты, реактивка, chunk-bonus)
 *        остаются в своих сервисах — см. спеку 5a.
 */
public final class BonusService {

    private final CloudCorePlugin plugin;

    private final Map<UUID, BuildingRewards> cache = new ConcurrentHashMap<>();
    private final Map<UUID, BuildingRewards> staleCache = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<BuildingRewards>> loading = new ConcurrentHashMap<>();

    public BonusService(@NotNull CloudCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void invalidate(@NotNull UUID townUuid) {
        cache.remove(townUuid);
        loading.remove(townUuid);
    }

    public void invalidateCaches() {
        cache.clear();
        loading.clear();
    }

    public void invalidateAll() {
        cache.clear();
        loading.clear();
        staleCache.clear();
    }

    public @NotNull BuildingRewards getRewards(@NotNull UUID townUuid) {
        BuildingRewards cached = cache.get(townUuid);
        if (cached != null) return cached;

        List<BuildingData> buildings = plugin.townService().cache().getBuildings(townUuid);
        if (buildings != null) {
            BuildingRewards fresh = sum(buildings, townUuid);
            cache.put(townUuid, fresh);
            staleCache.put(townUuid, fresh);
            return fresh;
        }

        BuildingRewards stale = staleCache.get(townUuid);
        if (stale != null) {
            scheduleRefresh(townUuid);
            return stale;
        }

        scheduleRefresh(townUuid);
        return BuildingRewards.EMPTY;
    }

    private void scheduleRefresh(@NotNull UUID townUuid) {
        CompletableFuture<BuildingRewards> fresh = plugin.townService()
                .getBuildings(townUuid)
                .thenApply(list -> {
                    BuildingRewards r = sum(list, townUuid);
                    cache.put(townUuid, r);
                    staleCache.put(townUuid, r);
                    return r;
                });

        CompletableFuture<BuildingRewards> winner = loading.putIfAbsent(townUuid, fresh);
        if (winner == null) {
            fresh.whenComplete((r, ex) -> loading.remove(townUuid));
        }

        fresh.exceptionally(ex -> {
            plugin.getLogger().warning("[Bonus] refresh(" + townUuid
                    + ") упал: " + ex.getMessage());
            return null;
        });
    }

    private @NotNull BuildingRewards sum(@NotNull List<BuildingData> buildings,
                                         @NotNull UUID townUuid) {
        BuildingRewards base = BuildingRewardsMath.sum(plugin.buildingRegistry(), buildings);
        BuildingRewards wonders = plugin.wonderService() != null
                ? plugin.wonderService().getCachedRewards(townUuid)
                : BuildingRewards.EMPTY;
        BuildingRewards merged = BuildingRewardsMath.merge(base, wonders);

        // ★ 5a — institutes health-бонус: единая точка.
        // Читается из кэша InstitutesService (синхронно, без обращения к БД).
        double instHealth = 0.0;
        if (plugin.institutesService() != null) {
            instHealth = plugin.institutesService().contributeHealthBonus(townUuid);
        }
        if (instHealth != 0.0) {
            merged = merged.withHealthBonusHearts(merged.healthBonusHearts() + instHealth);
        }

        return merged;
    }
}