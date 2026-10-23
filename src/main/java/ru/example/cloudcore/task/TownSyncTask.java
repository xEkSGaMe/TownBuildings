package ru.example.cloudcore.task;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Town;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.database.model.TownData;
import ru.example.cloudcore.service.TownService;
import ru.example.cloudcore.CloudCorePlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ru.example.cloudcore.CloudCorePlugin;
/**
 * Раз в 5 минут: сверка имён, чистка кэшей, pool-metrics.
 *
 * P21 — handle задачи хранится и отменяется в stop() (onDisable).
 * CLEANUP — чистим TTL-кэши горячих лукапов и grace-карту сессий.
 * C-1/C-2 — backstop: для городов, которых нет в кэше, вызываем
 *           getOrCreate(...) (INSERT if missing). Заодно это заменяет
 *           опасную логику «создать TownData с onlineSeconds=0» из
 *           предыдущей версии syncNames.
 */
public final class TownSyncTask {

    private static final long INITIAL_DELAY_TICKS = 20L * 30;
    private static final long PERIOD_TICKS        = 20L * 60 * 5;

    private final CloudCorePlugin plugin;
    private final TownService townService;

    private ScheduledTask task;

    public TownSyncTask(@NotNull CloudCorePlugin plugin,
                        @NotNull TownService townService) {
        this.plugin = plugin;
        this.townService = townService;
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                t -> run(),
                INITIAL_DELAY_TICKS,
                PERIOD_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void run() {
        townService.cache().evictExpired();
        plugin.blockPlaceTracker().sweep();

        plugin.bonusListener().cleanup();
        plugin.effectService().cleanup();

        logPoolMetrics();

        List<TownData> fresh = new ArrayList<>();
        for (Town town : TownyAPI.getInstance().getTowns()) {
            UUID townUuid = town.getUUID();
            TownData cached = townService.getCachedTown(townUuid);

            if (cached == null) {
                // C-1 backstop — гарантируем строку tb_towns для «холодных»
                // городов. Покрывает окна: сервер стартовал и CacheWarmup
                // не успел прогреть; город создан во время прогрева; add-
                // resident событие было пропущено.
                // C-2 — здесь же и подтянутся реальные cityOnlineSeconds:
                // без этого следующий syncNames записал бы 0.
                townService.getOrCreate(townUuid, town.getName())
                        .exceptionally(ex -> {
                            plugin.getLogger().warning("[TownSync] getOrCreate("
                                    + town.getName() + "): " + ex.getMessage());
                            return null;
                        });
                continue;
            }

            fresh.add(new TownData(townUuid, town.getName(), cached.cityOnlineSeconds()));
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