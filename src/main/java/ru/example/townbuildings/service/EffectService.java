package ru.example.townbuildings.service;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.config.BuildingDefinition;
import ru.example.townbuildings.config.BuildingLevel;
import ru.example.townbuildings.config.EffectSpec;
import ru.example.townbuildings.database.model.BuildingData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Выдаёт эффекты горожанам по уровню зданий.
 *
 * C5 — тогл features.effects: при выключенной фиче start() не запускает
 *      задачи, reloadSettings() останавливает существующие, reapplyFor()
 *      становится no-op.
 */
public final class EffectService {

    private static final long TOWN_CACHE_TTL_MS = 3_000L;

    private final TownBuildingsPlugin plugin;

    private volatile long permanentRefreshTicks;
    private volatile int  permanentDurationTicks;

    private final long periodicCheckTicks;

    private ScheduledTask permanentTask;
    private ScheduledTask periodicTask;

    private final Map<UUID, TownEffects> cache = new ConcurrentHashMap<>();
    private final Map<UUID, TownEffects> staleCache = new ConcurrentHashMap<>();

    private final Map<UUID, CachedTown> residentTownCache = new ConcurrentHashMap<>();

    public EffectService(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;

        var ec = plugin.getConfigManager().effects();
        this.permanentRefreshTicks  = 20L * ec.permanentRefreshSeconds();
        this.permanentDurationTicks = 20 * ec.permanentDurationSeconds();
        this.periodicCheckTicks     = 20L;
    }

    public void start() {
        // ★ C5 — не запускаем задачи, если фича выключена.
        if (!plugin.getConfigManager().features().effects()) {
            plugin.getLogger().info("[Effects] Фича выключена — задачи не запускаются.");
            return;
        }
        warnIfUnsafeConfig();
        permanentTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                task -> refreshPermanents(),
                permanentRefreshTicks, permanentRefreshTicks);

        periodicTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                task -> refreshPeriodics(),
                periodicCheckTicks, periodicCheckTicks);
    }

    public void reloadSettings() {
        // ★ C5 — сначала отменяем всё, что было запущено.
        if (permanentTask != null) permanentTask.cancel();
        if (periodicTask != null) periodicTask.cancel();
        permanentTask = null;
        periodicTask = null;

        // Фича выключена — не перезапускаем.
        if (!plugin.getConfigManager().features().effects()) {
            cache.clear();
            staleCache.clear();
            residentTownCache.clear();
            plugin.getLogger().info("[Effects] Фича выключена — задачи остановлены.");
            return;
        }

        var ec = plugin.getConfigManager().effects();
        this.permanentRefreshTicks  = 20L * ec.permanentRefreshSeconds();
        this.permanentDurationTicks = 20 * ec.permanentDurationSeconds();

        permanentTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                task -> refreshPermanents(),
                permanentRefreshTicks, permanentRefreshTicks);

        periodicTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                task -> refreshPeriodics(),
                periodicCheckTicks, periodicCheckTicks);

        plugin.getLogger().info("[Effects] Пересчитано: refresh="
                + ec.permanentRefreshSeconds() + "s, duration="
                + ec.permanentDurationSeconds() + "s");
        warnIfUnsafeConfig();
    }

    private void warnIfUnsafeConfig() {
        if (permanentDurationTicks <= permanentRefreshTicks * 2) {
            plugin.getLogger().warning("[Effects] permanent-duration-seconds ("
                    + (permanentDurationTicks / 20) + ") должен быть > 2 × "
                    + "permanent-refresh-seconds (" + (permanentRefreshTicks / 20)
                    + "). Иначе PERMANENT-эффекты будут мерцать на HUD.");
        }
    }

    public void onDisable() {
        if (permanentTask != null) permanentTask.cancel();
        if (periodicTask != null) periodicTask.cancel();
        cache.clear();
        staleCache.clear();
        residentTownCache.clear();
    }

    public void invalidate(@NotNull UUID townUuid) {
        cache.remove(townUuid);
    }

    public void invalidateAll() {
        cache.clear();
        staleCache.clear();
    }

    // ============================================================
    //   P16-ECHO — resident → town через TTL-кэш
    // ============================================================

    private @Nullable UUID resolveTownUuid(@NotNull Player player) {
        UUID playerUuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        CachedTown cached = residentTownCache.get(playerUuid);
        if (cached != null && now < cached.expiresAt()) {
            return cached.townUuid();
        }

        Resident resident = TownyAPI.getInstance().getResident(playerUuid);
        UUID townUuid = null;
        if (resident != null && resident.hasTown()) {
            Town town = resident.getTownOrNull();
            if (town != null) townUuid = town.getUUID();
        }
        residentTownCache.put(playerUuid, new CachedTown(townUuid, now + TOWN_CACHE_TTL_MS));
        return townUuid;
    }

    public void cleanup() {
        long now = System.currentTimeMillis();
        residentTownCache.entrySet().removeIf(e -> now >= e.getValue().expiresAt());
    }

    private record CachedTown(@Nullable UUID townUuid, long expiresAt) {}

    // ============================================================
    //   Permanent
    // ============================================================

    private void refreshPermanents() {
        long minRemaining = permanentDurationTicks / 2L;

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID townUuid = resolveTownUuid(player);
            if (townUuid == null) continue;

            List<EffectSpec> specs = getTownEffects(townUuid).permanents;
            if (specs.isEmpty()) continue;

            for (EffectSpec spec : specs) {
                PotionEffect current = player.getPotionEffect(spec.type());

                if (current != null
                        && current.getAmplifier() >= spec.amplifier()
                        && current.getDuration() > minRemaining) {
                    continue;
                }

                player.addPotionEffect(new PotionEffect(
                        spec.type(), permanentDurationTicks, spec.amplifier(),
                        true, false, false), false);
            }
        }
    }

    public void reapplyFor(@NotNull Player player, @NotNull UUID townUuid) {
        // ★ C5 — не перевыдаём, если фича выключена.
        if (!plugin.getConfigManager().features().effects()) return;
        if (!player.isOnline()) return;

        List<EffectSpec> specs = getTownEffects(townUuid).permanents;
        if (specs.isEmpty()) return;

        long minRemaining = permanentDurationTicks / 2L;

        for (EffectSpec spec : specs) {
            PotionEffect current = player.getPotionEffect(spec.type());
            if (current != null
                    && current.getAmplifier() >= spec.amplifier()
                    && current.getDuration() > minRemaining) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(
                    spec.type(), permanentDurationTicks, spec.amplifier(),
                    true, false, false), false);
        }
    }

    // ============================================================
    //   Periodic
    // ============================================================

    private void refreshPeriodics() {
        long nowMs = System.currentTimeMillis();

        Map<UUID, List<Player>> playersByTown = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID townUuid = resolveTownUuid(player);
            if (townUuid == null) continue;
            playersByTown.computeIfAbsent(townUuid, k -> new ArrayList<>(4)).add(player);
        }

        for (Map.Entry<UUID, List<Player>> entry : playersByTown.entrySet()) {
            UUID townUuid = entry.getKey();
            List<PeriodicEntry> entries = getTownEffects(townUuid).periodics;
            if (entries.isEmpty()) continue;

            for (PeriodicEntry pe : entries) {
                long last = pe.lastApplyMs.get();
                long intervalMs = pe.spec.intervalSeconds() * 1000L;
                if (nowMs - last < intervalMs) continue;

                if (pe.lastApplyMs.compareAndSet(last, nowMs)) {
                    int ticks = 20 * pe.spec.durationSeconds();
                    for (Player p : entry.getValue()) {
                        p.addPotionEffect(new PotionEffect(
                                pe.spec.type(), ticks, pe.spec.amplifier(),
                                true, false, false), false);
                    }
                }
            }
        }
    }

    // ============================================================
    //   Кэш по городу
    // ============================================================

    private @NotNull TownEffects getTownEffects(@NotNull UUID townUuid) {
        TownEffects cached = cache.get(townUuid);
        if (cached != null) return cached;

        List<BuildingData> buildings = plugin.townService().cache().getBuildings(townUuid);
        if (buildings != null) {
            TownEffects fresh = buildEffects(buildings);
            cache.put(townUuid, fresh);
            staleCache.put(townUuid, fresh);
            return fresh;
        }

        TownEffects stale = staleCache.get(townUuid);
        if (stale != null) {
            plugin.townService().getBuildings(townUuid).thenAccept(list -> {
                TownEffects fresh = buildEffects(list);
                cache.put(townUuid, fresh);
                staleCache.put(townUuid, fresh);
            });
            return stale;
        }

        plugin.townService().getBuildings(townUuid).thenAccept(list -> {
            TownEffects fresh = buildEffects(list);
            cache.put(townUuid, fresh);
            staleCache.put(townUuid, fresh);
        });
        return TownEffects.EMPTY;
    }

    private @NotNull TownEffects buildEffects(@NotNull List<BuildingData> buildings) {
        Map<PotionEffectType, EffectSpec> permanentsByType = new LinkedHashMap<>();
        Map<String, PeriodicEntry> periodicsByKey = new LinkedHashMap<>();

        for (BuildingData bd : buildings) {
            BuildingDefinition def = plugin.buildingRegistry().byId(bd.buildingId()).orElse(null);
            if (def == null) continue;

            int maxLvl = Math.min(bd.level(), def.maxLevel());
            for (int lvl = 1; lvl <= maxLvl; lvl++) {
                BuildingLevel level = def.level(lvl).orElse(null);
                if (level == null || level.effects().isEmpty()) continue;

                for (EffectSpec spec : level.effects()) {
                    if (spec.mode() == EffectSpec.Mode.PERMANENT) {
                        permanentsByType.merge(
                                spec.type(),
                                spec,
                                (a, b) -> a.amplifier() >= b.amplifier() ? a : b);
                    } else {
                        String key = spec.type().getKey() + ":"
                                + spec.amplifier() + ":"
                                + spec.intervalSeconds() + ":"
                                + spec.durationSeconds();
                        periodicsByKey.putIfAbsent(key, new PeriodicEntry(spec));
                    }
                }
            }
        }

        return new TownEffects(
                List.copyOf(permanentsByType.values()),
                List.copyOf(periodicsByKey.values()));
    }

    // ============================================================
    //   Внутренние типы
    // ============================================================

    private static final class TownEffects {
        static final TownEffects EMPTY = new TownEffects(List.of(), List.of());
        final List<EffectSpec> permanents;
        final List<PeriodicEntry> periodics;
        TownEffects(List<EffectSpec> p, List<PeriodicEntry> pe) {
            this.permanents = p;
            this.periodics = pe;
        }
    }

    private static final class PeriodicEntry {
        final EffectSpec spec;
        final AtomicLong lastApplyMs;
        PeriodicEntry(EffectSpec spec) {
            this.spec = spec;
            this.lastApplyMs = new AtomicLong(System.currentTimeMillis());
        }
    }
}