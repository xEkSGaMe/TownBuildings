package ru.example.townbuildings.service;

import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.config.BuildingRewards;
import ru.example.townbuildings.database.model.BuildingData;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кэш бонусов города от зданий.
 *
 * 2.4 — добавлен staleCache: последнее успешно рассчитанное значение.
 *       Проблема была в том, что при invalidate() getRewards возвращал EMPTY,
 *       пока шла async-загрузка — BonusListener терял бонусы за это окно
 *       (просадка exp/harvest в момент апгрейда).
 *
 *       Теперь getRewards не отдаёт EMPTY, пока есть что вернуть:
 *         1) cache       — актуальное значение;
 *         2) sync-build  — мгновенный расчёт из TownCache.buildings (без БД);
 *         3) staleCache  — последнее известное (не инвалидируется);
 *         4) EMPTY       — только на абсолютно холодном старте.
 *
 * P22 — суммирование бонусов вынесено в BuildingRewardsMath; локальный
 *       метод sum(...) стал шорткатом к общему util.
 */
public final class BonusService {

    private final TownBuildingsPlugin plugin;

    /** Актуальный кэш (инвалидируется при апгрейде/админ-изменении). */
    private final Map<UUID, BuildingRewards> cache = new ConcurrentHashMap<>();

    /** 2.4 — последнее успешно рассчитанное значение. НЕ инвалидируется,
     *         служит fallback'ом на окно перезагрузки. */
    private final Map<UUID, BuildingRewards> staleCache = new ConcurrentHashMap<>();

    private final Map<UUID, CompletableFuture<BuildingRewards>> loading = new ConcurrentHashMap<>();

    public BonusService(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
    }

    public void invalidate(@NotNull UUID townUuid) {
        cache.remove(townUuid);
        loading.remove(townUuid);
        // staleCache НЕ трогаем — он и есть fallback.
    }

    public void invalidateAll() {
        cache.clear();
        loading.clear();
        staleCache.clear();
    }

    public @NotNull BuildingRewards getRewards(@NotNull UUID townUuid) {
        // 1. Актуальный кэш.
        BuildingRewards cached = cache.get(townUuid);
        if (cached != null) return cached;

        // 2. 2.4 — синхронный расчёт из TownCache.buildings.
        //    TownCache хранит List<BuildingData> в ConcurrentHashMap;
        //    чтение не требует БД. O(30) — быстро для hot-path.
        List<BuildingData> buildings = plugin.townService().cache().getBuildings(townUuid);
        if (buildings != null) {
            BuildingRewards fresh = sum(buildings);
            cache.put(townUuid, fresh);
            staleCache.put(townUuid, fresh);
            return fresh;
        }

        // 3. 2.4 — TownCache тоже пуст (город давно не открывали GUI).
        //    Отдаём stale — не теряем бонус, пока идёт async-загрузка.
        BuildingRewards stale = staleCache.get(townUuid);
        if (stale != null) {
            scheduleRefresh(townUuid);
            return stale;
        }

        // 4. Холодный старт — тут уже нечего отдавать, только EMPTY
        //    (и тут же запускаем загрузку для следующего вызова).
        scheduleRefresh(townUuid);
        return BuildingRewards.EMPTY;
    }

    /** 2.4 — асинхронный догруз в кэш; не блокирует hot-path. */
    private void scheduleRefresh(@NotNull UUID townUuid) {
        if (loading.containsKey(townUuid)) return;
        CompletableFuture<BuildingRewards> fresh = plugin.townService()
                .getBuildings(townUuid)
                .thenApply(list -> {
                    BuildingRewards r = sum(list);
                    cache.put(townUuid, r);
                    staleCache.put(townUuid, r);
                    return r;
                });
        if (loading.putIfAbsent(townUuid, fresh) == null) {
            fresh.whenComplete((r, ex) -> loading.remove(townUuid));
        }
    }

    private @NotNull BuildingRewards sum(@NotNull List<BuildingData> buildings) {
        // P22 — общая логика вынесена в BuildingRewardsMath,
        // чтобы BonusService и RewardService не расходились.
        return BuildingRewardsMath.sum(plugin.buildingRegistry(), buildings);
    }
}