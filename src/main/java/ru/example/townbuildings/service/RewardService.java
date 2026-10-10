package ru.example.townbuildings.service;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.config.BuildingDefinition;
import ru.example.townbuildings.config.BuildingRewards;
import ru.example.townbuildings.database.model.BuildingData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Расчёт и выдача почасовых наград.
 * 11-C4 — mintIncomeBonusPercent вместо хардкода по TREASURY_ID.
 * 11-D7 — MAX_HOURS_PER_BATCH читается из config.yml.
 * 10    — stockItemsPerHour суммируется кумулятивно (как EffectService),
 *         остальные бонусы — только текущий уровень.
 * 1.2   — flushTownSync сессий /t stock ПЕРЕД depositBatch.
 * 1.3   — refundCityHours / refundPlayerHours при race-потере адресата.
 * 4.4   — maxHoursPerBatch volatile + reloadSettings().
 * P6    — вход в checkAndGiveCityRewards/checkAndGivePlayerRewards
 *         обёрнут в main-таск: методы могут быть вызваны из io-потока,
 *         а первое же обращение — к TownyAPI/Bukkit.
 * P21   — checkAndGivePlayerRewards: игрок должен всё ещё быть в том
 *         городе, за который накопил часы.
 * P7    — giveCityRewards: flushTownSync → flushTownAsync.
 * P22   — sumRewards делегирует в общий BuildingRewardsMath.sum(...).
 */
public final class RewardService {

    private final TownBuildingsPlugin plugin;

    /** 11-D7 / 4.4 — volatile, чтобы reloadSettings мог переписать. */
    private volatile int maxHoursPerBatch;

    public RewardService(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
        this.maxHoursPerBatch = plugin.getConfigManager().online().maxHoursPerBatch();
    }

    /**
     * 4.4 — пересчитать max-hours-per-batch из нового config.yml.
     */
    public void reloadSettings() {
        this.maxHoursPerBatch = plugin.getConfigManager().online().maxHoursPerBatch();
        plugin.getLogger().info("[Reward] max-hours-per-batch=" + maxHoursPerBatch);
    }

    // ============================================================
    //   ГОРОД
    // ============================================================

