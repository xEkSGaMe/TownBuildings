package ru.example.townbuildings.service;

import com.palmergames.bukkit.towny.object.Town;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.config.BuildingDefinition;
import ru.example.townbuildings.config.BuildingDefinitionRegistry;
import ru.example.townbuildings.config.BuildingLevel;
import ru.example.townbuildings.database.model.BuildingData;
import ru.example.townbuildings.util.RomanNumbers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Логика прокачки зданий.
 *
 * C5  — тогл features.buildings.
 * L-2 — страховочный таймаут 30 сек на всю цепочку tryUpgrade. Если
 *       внутренние orTimeout не сработали (например, main-task
 *       scheduler не выполнил отложенный callback в момент onDisable),
 *       inFlight навсегда остался бы занят — «busy» до перезапуска.
 */
public final class BuildingService {

    private static final long DB_TIMEOUT_SECONDS = 20L;

    /** ★ L-2 — общий страховочный таймаут на всю цепочку апгрейда (сек). */
    private static final long SAFETY_TIMEOUT_SECONDS = 30L;

    private final TownBuildingsPlugin plugin;
    private final BuildingDefinitionRegistry registry;
    private final TownService townService;
    private final TownyIntegrationService towny;
    private final EconomyService economy;
    private final InventoryService inventory;
    private final LogService logService;

    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public BuildingService(@NotNull TownBuildingsPlugin plugin,
                           @NotNull BuildingDefinitionRegistry registry,
                           @NotNull TownService townService,
                           @NotNull TownyIntegrationService towny,
                           @NotNull EconomyService economy,
                           @NotNull InventoryService inventory,
                           @NotNull LogService logService) {
        this.plugin = plugin;
        this.registry = registry;
        this.townService = townService;
        this.towny = towny;
        this.economy = economy;
        this.inventory = inventory;
        this.logService = logService;
    }

    public void tryUpgrade(@NotNull UUID townUuid,
                           @NotNull String buildingId,
                           @Nullable Player player,
                           @NotNull Consumer<UpgradeResult> callback) {

        if (!plugin.getConfigManager().features().buildings()) {
            callback.accept(UpgradeResult.inDevelopment());
            return;
        }

        if (!inFlight.add(townUuid)) {
            callback.accept(UpgradeResult.busy());
            return;
        }

        AtomicBoolean called = new AtomicBoolean(false);
        Consumer<UpgradeResult> wrapped = result -> {
            if (called.compareAndSet(false, true)) {
                inFlight.remove(townUuid);
                callback.accept(result);
            } else {
                plugin.getLogger().warning("[Upgrade] Повторный callback для "
                        + townUuid + "/" + buildingId + ": " + result.status());
            }
        };

        // ★ L-2 — общий страховочный таймаут. Если какой-то из внутренних
        // orTimeout не сработал (main-task scheduler не выполнил отложенный
        // callback в момент onDisable, цепочка потерялась в scheduler'е и
        // т.п.), inFlight остался бы занят навсегда — «busy» до перезапуска
        // сервера. delayedExecutor шлёт задачу через java.util.concurrent,
        // не через Bukkit scheduler, поэтому переживает «зависший» main.
        CompletableFuture.delayedExecutor(SAFETY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .execute(() -> {
                    if (called.compareAndSet(false, true)) {
                        plugin.getLogger().warning("[Upgrade] Страховочный timeout "
                                + "для " + townUuid + "/" + buildingId
                                + " — операция не завершилась за " + SAFETY_TIMEOUT_SECONDS + "с");
                        inFlight.remove(townUuid);
                        // callback.accept(...) может вызывать Bukkit API внутри
                        // — оборачиваем в scheduler, чтобы точно попали в main.
                        try {
                            Bukkit.getScheduler().runTask(plugin,
                                    () -> callback.accept(UpgradeResult.timeout()));
                        } catch (Throwable t) {
                            plugin.getLogger().severe("[Upgrade] safety-callback упал: "
                                    + t.getMessage());
                        }
                    }
                });

        try {
            tryUpgrade0(townUuid, buildingId, player, wrapped);
        } catch (Throwable t) {
            plugin.getLogger().severe("[Upgrade] tryUpgrade0 threw for "
                    + townUuid + "/" + buildingId + ": " + t);
            wrapped.accept(UpgradeResult.inventoryError(ResourceCheck.trivial()));
        }
    }

    // ============================================================
    //   Admin-lock
    // ============================================================

    public boolean isInFlight(@NotNull UUID townUuid) {
        return inFlight.contains(townUuid);
    }

    public boolean tryAdminLock(@NotNull UUID townUuid) {
        return inFlight.add(townUuid);
    }

    public void releaseAdminLock(@NotNull UUID townUuid) {
        inFlight.remove(townUuid);
    }

    private void tryUpgrade0(@NotNull UUID townUuid,
                             @NotNull String buildingId,
                             @Nullable Player player,
                             @NotNull Consumer<UpgradeResult> callback) {

        BuildingDefinition def = registry.byId(buildingId).orElse(null);
        if (def == null) {
            callback.accept(UpgradeResult.unknownBuilding());
            return;
        }
        if (def.inDevelopment()) {
            callback.accept(UpgradeResult.inDevelopment());
            return;
        }

        List<BuildingData> cached = plugin.townService().cache().getBuildings(townUuid);
        if (cached != null) {
            int cachedLevel = cached.stream()
                    .filter(b -> b.buildingId().equals(buildingId))
                    .mapToInt(BuildingData::level)
                    .findFirst()
                    .orElse(0);
            if (!def.isMax(cachedLevel)) {
                doUpgrade(townUuid, buildingId, def, cached, player, callback);
                return;
            }
        }

        townService.getBuildings(townUuid)
                .orTimeout(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((list, ex) -> {
                    if (ex != null || list == null) {
                        plugin.getLogger().warning("[Upgrade] getBuildings timeout/fail для "
                                + townUuid + ": " + (ex != null ? ex.getMessage() : "null"));
                        Bukkit.getScheduler().runTask(plugin,
                                () -> callback.accept(UpgradeResult.timeout()));
                        return;
                    }
                    int freshLevel = list.stream()
                            .filter(b -> b.buildingId().equals(buildingId))
                            .mapToInt(BuildingData::level)
                            .findFirst()
                            .orElse(0);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (def.isMax(freshLevel)) {
                            callback.accept(UpgradeResult.maxLevel(freshLevel));
                            return;
                        }
                        doUpgrade(townUuid, buildingId, def, list, player, callback);
                    });
                });
    }

