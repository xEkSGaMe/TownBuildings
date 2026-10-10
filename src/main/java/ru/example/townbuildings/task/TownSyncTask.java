package ru.example.townbuildings.task;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Town;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.database.model.TownData;
import ru.example.townbuildings.service.TownService;

import java.util.ArrayList;
import java.util.List;

/**
 * Раз в 5 минут: сверка имён, чистка кэшей, pool-metrics.
 *
 * P21 — handle задачи хранится и отменяется в stop() (onDisable):
 *       после /reload задача больше не дёргает выключенный плагин.
 */
public final class TownSyncTask {

    private static final long INITIAL_DELAY_TICKS = 20L * 30;
    private static final long PERIOD_TICKS        = 20L * 60 * 5;

    private final TownBuildingsPlugin plugin;
    private final TownService townService;

    /** P21 — handle повторяющейся задачи. */
    private ScheduledTask task;

    public TownSyncTask(@NotNull TownBuildingsPlugin plugin,
                        @NotNull TownService townService) {
        this.plugin = plugin;
        this.townService = townService;
    }

    public void start() {
        if (task != null) return; // защита от двойного планирования
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                t -> run(),
                INITIAL_DELAY_TICKS,
                PERIOD_TICKS);
    }

    /** P21 — отмена задачи при выключении плагина. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void run() {
        townService.cache().evictExpired();
        plugin.blockPlaceTracker().sweep();
        logPoolMetrics();                                    // 11-D4

        List<TownData> fresh = new ArrayList<>();
        for (Town town : TownyAPI.getInstance().getTowns()) {
            TownData cached = townService.getCachedTown(town.getUUID());
            long onlineSeconds = cached != null ? cached.cityOnlineSeconds() : 0L;
            fresh.add(new TownData(town.getUUID(), town.getName(), onlineSeconds));
        }
        if (fresh.isEmpty()) return;

        townService.syncNames(fresh).whenComplete((v, ex) -> {
            if (ex != null) {
                plugin.getLogger().severe("[TownSync] Ошибка сверки имён: " + ex.getMessage());
            }
        });
    }

    private void logPoolMetrics() {
        try {
            var ds = plugin.databaseManager().dataSource();
            var mx = ds.getHikariPoolMXBean();
            if (mx == null) return;
            int active = mx.getActiveConnections();
            int idle = mx.getIdleConnections();
            int total = mx.getTotalConnections();
            int waiting = mx.getThreadsAwaitingConnection();
            if (waiting > 0 || active == total) {
                plugin.getLogger().warning("[DB-Pool] active=" + active + " idle=" + idle
                        + " total=" + total + " waiting=" + waiting);
            } else if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[DB-Pool] active=" + active + " idle=" + idle
                        + " total=" + total + " waiting=" + waiting);
            }
        } catch (Throwable ignored) {
            // не валим задачу
        }
    }
}