    public void checkAndGiveCityRewards(@NotNull UUID townUuid) {
        // P6 — метод может быть вызван из io-потока (OnlineTimeService.tick),
        // но первое же обращение идёт к TownyAPI. Оборачиваем весь вход
        // в main-таск; consumeCityHours остаётся async.
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            // 1.3 — проверяем существование города ДО списания часов.
            Town town = TownyAPI.getInstance().getTown(townUuid);
            if (town == null) return;

            plugin.townService().consumeCityHours(townUuid, maxHoursPerBatch).thenAccept(hours -> {
                if (hours <= 0) return;
                Bukkit.getGlobalRegionScheduler().run(plugin,
                        task2 -> giveCityRewards(townUuid, hours));
            });
        });
    }

    private void giveCityRewards(@NotNull UUID townUuid, int hours) {
        Town town = TownyAPI.getInstance().getTown(townUuid);
        if (town == null) {
            // 1.3 — race: город удалён между consume и выдачей.
            //       Возвращаем часы, чтобы не потерять.
            plugin.townDao().refundCityHours(townUuid, hours)
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("[Reward] refundCityHours("
                                + townUuid + "," + hours + ") упал: " + ex.getMessage());
                        return null;
                    });
            return;
        }

        plugin.townService().getBuildings(townUuid).thenAccept(buildings ->
                Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                    long totalTownCoinsPerHour = 0L;
                    Map<Material, Integer> stockPerHour = new HashMap<>();
                    double mintIncomeBonusPercent = 0;                    // 11-C4

                    for (BuildingData bd : buildings) {
                        BuildingDefinition def = plugin.buildingRegistry().byId(bd.buildingId()).orElse(null);
                        if (def == null) continue;

                        // ★ Абсолютные бонусы — только текущий уровень.
                        BuildingRewards rCur = def.level(bd.level())
                                .map(l -> l.rewards())
                                .orElse(BuildingRewards.EMPTY);
                        if (!rCur.isEmpty()) {
                            totalTownCoinsPerHour += rCur.townCoinsPerHour();
                            mintIncomeBonusPercent += rCur.mintIncomeBonusPercent();
                        }

                        // ★ Кумулятивно только для stockItemsPerHour.
                        int maxLvl = Math.min(bd.level(), def.maxLevel());
                        for (int lvl = 1; lvl <= maxLvl; lvl++) {
                            BuildingRewards r = def.level(lvl)
                                    .map(l -> l.rewards())
                                    .orElse(BuildingRewards.EMPTY);
                            for (var e : r.stockItemsPerHour().entrySet()) {
                                stockPerHour.merge(e.getKey(), e.getValue(), Integer::sum);
                            }
                        }
                    }

                    if (mintIncomeBonusPercent > 0) {
                        totalTownCoinsPerHour = totalTownCoinsPerHour
                                + Math.round(totalTownCoinsPerHour * mintIncomeBonusPercent / 100.0);
                    }

                    long finalTownCoins = totalTownCoinsPerHour * hours;
                    if (finalTownCoins > 0) {
                        plugin.economyService().depositToTown(town, finalTownCoins,
                                "TownBuildings: hourly reward");
                    }

                    if (!stockPerHour.isEmpty()) {
                        int maxPages = resolveStockPages(buildings);
                        Map<Material, Integer> toDeposit = new HashMap<>(stockPerHour.size());
                        for (var e : stockPerHour.entrySet()) {
                            toDeposit.put(e.getKey(), e.getValue() * hours);
                        }

                        // P7 — flush сессий /t stock асинхронно, не блокируя main,
                        // затем depositBatch одной цепочкой.
                        plugin.stockSessions().flushTownAsync(townUuid)
                                .thenCompose(v -> plugin.stockService()
                                        .depositBatch(townUuid, maxPages, toDeposit))
                                .thenAccept(ok -> {
                                    if (!ok) {
                                        plugin.getLogger().info(
                                                "[Reward] /t stock переполнен для города "
                                                        + townUuid + " — часть ресурсов не влезла");
                                        return;
                                    }
                                    // ★ Хранилище пополнено в БД — инвалидируем сессии,
                                    //   чтобы открытые GUI подгрузили свежий снапшот.
                                    Bukkit.getGlobalRegionScheduler().run(plugin, invalidateTask ->
                                            plugin.stockSessions().invalidateTown(townUuid));
                                });
                    }
                }));
    }

    // ============================================================
    //   ИГРОК
    // ============================================================

    public void checkAndGivePlayerRewards(@NotNull UUID playerUuid, @NotNull UUID townUuid) {
        // P6 — как и у city-версии: первое обращение к Bukkit/Towny должно
        // произойти в main. Оборачиваем вход.
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            // 1.3 — проверяем онлайн ДО списания.
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null || !player.isOnline()) return;

            // P21 — игрок должен всё ещё быть в том городе, за который
            // накопил часы. Иначе бывший житель получит выплату из старого
            // города, параллельно копя в новом. В этом случае НЕ списываем
            // и НЕ выдаём; осиротевшая строка tb_player_online будет
            // вычищена отдельным механизмом (или останется как история).
            Town currentTown = plugin.towny().getTownOf(player);
            if (currentTown == null || !currentTown.getUUID().equals(townUuid)) {
                return;
            }

            plugin.onlineTimeDao().consumePlayerHours(playerUuid, townUuid, maxHoursPerBatch)
                    .thenAccept(hours -> {
                        if (hours <= 0) return;
                        Bukkit.getGlobalRegionScheduler().run(plugin,
                                task2 -> givePlayerRewards(playerUuid, townUuid, hours));
                    });
        });
    }

    private void givePlayerRewards(@NotNull UUID playerUuid, @NotNull UUID townUuid, int hours) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.isOnline()) {
            // 1.3 — race: игрок вышел между consume и выдачей.
            //       Возвращаем часы, чтобы не потерять.
            plugin.onlineTimeDao().refundPlayerHours(playerUuid, townUuid, hours)
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("[Reward] refundPlayerHours("
                                + playerUuid + "," + townUuid + "," + hours
                                + ") упал: " + ex.getMessage());
                        return null;
                    });
            return;
        }

        plugin.townService().getBuildings(townUuid).thenAccept(buildings ->
                Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                    long citizenCoinsPerHour = 0L;
                    for (BuildingData bd : buildings) {
                        BuildingDefinition def = plugin.buildingRegistry().byId(bd.buildingId()).orElse(null);
                        if (def == null) continue;
                        citizenCoinsPerHour += def.level(bd.level())
                                .map(l -> l.rewards().citizenCoinsPerHour())
                                .orElse(0L);
                    }
                    if (citizenCoinsPerHour <= 0) return;
                    long total = citizenCoinsPerHour * hours;
                    plugin.economyService().depositToPlayer(player, total,
                            "TownBuildings: bank reward");
                }));
    }

    // ============================================================
    //   Хелперы
    // ============================================================

    private int resolveStockPages(@NotNull List<BuildingData> buildings) {
        int level = buildings.stream()
                .filter(b -> b.buildingId().equals(StockService.WAREHOUSE_STOCK_BUILDING_ID))
                .mapToInt(BuildingData::level)
                .findFirst().orElse(0);
        return 1 + Math.max(0, Math.min(3, level));
    }

    public @NotNull CompletableFuture<BuildingRewards> sumRewards(@NotNull UUID townUuid) {
        // P22 — общая логика вынесена в BuildingRewardsMath.
        return plugin.townService().getBuildings(townUuid)
                .thenApply(buildings -> BuildingRewardsMath.sum(
                        plugin.buildingRegistry(), buildings));
    }
}