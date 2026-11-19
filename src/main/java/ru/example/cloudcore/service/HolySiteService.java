package ru.example.cloudcore.service;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.CloudCorePlugin;
import ru.example.cloudcore.config.BuildingRewards;
import ru.example.cloudcore.config.HolySiteConfig;
import ru.example.cloudcore.config.HolySiteEffects;
import ru.example.cloudcore.config.HolySiteRegistry;
import ru.example.cloudcore.config.TimedItem;
import ru.example.cloudcore.database.dao.HolySiteDao;
import ru.example.cloudcore.institutes.data.Religion;
import ru.example.cloudcore.institutes.data.TownInstituteData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Логика Священных мест.
 *
 * Инварианты:
 *   • у города максимум одно место (UNIQUE town_uuid + in-memory проверка);
 *   • место строится только при совпадении религии города;
 *   • свиток списывается только из инвентаря игрока-инициатора и
 *     возвращается при любом отказе записи в БД.
 */
public final class HolySiteService {

    private static final long DB_TIMEOUT_SECONDS = 20L;

    private final CloudCorePlugin plugin;
    private final HolySiteRegistry registry;
    private final HolySiteDao dao;
    private final LogService logService;
    private final HolyScrollItem scrolls;

    private final Map<UUID, String> siteByTown       = new ConcurrentHashMap<>();
    private final Map<String, UUID> townBySite       = new ConcurrentHashMap<>();
    private final Map<String, String> townNameBySite = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Double>> timedProgress = new ConcurrentHashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public HolySiteService(@NotNull CloudCorePlugin plugin,
                           @NotNull HolySiteRegistry registry,
                           @NotNull HolySiteDao dao,
                           @NotNull LogService logService,
                           @NotNull HolyScrollItem scrolls) {
        this.plugin = plugin;
        this.registry = registry;
        this.dao = dao;
        this.logService = logService;
        this.scrolls = scrolls;
    }

