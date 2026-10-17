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
 *
 * WND — награды от построенных чудес суммируются поверх наград зданий:
 *       stockItemsPerHour — через wonderService.getCachedRewards;
 *       town/citizen coins — тоже добавляются (обычно 0, но поля есть).
 */
public final class RewardService {

    private static final String MINT_BUILDING_ID = "mint";

    private final TownBuildingsPlugin plugin;

    private volatile int maxHoursPerBatch;

    public RewardService(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
        this.maxHoursPerBatch = plugin.getConfigManager().online().maxHoursPerBatch();
    }

    public void reloadSettings() {
        this.maxHoursPerBatch = plugin.getConfigManager().online().maxHoursPerBatch();
        plugin.getLogger().info("[Reward] max-hours-per-batch=" + maxHoursPerBatch);
    }

    public void checkAndGiveCityRewards(@NotNull UUID townUuid) {
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
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
            plugin.townDao().refundCityHours(townUuid, hours)
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("[Reward] refundCityHours("
                                + townUuid + "," + hours + ") упал: " + ex.getMessage());
                        return null;
                    });
            return;
        }

        plugin.townService().getBuildings(townUuid).thenAccept(buildings -> {
            try {
                Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                    long nonMintCoinsPerHour = 0L;
                    long mintCoinsPerHour = 0L;
                    Map<Material, Integer> stockPerHour = new HashMap<>();
                    double mintIncomeBonusPercent = 0;

                    for (BuildingData bd : buildings) {
                        BuildingDefinition def = plugin.buildingRegistry().byId(bd.buildingId()).orElse(null);
                        if (def == null) continue;

                        BuildingRewards rCur = def.level(bd.level())
                                .map(l -> l.rewards())
                                .orElse(BuildingRewards.EMPTY);
                        if (!rCur.isEmpty()) {
                            if (MINT_BUILDING_ID.equals(bd.buildingId())) {
                                mintCoinsPerHour += rCur.townCoinsPerHour();
                            } else {
                                nonMintCoinsPerHour += rCur.townCoinsPerHour();
                            }
                            mintIncomeBonusPercent += rCur.mintIncomeBonusPercent();
                        }

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

                    // ★ NEW — награды от чудес.
                    BuildingRewards wRew = plugin.wonderService() != null
                            ? plugin.wonderService().getCachedRewards(townUuid)
                            : BuildingRewards.EMPTY;
                    for (var e : wRew.stockItemsPerHour().entrySet()) {
                        stockPerHour.merge(e.getKey(), e.getValue(), Integer::sum);
                    }
                    nonMintCoinsPerHour += wRew.townCoinsPerHour();

                    long mintWithBonus = mintCoinsPerHour;
                    if (mintIncomeBonusPercent > 0 && mintCoinsPerHour > 0) {
                        mintWithBonus += Math.round(
                                mintCoinsPerHour * mintIncomeBonusPercent / 100.0);
                    }
                    long totalTownCoinsPerHour = nonMintCoinsPerHour + mintWithBonus;

                    long finalTownCoins = totalTownCoinsPerHour * hours;
                    boolean coinsOk = true;
                    if (finalTownCoins > 0) {
                        coinsOk = plugin.economyService().depositToTown(town, finalTownCoins,
                                "TownBuildings: hourly reward");
                        if (!coinsOk) {
                            plugin.getLogger().warning("[Reward] depositToTown(" + town.getName()
                                    + ", " + finalTownCoins + ") не выполнен — возврат "
                                    + hours + " ч. городу " + townUuid);
                        }
                    }

                    if (!coinsOk) {
                        refundCityHours(townUuid, hours);
                        return;
                    }

                    if (!stockPerHour.isEmpty()) {
                        int maxPages = resolveStockPages(buildings);
                        Map<Material, Integer> toDeposit = new HashMap<>(stockPerHour.size());
                        for (var e : stockPerHour.entrySet()) {
                            toDeposit.put(e.getKey(), e.getValue() * hours);
                        }

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
                                    Bukkit.getGlobalRegionScheduler().run(plugin, invalidateTask ->
                                            plugin.stockSessions().invalidateTown(townUuid));
                                })
                                .exceptionally(ex -> {
                                    plugin.getLogger().warning("[Reward] depositBatch("
                                            + townUuid + ") упал: " + ex.getMessage());
                                    return null;
                                });
                    }
                });
            } catch (Throwable t) {
                plugin.getLogger().warning("[Reward] giveCityRewards(" + townUuid
                        + ") сбой: " + t + " — возврат " + hours + " ч.");
                refundCityHours(townUuid, hours);
            }
        }).exceptionally(ex -> {
            plugin.getLogger().warning("[Reward] getBuildings(" + townUuid
                    + ") упал: " + ex.getMessage() + " — возврат " + hours + " ч.");
            refundCityHours(townUuid, hours);
            return null;
        });
    }

    private void refundCityHours(@NotNull UUID townUuid, int hours) {
        plugin.townDao().refundCityHours(townUuid, hours)
                .exceptionally(ex -> {
                    plugin.getLogger().severe("[Reward] refundCityHours("
                            + townUuid + "," + hours + ") упал: " + ex.getMessage());
                    return null;
                });
    }

    public void checkAndGivePlayerRewards(@NotNull UUID playerUuid, @NotNull UUID townUuid) {
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null || !player.isOnline()) return;

            Town currentTown = plugin.towny().getTownOf(player);
            if (currentTown == null || !currentTown.getUUID().equals(townUuid)) {
                return;
            }

            plugin.onlineTimeDao().consumePlayerHours(playerUuid, townUuid, maxHoursPerBatch)
                    .thenAccept(hours -> {
                        if (hours <= 0) return;
                        Bukkit.getGlobalRegionScheduler().run(plugin, mainTask -> {
                            Player p = Bukkit.getPlayer(playerUuid);
                            Town nowTown = (p != null && p.isOnline())
                                    ? plugin.towny().getTownOf(p) : null;
                            if (p == null || !p.isOnline() || nowTown == null
                                    || !nowTown.getUUID().equals(townUuid)) {
                                plugin.onlineTimeDao().refundPlayerHours(playerUuid, townUuid, hours)
                                        .exceptionally(ex -> {
                                            plugin.getLogger().warning("[Reward] refundPlayerHours(pre-give,"
                                                    + playerUuid + "," + townUuid + "," + hours
                                                    + ") упал: " + ex.getMessage());
                                            return null;
                                        });
                                return;
                            }
                            givePlayerRewards(playerUuid, townUuid, hours);
                        });
                    })
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("[Reward] consumePlayerHours("
                                + playerUuid + "," + townUuid + ") упал: " + ex.getMessage());
                        return null;
                    });
        });
    }

    private void givePlayerRewards(@NotNull UUID playerUuid, @NotNull UUID townUuid, int hours) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.isOnline()) {
            plugin.onlineTimeDao().refundPlayerHours(playerUuid, townUuid, hours)
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("[Reward] refundPlayerHours("
                                + playerUuid + "," + townUuid + "," + hours
                                + ") упал: " + ex.getMessage());
                        return null;
                    });
            return;
        }

        plugin.townService().getBuildings(townUuid).thenAccept(buildings -> {
            try {
                Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                    long citizenCoinsPerHour = 0L;
                    for (BuildingData bd : buildings) {
                        BuildingDefinition def = plugin.buildingRegistry().byId(bd.buildingId()).orElse(null);
                        if (def == null) continue;
                        citizenCoinsPerHour += def.level(bd.level())
                                .map(l -> l.rewards().citizenCoinsPerHour())
                                .orElse(0L);
                    }
                    // ★ NEW — citizen-coins от чудес (обычно 0, но поле есть).
                    if (plugin.wonderService() != null) {
                        citizenCoinsPerHour += plugin.wonderService()
                                .getCachedRewards(townUuid).citizenCoinsPerHour();
                    }
                    if (citizenCoinsPerHour <= 0) return;
                    long total = citizenCoinsPerHour * hours;
                    boolean ok = plugin.economyService().depositToPlayer(player, total,
                            "TownBuildings: bank reward");
                    if (!ok) {
                        plugin.getLogger().warning("[Reward] depositToPlayer(" + player.getName()
                                + ", " + total + ") не выполнен — возврат " + hours + " ч.");
                        refundPlayerHours(playerUuid, townUuid, hours);
                    }
                });
            } catch (Throwable t) {
                plugin.getLogger().warning("[Reward] givePlayerRewards(" + playerUuid
                        + ") сбой: " + t + " — возврат " + hours + " ч.");
                refundPlayerHours(playerUuid, townUuid, hours);
            }
        }).exceptionally(ex -> {
            plugin.getLogger().warning("[Reward] getBuildings(player," + townUuid
                    + ") упал: " + ex.getMessage() + " — возврат " + hours + " ч.");
            refundPlayerHours(playerUuid, townUuid, hours);
            return null;
        });
    }

    private void refundPlayerHours(@NotNull UUID playerUuid, @NotNull UUID townUuid, int hours) {
        plugin.onlineTimeDao().refundPlayerHours(playerUuid, townUuid, hours)
                .exceptionally(ex -> {
                    plugin.getLogger().severe("[Reward] refundPlayerHours("
                            + playerUuid + "," + townUuid + "," + hours
                            + ") упал: " + ex.getMessage());
                    return null;
                });
    }

    private int resolveStockPages(@NotNull List<BuildingData> buildings) {
        int level = buildings.stream()
                .filter(b -> b.buildingId().equals(StockService.WAREHOUSE_STOCK_BUILDING_ID))
                .mapToInt(BuildingData::level)
                .findFirst().orElse(0);
        return 1 + Math.max(0, Math.min(3, level));
    }

    public @NotNull CompletableFuture<BuildingRewards> sumRewards(@NotNull UUID townUuid) {
        return plugin.townService().getBuildings(townUuid)
                .thenApply(list -> BuildingRewardsMath.sum(plugin.buildingRegistry(), list));
    }
}