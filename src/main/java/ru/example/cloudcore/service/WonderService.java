package ru.example.cloudcore.service;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.config.BuildingRewards;
import ru.example.cloudcore.config.TimedItem;
import ru.example.cloudcore.config.WonderConfig;
import ru.example.cloudcore.config.WonderRegistry;
import ru.example.cloudcore.database.dao.WonderDao;
import ru.example.cloudcore.database.model.BuildingData;
import ru.example.cloudcore.CloudCorePlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import ru.example.cloudcore.CloudCorePlugin;
public final class WonderService {

    private static final long DB_TIMEOUT_SECONDS = 20L;

    private final CloudCorePlugin plugin;
    private final WonderRegistry registry;
    private final WonderDao dao;
    private final TownService townService;
    private final InventoryService inventory;
    private final EconomyService economy;
    private final LogService logService;

    private final Map<UUID, List<WonderConfig>> builtByTown = new ConcurrentHashMap<>();
    private final Map<String, UUID> townByWonder       = new ConcurrentHashMap<>();
    private final Map<String, String> townNameByWonder = new ConcurrentHashMap<>();

    /** Админ-бонус к лимиту слотов (аддитивный). */
    private final Map<UUID, Integer> adminSlotsBonus   = new ConcurrentHashMap<>();

    /**
     * ★ timed-items: аккумулятор «сколько часов уже накоплено» по каждому
     *   чудо-предмету для каждого города. Ключ — "wonder_id|MATERIAL|period".
     *   In-memory, сбрасывается при рестарте (см. TimedItem).
     */
    private final Map<UUID, Map<String, Double>> timedProgress = new ConcurrentHashMap<>();

    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public WonderService(@NotNull CloudCorePlugin plugin,
                         @NotNull WonderRegistry registry,
                         @NotNull WonderDao dao,
                         @NotNull TownService townService,
                         @NotNull InventoryService inventory,
                         @NotNull EconomyService economy,
                         @NotNull LogService logService) {
        this.plugin = plugin;
        this.registry = registry;
        this.dao = dao;
        this.townService = townService;
        this.inventory = inventory;
        this.economy = economy;
        this.logService = logService;
    }

    public void warmup() {
        dao.findAll().whenComplete((list, ex) -> {
            if (ex != null || list == null) {
                plugin.getLogger().severe("[Wonders] warmup: "
                        + (ex != null ? ex.getMessage() : "null"));
                return;
            }
            Map<UUID, List<WonderConfig>> byTown = new HashMap<>();
            Map<String, UUID> wToTown = new HashMap<>();
            Map<String, String> wToName = new HashMap<>();
            for (var p : list) {
                WonderConfig wc = registry.byId(p.wonderId()).orElse(null);
                if (wc == null) continue;
                byTown.computeIfAbsent(p.townUuid(), k -> new ArrayList<>()).add(wc);
                wToTown.put(p.wonderId(), p.townUuid());
                wToName.put(p.wonderId(), p.townName());
            }
            builtByTown.clear();
            builtByTown.putAll(byTown);
            townByWonder.clear();
            townByWonder.putAll(wToTown);
            townNameByWonder.clear();
            townNameByWonder.putAll(wToName);
            plugin.getLogger().info("[Wonders] Загружено построек: " + list.size());
        });

        dao.findAllSlotsBonuses().whenComplete((map, ex) -> {
            if (ex != null || map == null) {
                plugin.getLogger().severe("[Wonders] warmup slots: "
                        + (ex != null ? ex.getMessage() : "null"));
                return;
            }
            adminSlotsBonus.clear();
            adminSlotsBonus.putAll(map);
            plugin.getLogger().info("[Wonders] Загружено бонусов слотов: " + map.size());
        });
    }

    public void onTownDeleted(@NotNull UUID townUuid) {
        List<WonderConfig> removed = builtByTown.remove(townUuid);
        timedProgress.remove(townUuid);
        adminSlotsBonus.remove(townUuid);
        if (removed == null) return;
        for (WonderConfig w : removed) {
            townByWonder.remove(w.id());
            townNameByWonder.remove(w.id());
        }
    }