    public void warmup() {
        dao.findAll().whenComplete((list, ex) -> {
            if (ex != null || list == null) {
                plugin.getLogger().severe("[HolySites] warmup: "
                        + (ex != null ? ex.getMessage() : "null"));
                return;
            }
            Map<UUID, String> byTown = new HashMap<>();
            Map<String, UUID> toTown = new HashMap<>();
            Map<String, String> toName = new HashMap<>();
            for (HolySiteDao.Placement p : list) {
                if (registry.byId(p.siteId()).isEmpty()) continue;
                byTown.put(p.townUuid(), p.siteId());
                toTown.put(p.siteId(), p.townUuid());
                toName.put(p.siteId(), p.townName());
            }
            siteByTown.clear();       siteByTown.putAll(byTown);
            townBySite.clear();       townBySite.putAll(toTown);
            townNameBySite.clear();   townNameBySite.putAll(toName);
            plugin.getLogger().info("[HolySites] Загружено построек: " + list.size());

            // Бонусы/эффекты могли быть посчитаны до готовности кэша —
            // сбрасываем, чтобы holy-награды попали в расчёт.
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!plugin.isEnabled()) return;
                    plugin.bonusService().invalidateCaches();
                    plugin.effectService().invalidateCaches();
                });
            } catch (Throwable ignored) {}
        });
    }

    public void onTownRenamed(@NotNull UUID townUuid, @NotNull String newName) {
        String siteId = siteByTown.get(townUuid);
        if (siteId != null) townNameBySite.put(siteId, newName);
    }

    public void onTownDeleted(@NotNull UUID townUuid) {
        String siteId = siteByTown.remove(townUuid);
        timedProgress.remove(townUuid);
        if (siteId == null) return;
        townBySite.remove(siteId);
        townNameBySite.remove(siteId);
    }

    /**
     * Вызывается после смены религии города. Священное место не отбирается,
     * но при несовпадении религии деактивируется — его награды, эффекты и
     * бонусы TownyWar перестают действовать. При возврате «правильной»
     * религии место снова активируется.
     */
    public void onReligionChanged(@NotNull UUID townUuid, @Nullable Religion oldReligion) {
        HolySiteConfig site = getBuiltSite(townUuid).orElse(null);
        if (site == null) return;

        boolean wasActive = oldReligion == site.religion();
        boolean nowActive = isActive(townUuid);

        plugin.effectService().invalidate(townUuid);
        plugin.bonusService().invalidate(townUuid);

        if (wasActive == nowActive) return;
        var town = plugin.towny().getTownByUuid(townUuid);
        if (town == null) return;
        try {
            if (nowActive) {
                plugin.townyWarService().onHolySiteBuilt(town, site);
            } else {
                plugin.townyWarService().onHolySiteRemoved(town, site);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[HolySites] TownyWar sync (religion change) failed: "
                    + t.getMessage());
        }
    }

    public @NotNull Optional<HolySiteConfig> getBuiltSite(@NotNull UUID townUuid) {
        String siteId = siteByTown.get(townUuid);
        if (siteId == null) return Optional.empty();
        return registry.byId(siteId);
    }

    public @NotNull Optional<String> getBuiltBy(@NotNull String siteId) {
        return Optional.ofNullable(townNameBySite.get(siteId));
    }

    public @NotNull Optional<UUID> getBuiltTownUuid(@NotNull String siteId) {
        return Optional.ofNullable(townBySite.get(siteId));
    }

    public boolean hasSite(@NotNull UUID townUuid) {
        return siteByTown.containsKey(townUuid);
    }

    /**
     * Активно ли Священное место города. Место остаётся за городом навсегда,
     * но его награды и эффекты работают только при совпадении религии города
     * с религией места.
     */
    public boolean isActive(@NotNull UUID townUuid) {
        HolySiteConfig site = getBuiltSite(townUuid).orElse(null);
        if (site == null) return false;
        var institutes = plugin.institutesService();
        if (institutes == null) return false;
        TownInstituteData data = institutes.getCached(townUuid);
        return data != null && data.religion() == site.religion();
    }

    private @NotNull Optional<HolySiteConfig> activeSite(@NotNull UUID townUuid) {
        HolySiteConfig site = getBuiltSite(townUuid).orElse(null);
        if (site == null) return Optional.empty();
        return isActive(townUuid) ? Optional.of(site) : Optional.empty();
    }

    public @NotNull BuildingRewards getCachedRewards(@NotNull UUID townUuid) {
        return activeSite(townUuid).map(HolySiteConfig::rewards)
                .orElse(BuildingRewards.EMPTY);
    }

    public @NotNull HolySiteEffects getEffects(@NotNull UUID townUuid) {
        return activeSite(townUuid).map(HolySiteConfig::effects)
                .orElse(HolySiteEffects.EMPTY);
    }

    public @NotNull Map<Material, Integer> consumeDueTimedItems(@NotNull UUID townUuid, int hours) {
        if (hours <= 0) return Map.of();
        HolySiteConfig site = activeSite(townUuid).orElse(null);
        if (site == null || site.timedItems().isEmpty()) return Map.of();

        Map<String, Double> progress = timedProgress.computeIfAbsent(
                townUuid, k -> new ConcurrentHashMap<>());
        Map<Material, Integer> out = new HashMap<>();
        double elapsedMinutes = hours * 60.0;

        for (TimedItem ti : site.timedItems()) {
            if (ti.periodMinutes() <= 0) continue;
            String key = site.id() + "|" + ti.material().name() + "|" + ti.periodMinutes();
            double accum = progress.getOrDefault(key, 0.0) + elapsedMinutes;
            // Деление вместо цикла: при большом офлайн-накоплении цикл
            // мог бы крутиться миллионы раз.
            long cycles = (long) (accum / ti.periodMinutes());
            accum -= (double) cycles * ti.periodMinutes();
            if (cycles > 0) {
                long added = cycles * ti.amount();
                int add = (int) Math.min(Integer.MAX_VALUE, added);
                out.merge(ti.material(), add, HolySiteService::addClamped);
            }
            progress.put(key, accum);
        }
        return out;
    }

    private static int addClamped(int a, int b) {
        return (int) Math.min(Integer.MAX_VALUE, (long) a + b);
    }

    // ============================================================
    //   Постройка
    // ============================================================

    public void tryBuild(@NotNull UUID townUuid,
                         @NotNull String siteId,
                         @Nullable Player player,
                         @NotNull Consumer<HolyBuildResult> callback) {

        if (!plugin.getConfigManager().features().holySites()) {
            callback.accept(HolyBuildResult.featureDisabled());
            return;
        }

        HolySiteConfig site = registry.byId(siteId).orElse(null);
        if (site == null) { callback.accept(HolyBuildResult.error()); return; }

        if (siteByTown.containsKey(townUuid)) {
            callback.accept(HolyBuildResult.alreadyOwned());
            return;
        }
        UUID owner = townBySite.get(siteId);
        if (owner != null && !owner.equals(townUuid)) {
            callback.accept(HolyBuildResult.alreadyTaken(townNameBySite.get(siteId)));
            return;
        }
        if (player == null) { callback.accept(HolyBuildResult.error()); return; }

        var town = plugin.towny().getTownByUuid(townUuid);
        if (town == null) { callback.accept(HolyBuildResult.error()); return; }

        if (!inFlight.add(townUuid)) {
            callback.accept(HolyBuildResult.busy());
            return;
        }

        AtomicBoolean called = new AtomicBoolean(false);
        Consumer<HolyBuildResult> wrapped = r -> {
            if (called.compareAndSet(false, true)) {
                inFlight.remove(townUuid);
                callback.accept(r);
            }
        };

        var institutes = plugin.institutesService();
        if (institutes == null) { wrapped.accept(HolyBuildResult.error()); return; }

        // Религия читается асинхронно (может потребоваться запрос в БД),
        // поэтому продолжение всегда возвращаем в главный поток.
        institutes.getOrLoad(townUuid, town.getName())
                .whenComplete((data, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ex != null || data == null) {
                        wrapped.accept(HolyBuildResult.error());
                        return;
                    }
                    proceedBuild(townUuid, site, player, data, wrapped);
                }));
    }

    private void proceedBuild(@NotNull UUID townUuid,
                              @NotNull HolySiteConfig site,
                              @NotNull Player player,
                              @NotNull TownInstituteData data,
                              @NotNull Consumer<HolyBuildResult> callback) {

        // Игрок мог выйти, пока грузилась религия: не трогаем offline-инвентарь.
        if (!player.isOnline()) {
            callback.accept(HolyBuildResult.error());
            return;
        }

        // Повторная проверка после async-паузы: за это время место могло
        // занять другой город или сам город мог получить место иначе.
        if (siteByTown.containsKey(townUuid)) {
            callback.accept(HolyBuildResult.alreadyOwned());
            return;
        }
        UUID owner = townBySite.get(site.id());
        if (owner != null && !owner.equals(townUuid)) {
            callback.accept(HolyBuildResult.alreadyTaken(townNameBySite.get(site.id())));
            return;
        }

        if (data.religion() != site.religion()) {
            callback.accept(HolyBuildResult.wrongReligion());
            return;
        }

        if (!scrolls.takeOne(player, site)) {
            callback.accept(HolyBuildResult.noScroll());
            return;
        }

        var town = plugin.towny().getTownByUuid(townUuid);
        if (town == null) {
            returnScroll(player, site);
            callback.accept(HolyBuildResult.error());
            return;
        }

        dao.tryInsert(townUuid, site.id())
                .orTimeout(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((inserted, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ex != null) {
                        returnScroll(player, site);
                        callback.accept(HolyBuildResult.timeout());
                        return;
                    }
                    if (!Boolean.TRUE.equals(inserted)) {
                        returnScroll(player, site);
                        callback.accept(HolyBuildResult.alreadyTaken(
                                townNameBySite.get(site.id())));
                        return;
                    }
                    applyBuilt(town, site, player.getUniqueId(), callback);
                }));
    }

    private void applyBuilt(@NotNull com.palmergames.bukkit.towny.object.Town town,
                            @NotNull HolySiteConfig site,
                            @Nullable UUID actorUuid,
                            @NotNull Consumer<HolyBuildResult> callback) {
        UUID townUuid = town.getUUID();
        siteByTown.put(townUuid, site.id());
        townBySite.put(site.id(), townUuid);
        townNameBySite.put(site.id(), town.getName());

        plugin.effectService().invalidate(townUuid);
        plugin.bonusService().invalidate(townUuid);

        // Награды TownyWar начисляем только если место активно по религии.
        if (isActive(townUuid)) {
            try {
                plugin.townyWarService().onHolySiteBuilt(town, site);
            } catch (Throwable t) {
                plugin.getLogger().warning("[HolySites] TownyWar sync (build) failed: "
                        + t.getMessage());
            }
        }

        logService.logAdminGive(townUuid, actorUuid, "holysite:" + site.id(), 1);
        broadcastBuild(town, site);
        callback.accept(HolyBuildResult.success(site));
    }

    private void returnScroll(@NotNull Player player, @NotNull HolySiteConfig site) {
        if (!player.isOnline()) {
            plugin.getLogger().warning("[HolySites] Не удалось вернуть свиток "
                    + site.id() + ": игрок " + player.getName() + " offline.");
            return;
        }
        scrolls.give(player, site, 1);
    }

    private void broadcastBuild(@NotNull com.palmergames.bukkit.towny.object.Town town,
                                @NotNull HolySiteConfig site) {
        var msg = plugin.getMessagesConfig().get("holy-sites.upgrade.broadcast",
                Map.of("town", town.getName(), "site", site.displayName()));
        for (Player m : plugin.towny().getOnlineMembers(town)) m.sendMessage(msg);
        plugin.getLogger().info("[HolySites] " + town.getName() + " открыл " + site.id());
    }

    // ============================================================
    //   Админ-операции
    // ============================================================

    public void adminGive(@NotNull UUID townUuid,
                          @NotNull String siteId,
                          @Nullable UUID actorUuid,
                          @NotNull Consumer<HolyBuildResult> callback) {

        HolySiteConfig site = registry.byId(siteId).orElse(null);
        if (site == null) { callback.accept(HolyBuildResult.error()); return; }

        if (siteByTown.containsKey(townUuid)) {
            callback.accept(HolyBuildResult.alreadyOwned());
            return;
        }
        UUID owner = townBySite.get(siteId);
        if (owner != null && !owner.equals(townUuid)) {
            callback.accept(HolyBuildResult.alreadyTaken(townNameBySite.get(siteId)));
            return;
        }

        var town = plugin.towny().getTownByUuid(townUuid);
        if (town == null) { callback.accept(HolyBuildResult.error()); return; }

        if (!inFlight.add(townUuid)) { callback.accept(HolyBuildResult.busy()); return; }
        AtomicBoolean called = new AtomicBoolean(false);
        Consumer<HolyBuildResult> wrapped = r -> {
            if (called.compareAndSet(false, true)) {
                inFlight.remove(townUuid);
                callback.accept(r);
            }
        };

        dao.tryInsert(townUuid, siteId)
                .orTimeout(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((inserted, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ex != null) { wrapped.accept(HolyBuildResult.timeout()); return; }
                    if (!Boolean.TRUE.equals(inserted)) {
                        wrapped.accept(HolyBuildResult.alreadyTaken(
                                townNameBySite.get(siteId)));
                        return;
                    }
                    applyBuilt(town, site, actorUuid, wrapped);
                }));
    }

    public @NotNull CompletableFuture<Boolean> adminRemove(
            @NotNull String siteId, @Nullable UUID actorUuid) {

        UUID townUuid = townBySite.get(siteId);
        if (townUuid == null) return CompletableFuture.completedFuture(false);
        if (inFlight.contains(townUuid)) return CompletableFuture.completedFuture(false);

        HolySiteConfig site = registry.byId(siteId).orElse(null);

        return dao.deleteBySite(siteId).thenCompose(v -> {
            CompletableFuture<Boolean> done = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> {
                siteByTown.remove(townUuid);
                townBySite.remove(siteId);
                townNameBySite.remove(siteId);

                Map<String, Double> progress = timedProgress.get(townUuid);
                if (progress != null) {
                    progress.keySet().removeIf(k -> k.startsWith(siteId + "|"));
                    if (progress.isEmpty()) timedProgress.remove(townUuid);
                }

                plugin.effectService().invalidate(townUuid);
                plugin.bonusService().invalidate(townUuid);

                var town = plugin.towny().getTownByUuid(townUuid);
                if (town != null && site != null) {
                    try {
                        plugin.townyWarService().onHolySiteRemoved(town, site);
                    } catch (Throwable t) {
                        plugin.getLogger().warning("[HolySites] TownyWar sync (remove) failed: "
                                + t.getMessage());
                    }
                }

                logService.logAdminGive(townUuid, actorUuid, "holysite-remove:" + siteId, 0);
                done.complete(true);
            });
            return done;
        });
    }

    public @NotNull HolyScrollItem scrolls() { return scrolls; }
    public @NotNull List<HolySiteConfig> allSites() { return registry.all(); }
}