    private void doUpgrade(@NotNull UUID townUuid,
                           @NotNull String buildingId,
                           @NotNull BuildingDefinition def,
                           @NotNull List<BuildingData> snapshot,
                           @Nullable Player player,
                           @NotNull Consumer<UpgradeResult> callback) {

        int currentLevel = snapshot.stream()
                .filter(b -> b.buildingId().equals(buildingId))
                .mapToInt(BuildingData::level)
                .findFirst()
                .orElse(0);

        if (def.isMax(currentLevel)) {
            callback.accept(UpgradeResult.maxLevel(currentLevel));
            return;
        }

        BuildingLevel next = def.nextLevel(currentLevel).orElse(null);
        if (next == null) {
            callback.accept(UpgradeResult.maxLevel(currentLevel));
            return;
        }

        Town town = (player != null ? towny.getTownOf(player) : towny.getTownByUuid(townUuid));
        if (town == null || !towny.getTownUuid(town).equals(townUuid)) {
            callback.accept(UpgradeResult.noTown());
            return;
        }

        OptionalDouble balanceOpt = economy.getTownBalanceSafe(town);
        if (balanceOpt.isEmpty() && next.costCoins() > 0) {
            balanceOpt = economy.getTownBalanceSafe(town);
            if (balanceOpt.isEmpty()) {
                if (player != null) plugin.soundService().playError(player);
                callback.accept(UpgradeResult.economyError(ResourceCheck.trivial()));
                return;
            }
        }
        final long townBalance = (long) Math.floor(balanceOpt.orElse(0.0));

        if (next.costCoins() > townBalance) {
            ResourceCheck check = buildCheck(next, townBalance, next.costItems());
            if (player != null) plugin.soundService().playError(player);
            callback.accept(UpgradeResult.notEnough(check));
            return;
        }

        plugin.inventorySessions().flushTownAsync(townUuid)
                .thenCompose(v -> inventory.withdrawIfAvailable(townUuid, next.costItems()))
                .orTimeout(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((result, ex) -> {
                    if (ex != null || result == null) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (player != null) plugin.soundService().playError(player);
                            callback.accept(UpgradeResult.timeout());
                        });
                        return;
                    }

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!result.ok()) {
                            Map<Material, Integer> have = new HashMap<>();
                            for (Map.Entry<Material, Integer> e : next.costItems().entrySet()) {
                                int miss = result.missing().getOrDefault(e.getKey(), 0);
                                have.put(e.getKey(), Math.max(0, e.getValue() - miss));
                            }
                            ResourceCheck check = buildCheck(next, townBalance, have);
                            if (player != null) plugin.soundService().playError(player);
                            callback.accept(UpgradeResult.notEnough(check));
                            return;
                        }