    public @NotNull List<WonderConfig> getBuiltWonders(@NotNull UUID townUuid) {
        return builtByTown.getOrDefault(townUuid, List.of());
    }

    public @NotNull Optional<String> getBuiltBy(@NotNull String wonderId) {
        return Optional.ofNullable(townNameByWonder.get(wonderId));
    }

    public @NotNull Optional<UUID> getBuiltTownUuid(@NotNull String wonderId) {
        return Optional.ofNullable(townByWonder.get(wonderId));
    }

    public @NotNull BuildingRewards getCachedRewards(@NotNull UUID townUuid) {
        BuildingRewards total = BuildingRewards.EMPTY;
        for (WonderConfig w : getBuiltWonders(townUuid)) {
            total = BuildingRewardsMath.merge(total, w.rewards());
        }
        return total;
    }

    /**
     * ★ timed-items: добавляем {@code hours} часов к аккумулятору каждого
     *   timed-item всех построенных чудес города и возвращаем те предметы,
     *   чей период уже накопился (с учётом кратности).
     */
    public @NotNull Map<Material, Integer> consumeDueTimedItems(@NotNull UUID townUuid,
                                                                int hours) {
        if (hours <= 0) return Map.of();
        List<WonderConfig> built = getBuiltWonders(townUuid);
        if (built.isEmpty()) return Map.of();

        Map<String, Double> townProgress = timedProgress.computeIfAbsent(
                townUuid, k -> new ConcurrentHashMap<>());

        Map<Material, Integer> out = new HashMap<>();
        for (WonderConfig wc : built) {
            for (TimedItem ti : wc.timedItems()) {
                String key = wc.id() + "|" + ti.material().name() + "|" + ti.periodHours();
                double accum = townProgress.getOrDefault(key, 0.0) + hours;
                int cycles = 0;
                while (accum >= ti.periodHours()) {
                    cycles++;
                    accum -= ti.periodHours();
                }
                if (cycles > 0) {
                    out.merge(ti.material(), cycles * ti.amount(), Integer::sum);
                }
                townProgress.put(key, accum);
            }
        }
        return out;
    }

    public int getSlotLimit(@NotNull UUID townUuid) {
        int limit = registry.baseSlots();
        List<BuildingData> buildings = townService.cache().getBuildings(townUuid);
        if (buildings != null) {
            limit += BuildingRewardsMath.sum(plugin.buildingRegistry(), buildings)
                    .wonderSlotsBonus();
        }
        for (WonderConfig w : getBuiltWonders(townUuid)) {
            limit += w.rewards().wonderSlotsBonus() + w.wonderSlotsBonus();
        }
        limit += adminSlotsBonus.getOrDefault(townUuid, 0);
        return Math.max(0, limit);
    }

    public int getUsedSlots(@NotNull UUID townUuid) {
        return getBuiltWonders(townUuid).size();
    }

    public double getClaimDiscountPercent(@NotNull UUID townUuid) {
        double total = 0.0;
        for (WonderConfig w : getBuiltWonders(townUuid)) {
            total += w.rewards().claimDiscountPercent();
        }
        return total;
    }

    public void tryBuild(@NotNull UUID townUuid,
                         @NotNull String wonderId,
                         @Nullable Player player,
                         @NotNull Consumer<WonderBuildResult> callback) {

        if (!plugin.getConfigManager().features().wonders()) {
            callback.accept(WonderBuildResult.error());
            return;
        }

        WonderConfig wc = registry.byId(wonderId).orElse(null);
        if (wc == null) { callback.accept(WonderBuildResult.error()); return; }

        if (getBuiltWonders(townUuid).stream().anyMatch(w -> w.id().equals(wonderId))) {
            callback.accept(WonderBuildResult.alreadyOwned()); return;
        }

        UUID ownerTown = townByWonder.get(wonderId);
        if (ownerTown != null && !ownerTown.equals(townUuid)) {
            callback.accept(WonderBuildResult.alreadyTaken(townNameByWonder.get(wonderId)));
            return;
        }

        int used  = getUsedSlots(townUuid);
        int limit = getSlotLimit(townUuid);
        if (used >= limit) {
            callback.accept(WonderBuildResult.noSlots(used, limit)); return;
        }

        if (!inFlight.add(townUuid)) {
            callback.accept(WonderBuildResult.busy()); return;
        }

        AtomicBoolean called = new AtomicBoolean(false);
        Consumer<WonderBuildResult> wrapped = r -> {
            if (called.compareAndSet(false, true)) {
                inFlight.remove(townUuid);
                callback.accept(r);
            }
        };

        Bukkit.getScheduler().runTask(plugin, () -> doBuild(townUuid, wc, player, wrapped));
    }

