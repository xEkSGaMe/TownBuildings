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
 * PERMANENT — перевыдаётся каждые N сек длительностью M сек (ambient).
 * PERIODIC  — срабатывает раз в interval сек на duration сек, с паузами.
 *
 * Все параметры — из buildings.yml (levels.<n>.effects).
 *
 * 2.4 — staleCache: последнее успешно рассчитанное TownEffects. При
 *       invalidate() (апгрейд здания) getTownEffects больше не отдаёт EMPTY,
 *       пока идёт async-загрузка из БД — эффекты не пропадают.
 * 4.1 — refreshPeriodics: O(towns) вместо O(players). Один проход по
 *       онлайну строит Map<town, List<Player>>, далее итерируем по городам.
 * 4.4 — reloadSettings(): пересчёт refresh/duration из config.yml
 *       без рестарта; задачи перепланируются.
 */
public final class EffectService {

    private final TownBuildingsPlugin plugin;

    /** 4.4 — поля изменяемы на лету (reload). */
    private volatile long permanentRefreshTicks;
    private volatile int  permanentDurationTicks;

    /** periodicCheckTicks не из конфига — фиксированный. */
    private final long periodicCheckTicks;

    /** 4.4 — handles для перепланирования. */
    private ScheduledTask permanentTask;
    private ScheduledTask periodicTask;

    private final Map<UUID, TownEffects> cache = new ConcurrentHashMap<>();
    private final Map<UUID, TownEffects> staleCache = new ConcurrentHashMap<>();   // 2.4

    public EffectService(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;

        var ec = plugin.getConfigManager().effects();
        this.permanentRefreshTicks  = 20L * ec.permanentRefreshSeconds();
        this.permanentDurationTicks = 20 * ec.permanentDurationSeconds();
        this.periodicCheckTicks     = 20L; // раз в 1 сек проверяем периодические
    }

    public void start() {
        permanentTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                task -> refreshPermanents(),
                permanentRefreshTicks, permanentRefreshTicks);

        periodicTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                task -> refreshPeriodics(),
                periodicCheckTicks, periodicCheckTicks);
    }

    /**
     * 4.4 — пересчитать refresh/duration и перепланировать permanent-задачу
     * из нового config.yml. Periodic-задача не зависит от config.yml
     * (фиксированный 20-тик), её не трогаем.
     */
    public void reloadSettings() {
        var ec = plugin.getConfigManager().effects();
        this.permanentRefreshTicks  = 20L * ec.permanentRefreshSeconds();
        this.permanentDurationTicks = 20 * ec.permanentDurationSeconds();

        if (permanentTask != null) permanentTask.cancel();
        permanentTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                task -> refreshPermanents(),
                permanentRefreshTicks, permanentRefreshTicks);

        plugin.getLogger().info("[Effects] Пересчитано: refresh="
                + ec.permanentRefreshSeconds() + "s, duration="
                + ec.permanentDurationSeconds() + "s");
    }

    public void onDisable() {
        if (permanentTask != null) permanentTask.cancel();
        if (periodicTask != null) periodicTask.cancel();
        cache.clear();
        staleCache.clear();
    }

    public void invalidate(@NotNull UUID townUuid) {
        cache.remove(townUuid);
        // staleCache НЕ трогаем — fallback (2.4).
    }

    public void invalidateAll() {
        cache.clear();
        staleCache.clear();
    }

    // ============================================================
    //   Permanent
    // ============================================================

    /**
     * Периодический refresh PERMANENT-эффектов.
     *
     * ВАЖНО: не применяем эффект, если он уже висит и его остаток
     * больше половины от полной длительности. Иначе HUD-таймер эффекта
     * сбрасывался бы каждые permanentRefreshTicks секунд — игрок
     * воспринимал бы это как «эффект постоянно сбрасывается».
     */
    private void refreshPermanents() {
        long minRemaining = permanentDurationTicks / 2L;

        for (Player player : Bukkit.getOnlinePlayers()) {
            Resident resident = TownyAPI.getInstance().getResident(player.getUniqueId());
            if (resident == null || !resident.hasTown()) continue;
            Town town = resident.getTownOrNull();
            if (town == null) continue;

            List<EffectSpec> specs = getTownEffects(town.getUUID()).permanents;
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

    /**
     * Немедленно перевыдать все PERMANENT-эффекты конкретному игроку.
     * Вызывается из EffectReapplyListener после телепорта/меню/команды.
     */
    public void reapplyFor(@NotNull Player player, @NotNull UUID townUuid) {
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

    /**
     * 4.1 — O(towns) вместо O(players): один проход по онлайну строит
     *       Map<townUuid, List<Player>>, дальше итерируем по городам.
     *       AtomicLong.CAS вызывается один раз на город (а не на игрока),
     *       что критично для городов с десятками резидентов.
     */
    private void refreshPeriodics() {
        long nowMs = System.currentTimeMillis();

        // 4.1 — сбор игроков по городам (один проход).
        Map<UUID, List<Player>> playersByTown = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Resident resident = TownyAPI.getInstance().getResident(player.getUniqueId());
            if (resident == null || !resident.hasTown()) continue;
            Town town = resident.getTownOrNull();
            if (town == null) continue;
            playersByTown.computeIfAbsent(town.getUUID(), k -> new ArrayList<>(4)).add(player);
        }

        // 4.1 — итерируем по городам, а не по игрокам.
        for (Map.Entry<UUID, List<Player>> entry : playersByTown.entrySet()) {
            UUID townUuid = entry.getKey();
            List<PeriodicEntry> entries = getTownEffects(townUuid).periodics;
            if (entries.isEmpty()) continue;

            for (PeriodicEntry pe : entries) {
                long last = pe.lastApplyMs.get();
                long intervalMs = pe.spec.intervalSeconds() * 1000L;
                if (nowMs - last < intervalMs) continue;

                // 4.1 — один CAS на город (а не на игрока).
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

    /**
     * 2.4 — порядок поиска:
     *   1) cache;
     *   2) sync-расчёт из TownCache.buildings (без БД);
     *   3) staleCache + async-догруз;
     *   4) холодный старт: async-догруз + EMPTY.
     */
    private @NotNull TownEffects getTownEffects(@NotNull UUID townUuid) {
        // 1. Актуальный кэш.
        TownEffects cached = cache.get(townUuid);
        if (cached != null) return cached;

        // 2. 2.4 — синхронный расчёт из TownCache.buildings.
        List<BuildingData> buildings = plugin.townService().cache().getBuildings(townUuid);
        if (buildings != null) {
            TownEffects fresh = buildEffects(buildings);
            cache.put(townUuid, fresh);
            staleCache.put(townUuid, fresh);
            return fresh;
        }

        // 3. 2.4 — fallback на последнее известное.
        TownEffects stale = staleCache.get(townUuid);
        if (stale != null) {
            plugin.townService().getBuildings(townUuid).thenAccept(list -> {
                TownEffects fresh = buildEffects(list);
                cache.put(townUuid, fresh);
                staleCache.put(townUuid, fresh);
            });
            return stale;
        }

        // 4. Холодный старт.
        plugin.townService().getBuildings(townUuid).thenAccept(list -> {
            TownEffects fresh = buildEffects(list);
            cache.put(townUuid, fresh);
            staleCache.put(townUuid, fresh);
        });
        return TownEffects.EMPTY;
    }

    private @NotNull TownEffects buildEffects(@NotNull List<BuildingData> buildings) {
        // PERMANENT: дедуп по типу, оставляем максимальный amplifier.
        Map<PotionEffectType, EffectSpec> permanentsByType = new LinkedHashMap<>();

        // PERIODIC: дедуп по полному ключу (type, amp, interval, duration).
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