                        if (next.costCoins() > 0) {
                            boolean coinsOk = economy.withdrawFromTown(town, next.costCoins(),
                                    "TownBuildings: upgrade " + buildingId + " to lvl " + next.level());
                            if (!coinsOk) {
                                double fresh = economy.getTownBalance(town);
                                if (fresh >= next.costCoins()) {
                                    coinsOk = economy.withdrawFromTown(town, next.costCoins(),
                                            "TownBuildings: upgrade " + buildingId
                                                    + " to lvl " + next.level() + " (retry)");
                                }
                            }
                            if (!coinsOk) {
                                rollbackItems(townUuid, next.costItems(), result.takenFrom());
                                if (player != null) plugin.soundService().playError(player);
                                callback.accept(UpgradeResult.economyError(
                                        buildCheck(next, townBalance, next.costItems())));
                                return;
                            }
                        }

                        plugin.inventorySessions().invalidateTownAsync(townUuid);

                        finishUpgrade(townUuid, buildingId, def, currentLevel, next,
                                town, player, result.takenFrom(), callback);
                    });
                });
    }

    private @NotNull ResourceCheck buildCheck(@NotNull BuildingLevel next,
                                              long townBalance,
                                              @NotNull Map<Material, Integer> haveItems) {
        ResourceCheck.Builder b = ResourceCheck.builder()
                .coins(next.costCoins(), townBalance);
        for (Map.Entry<Material, Integer> e : next.costItems().entrySet()) {
            b.item(e.getKey(), e.getValue(),
                    haveItems.getOrDefault(e.getKey(), 0));
        }
        return b.build();
    }

    private int resolveWarehouseInvPages(@NotNull UUID townUuid) {
        List<BuildingData> buildings = plugin.townService().cache().getBuildings(townUuid);
        if (buildings == null) return 1;
        int level = buildings.stream()
                .filter(b -> "warehouse_inv".equals(b.buildingId()))
                .mapToInt(BuildingData::level)
                .findFirst()
                .orElse(0);
        return 1 + Math.max(0, Math.min(3, level));
    }

    private void rollbackItems(@NotNull UUID townUuid,
                               @NotNull Map<Material, Integer> items,
                               @NotNull Map<Material, List<WithdrawResult.SlotRef>> preferred) {
        if (items.isEmpty()) return;
        int maxPages = resolveWarehouseInvPages(townUuid);

        plugin.inventoryDao().depositBack(townUuid, maxPages, items, preferred)
                .whenComplete((ok, ex) -> {
                    if (ex != null) {
                        plugin.getLogger().severe("[Upgrade] rollback items FAILED для "
                                + townUuid + ": " + ex.getMessage()
                                + " | потеряно: " + items);
                        return;
                    }
                    if (!Boolean.TRUE.equals(ok)) {
                        plugin.getLogger().warning("[Upgrade] rollback items overflow для "
                                + townUuid + " (maxPages=" + maxPages + "): " + items);
                    }
                });
    }

    private void finishUpgrade(@NotNull UUID townUuid,
                               @NotNull String buildingId,
                               @NotNull BuildingDefinition def,
                               int currentLevel,
                               @NotNull BuildingLevel next,
                               @NotNull Town town,
                               @Nullable Player player,
                               @NotNull Map<Material, List<WithdrawResult.SlotRef>> takenFrom,
                               @NotNull Consumer<UpgradeResult> callback) {

        int newLevel = currentLevel + 1;

        AtomicBoolean rollbackDone = new AtomicBoolean(false);

        Runnable afterRollback = () -> {
            if (!rollbackDone.compareAndSet(false, true)) return;
            Bukkit.getScheduler().runTask(plugin,
                    () -> plugin.inventorySessions().invalidateTownAsync(townUuid));
        };

        Runnable rollback = () -> {
            if (next.costCoins() > 0) {
                boolean coinsOk = economy.depositToTown(town, next.costCoins(),
                        "TownBuildings: rollback " + buildingId);
                if (!coinsOk) {
                    plugin.getLogger().severe("[Upgrade] ROLLBACK COINS FAILED town="
                            + town.getName() + " (" + townUuid + ") building="
                            + buildingId + " amount=" + next.costCoins()
                            + " — верните монеты вручную!");
                    logService.logRollbackFailed(townUuid,
                            player != null ? player.getUniqueId() : null,
                            buildingId, next.costCoins(), "coins_refund_failed");
                }
            }
            if (next.costItems().isEmpty()) {
                afterRollback.run();
            } else {
                plugin.inventoryDao().depositBack(
                                townUuid, resolveWarehouseInvPages(townUuid),
                                next.costItems(), takenFrom)
                        .whenComplete((ok2, ex2) -> {
                            if (ex2 != null) {
                                plugin.getLogger().severe("[Upgrade] rollback items FAILED для "
                                        + townUuid + ": " + ex2.getMessage()
                                        + " | потеряно: " + next.costItems());
                            } else if (!Boolean.TRUE.equals(ok2)) {
                                plugin.getLogger().warning("[Upgrade] rollback items overflow для "
                                        + townUuid + ": " + next.costItems());
                            }
                            afterRollback.run();
                        });
            }
        };

        townService.upgradeBuildingLevel(townUuid, buildingId, currentLevel, newLevel)
                .orTimeout(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((applied, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ex != null) {
                        plugin.getLogger().severe("[Upgrade] upgradeBuildingLevel failed for "
                                + townUuid + "/" + buildingId + ": " + ex.getMessage());
                        rollback.run();
                        if (player != null) plugin.soundService().playError(player);
                        callback.accept(UpgradeResult.economyError(ResourceCheck.trivial()));
                        return;
                    }
                    if (!Boolean.TRUE.equals(applied)) {
                        plugin.getLogger().warning("[Upgrade] CAS-конфликт для "
                                + townUuid + "/" + buildingId + " expected=" + currentLevel
                                + " → откат ресурсов");
                        townService.cache().invalidateBuildings(townUuid);
                        rollback.run();
                        if (player != null) plugin.soundService().playError(player);
                        callback.accept(UpgradeResult.busy());
                        return;
                    }

                    logService.logUpgrade(townUuid,
                            player != null ? player.getUniqueId() : null,
                            buildingId, currentLevel, newLevel, next);

                    try {
                        plugin.townyWarService().onBuildingLevelChanged(
                                town, buildingId, currentLevel, newLevel);
                    } catch (Throwable t) {
                        plugin.getLogger().warning("[Upgrade] TownyWar sync failed: "
                                + t.getMessage());
                    }

                    plugin.effectService().invalidate(townUuid);
                    plugin.bonusService().invalidate(townUuid);

                    UUID mayor = towny.getMayorUuid(town);
                    if (mayor != null) {
                        plugin.mayorChangeListener().refreshPermissions(townUuid, mayor);
                    }

                    plugin.universityService().syncTown(town);

                    if (player != null) {
                        plugin.soundService().playUpgradeSuccess(player);
                        plugin.soundService().showUpgradeTitle(
                                player,
                                plugin.getMessagesConfig().get("buildings.upgrade.success-title"),
                                plugin.getMessagesConfig().get("buildings.upgrade.success-subtitle",
                                        Map.of(
                                                "building", def.displayName(),
                                                "level", RomanNumbers.toRoman(newLevel)
                                        ))
                        );
                    }

                    broadcastUpgrade(town, def, newLevel);
                    callback.accept(UpgradeResult.success(newLevel, next));
                }));
    }

    private void broadcastUpgrade(@NotNull Town town,
                                  @NotNull BuildingDefinition def,
                                  int newLevel) {
        Map<String, String> ph = Map.of(
                "town", town.getName(),
                "building", def.displayName(),
                "level", Integer.toString(newLevel)
        );
        Component msg = plugin.getMessagesConfig().get("buildings.upgrade.broadcast", ph);

        for (Player member : towny.getOnlineMembers(town)) {
            member.sendMessage(msg);
        }
        plugin.getLogger().info("[Upgrade] " + town.getName() + ": "
                + def.id() + " -> " + newLevel);
    }
}