    private void doBuild(@NotNull UUID townUuid, @NotNull WonderConfig wc,
                         @Nullable Player player,
                         @NotNull Consumer<WonderBuildResult> callback) {

        var town = plugin.towny().getTownByUuid(townUuid);
        if (town == null) { callback.accept(WonderBuildResult.error()); return; }

        OptionalDouble balanceOpt = economy.getTownBalanceSafe(town);
        long townBalance = (long) Math.floor(balanceOpt.orElse(0.0));

        if (wc.costCoins() > townBalance) {
            callback.accept(WonderBuildResult.notEnough(
                    buildCheck(wc, townBalance, wc.costItems())));
            return;
        }

        plugin.inventorySessions().lockTown(townUuid);
        plugin.stockSessions().lockTown(townUuid);

        inventory.withdrawIfAvailable(townUuid, wc.costItems())
                .orTimeout(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((result, ex) -> {
                    if (ex != null || result == null) {
                        unlockSessions(townUuid);
                        Bukkit.getScheduler().runTask(plugin,
                                () -> callback.accept(WonderBuildResult.timeout()));
                        return;
                    }

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!result.ok()) {
                            unlockSessions(townUuid);
                            Map<Material, Integer> have = new HashMap<>();
                            for (var e : wc.costItems().entrySet()) {
                                int miss = result.missing().getOrDefault(e.getKey(), 0);
                                have.put(e.getKey(), Math.max(0, e.getValue() - miss));
                            }
                            callback.accept(WonderBuildResult.notEnough(
                                    buildCheck(wc, townBalance, have)));
                            return;
                        }

                        if (wc.costCoins() > 0) {
                            boolean ok = economy.withdrawFromTown(town, wc.costCoins(),
                                    "TownBuildings: wonder " + wc.id());
                            if (!ok) {
                                rollbackItems(townUuid, wc.costItems(), result.takenFrom());
                                unlockSessions(townUuid);
                                callback.accept(WonderBuildResult.error());
                                return;
                            }
                        }

                        dao.tryInsert(townUuid, wc.id())
                                .orTimeout(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                                .whenComplete((inserted, ex2) -> Bukkit.getScheduler().runTask(plugin, () -> {
                                    if (ex2 != null) {
                                        if (wc.costCoins() > 0) {
                                            economy.depositToTown(town, wc.costCoins(),
                                                    "TownBuildings: wonder rollback " + wc.id());
                                        }
                                        rollbackItems(townUuid, wc.costItems(), result.takenFrom());
                                        unlockSessions(townUuid);
                                        callback.accept(WonderBuildResult.error());
                                        return;
                                    }
                                    if (!Boolean.TRUE.equals(inserted)) {
                                        if (wc.costCoins() > 0) {
                                            economy.depositToTown(town, wc.costCoins(),
                                                    "TownBuildings: wonder race rollback " + wc.id());
                                        }
                                        rollbackItems(townUuid, wc.costItems(), result.takenFrom());
                                        unlockSessions(townUuid);
                                        callback.accept(WonderBuildResult.alreadyTaken(
                                                townNameByWonder.get(wc.id())));
                                        return;
                                    }

                                    builtByTown.computeIfAbsent(townUuid, k -> new ArrayList<>()).add(wc);
                                    townByWonder.put(wc.id(), townUuid);
                                    townNameByWonder.put(wc.id(), town.getName());

                                    plugin.effectService().invalidate(townUuid);
                                    plugin.bonusService().invalidate(townUuid);

                                    // ★ townywar-награды чудес (starting-points, max-flags).
                                    try {
                                        plugin.townyWarService().onWonderBuilt(town, wc);
                                    } catch (Throwable t) {
                                        plugin.getLogger().warning("[Wonder] TownyWar sync (build) failed: "
                                                + t.getMessage());
                                    }

                                    logService.logAdminGive(townUuid,
                                            player != null ? player.getUniqueId() : null,
                                            "wonder:" + wc.id(), 1);

                                    broadcastBuild(town, wc);

                                    unlockSessions(townUuid);
                                    callback.accept(WonderBuildResult.success(wc));
                                }));
                    });
                });
    }

    private void unlockSessions(@NotNull UUID townUuid) {
        try { plugin.inventorySessions().unlockTown(townUuid); } catch (Throwable ignored) {}
        try { plugin.stockSessions().unlockTown(townUuid); } catch (Throwable ignored) {}
    }

    private void rollbackItems(@NotNull UUID townUuid,
                               @NotNull Map<Material, Integer> items,
                               @NotNull Map<Material, List<WithdrawResult.SlotRef>> preferred) {
        if (items.isEmpty()) return;
        int maxPages = resolveWarehouseInvPages(townUuid);
        plugin.inventoryDao().depositBack(townUuid, maxPages, items, preferred)
                .exceptionally(ex -> {
                    plugin.getLogger().severe("[Wonders] rollback items FAILED для "
                            + townUuid + ": " + ex.getMessage() + " | items=" + items);
                    return false;
                });
    }

    private int resolveWarehouseInvPages(@NotNull UUID townUuid) {
        List<BuildingData> buildings = townService.cache().getBuildings(townUuid);
        if (buildings == null) return 1;
        int level = buildings.stream()
                .filter(b -> "warehouse_inv".equals(b.buildingId()))
                .mapToInt(BuildingData::level)
                .findFirst().orElse(0);
        return 1 + Math.max(0, Math.min(3, level));
    }

    private @NotNull ResourceCheck buildCheck(@NotNull WonderConfig wc,
                                              long townBalance,
                                              @NotNull Map<Material, Integer> haveItems) {
        ResourceCheck.Builder b = ResourceCheck.builder().coins(wc.costCoins(), townBalance);
        for (var e : wc.costItems().entrySet()) {
            b.item(e.getKey(), e.getValue(), haveItems.getOrDefault(e.getKey(), 0));
        }
        return b.build();
    }

    private void broadcastBuild(@NotNull com.palmergames.bukkit.towny.object.Town town,
                                @NotNull WonderConfig wc) {
        var msg = plugin.getMessagesConfig().get("wonders.upgrade.broadcast",
                Map.of("town", town.getName(), "wonder", wc.displayName()));
        for (Player m : plugin.towny().getOnlineMembers(town)) m.sendMessage(msg);
        plugin.getLogger().info("[Wonders] " + town.getName() + " построил " + wc.id());
    }

    // ============================================================
    //   ★ Админ-операции (без оплаты, с проверкой слотов)
    // ============================================================

    public void adminGive(@NotNull UUID townUuid,
                          @NotNull String wonderId,
                          @Nullable UUID actorUuid,
                          @NotNull Consumer<WonderBuildResult> callback) {

        WonderConfig wc = registry.byId(wonderId).orElse(null);
        if (wc == null) { callback.accept(WonderBuildResult.error()); return; }

        UUID owner = townByWonder.get(wonderId);
        if (owner != null) {
            if (owner.equals(townUuid)) callback.accept(WonderBuildResult.alreadyOwned());
            else callback.accept(WonderBuildResult.alreadyTaken(townNameByWonder.get(wonderId)));
            return;
        }

        int used  = getUsedSlots(townUuid);
        int limit = getSlotLimit(townUuid);
        if (used >= limit) {
            callback.accept(WonderBuildResult.noSlots(used, limit));
            return;
        }

        if (!inFlight.add(townUuid)) { callback.accept(WonderBuildResult.busy()); return; }

        AtomicBoolean called = new AtomicBoolean(false);
        Consumer<WonderBuildResult> wrapped = r -> {
            if (called.compareAndSet(false, true)) {
                inFlight.remove(townUuid);
                callback.accept(r);
            }
        };

        var town = plugin.towny().getTownByUuid(townUuid);
        if (town == null) { wrapped.accept(WonderBuildResult.error()); return; }

        dao.tryInsert(townUuid, wonderId)
                .orTimeout(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((inserted, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ex != null) { wrapped.accept(WonderBuildResult.timeout()); return; }
                    if (!Boolean.TRUE.equals(inserted)) {
                        wrapped.accept(WonderBuildResult.alreadyTaken(
                                townNameByWonder.get(wonderId)));
                        return;
                    }

                    builtByTown.computeIfAbsent(townUuid, k -> new ArrayList<>()).add(wc);
                    townByWonder.put(wonderId, townUuid);
                    townNameByWonder.put(wonderId, town.getName());

                    plugin.effectService().invalidate(townUuid);
                    plugin.bonusService().invalidate(townUuid);

                    // ★ townywar-награды чудес.
                    try {
                        plugin.townyWarService().onWonderBuilt(town, wc);
                    } catch (Throwable t) {
                        plugin.getLogger().warning("[Wonder] TownyWar sync (adminGive) failed: "
                                + t.getMessage());
                    }

                    logService.logAdminWonderGive(townUuid, actorUuid, wonderId);

                    wrapped.accept(WonderBuildResult.success(wc));
                }));
    }

    public @NotNull CompletableFuture<Boolean> adminRemove(
            @NotNull String wonderId, @Nullable UUID actorUuid) {

        UUID townUuid = townByWonder.get(wonderId);
        if (townUuid == null) return CompletableFuture.completedFuture(false);

        return dao.deleteById(wonderId).thenCompose(v -> {
            CompletableFuture<Boolean> done = new CompletableFuture<>();
            // Кэши трогаем на main-thread — они читаются и из GUI, и из BonusService.
            Bukkit.getScheduler().runTask(plugin, () -> {
                List<WonderConfig> list = builtByTown.get(townUuid);
                if (list != null) {
                    list.removeIf(w -> w.id().equals(wonderId));
                    if (list.isEmpty()) builtByTown.remove(townUuid);
                }
                townByWonder.remove(wonderId);
                townNameByWonder.remove(wonderId);

                Map<String, Double> progress = timedProgress.get(townUuid);
                if (progress != null) {
                    progress.keySet().removeIf(k -> k.startsWith(wonderId + "|"));
                    if (progress.isEmpty()) timedProgress.remove(townUuid);
                }

                plugin.effectService().invalidate(townUuid);
                plugin.bonusService().invalidate(townUuid);

                // ★ Снимаем townywar-бонусы, если они были применены.
                var town = plugin.towny().getTownByUuid(townUuid);
                if (town != null) {
                    plugin.wonderRegistry().byId(wonderId).ifPresent(wc -> {
                        try {
                            plugin.townyWarService().onWonderRemoved(town, wc);
                        } catch (Throwable t) {
                            plugin.getLogger().warning("[Wonder] TownyWar sync (adminRemove) failed: "
                                    + t.getMessage());
                        }
                    });
                }

                logService.logAdminWonderRemove(townUuid, actorUuid, wonderId);
                done.complete(true);
            });
            return done;
        });
    }

    public @NotNull CompletableFuture<Integer> adminSetSlotsBonus(
            @NotNull UUID townUuid, int bonus, @Nullable UUID actorUuid) {

        return dao.setSlotsBonus(townUuid, bonus).thenApply(v -> {
            if (bonus == 0) adminSlotsBonus.remove(townUuid);
            else            adminSlotsBonus.put(townUuid, bonus);

            logService.logAdminWonderSlots(townUuid, actorUuid, bonus);

            return getSlotLimit(townUuid);
        });
    }
}