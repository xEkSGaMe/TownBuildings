package ru.example.cloudcore.service;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.database.dao.OnlineTimeDao;
import ru.example.cloudcore.CloudCorePlugin;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Учёт онлайна игрока и города.
 * 11-D3 — batch-проверки наград: 2 SELECT/мин вместо 200+.
 * 5.3   — удалено мёртвое поле activeSessions (никогда не читалось).
 * P21   — ссылка на повторяющуюся задачу хранится и отменяется в
 *         onDisable: после /reload или выключения плагина таск больше
 *         не дёргает выключенный плагин (утечка задач через reload).
 */
public final class OnlineTimeService {

    /** Минимальный период планировщика: 1 секунда (20 тиков). */
    private static final long MIN_TICK_INTERVAL_TICKS = 20L;

    private final CloudCorePlugin plugin;
    private final OnlineTimeDao dao;
    private final OnlineBatchWriter batchWriter;

    /** P21 — handle повторяющейся минуты. */
    private ScheduledTask tickTask;

    public OnlineTimeService(@NotNull CloudCorePlugin plugin,
                             @NotNull OnlineTimeDao dao,
                             @NotNull OnlineBatchWriter batchWriter) {
        this.plugin = plugin;
        this.dao = dao;
        this.batchWriter = batchWriter;
    }

    public void start() {
        if (tickTask != null) return;
        schedule();
    }

    /**
     * Перечитывает online-tracking.tick-interval-seconds и перепланирует
     * задачу (вызывается из /tadmin reload). Раньше значение из config.yml
     * молча игнорировалось до рестарта сервера.
     */
    public void reloadSchedule() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (plugin.isEnabled()) schedule();
    }

    private void schedule() {
        // P21 — сохраняем handle: без отмены в onDisable задача продолжала
        // выполняться после /reload и выключенного плагина.
        long intervalTicks = Math.max(MIN_TICK_INTERVAL_TICKS,
                20L * plugin.getConfigManager().online().tickIntervalSeconds());
        tickTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> tick(),
                intervalTicks, intervalTicks);
    }

    /**
     * 5.3 — activeSessions было мёртвым полем (записывалось, но никогда
     *       не читалось). Метод оставлен для совместимости с
     *       PlayerConnectionListener; при необходимости позже можно
     *       использовать для метрик.
     */
    public void onJoin(@NotNull Player player) {
        // no-op: учёт идёт в tick() через Bukkit.getOnlinePlayers().
    }

    public void onQuit(@NotNull Player player) {
        // no-op: см. onJoin.
    }

    public void onDisable() {
        // P21 — отменяем повторяющуюся минуту, иначе после /reload она
        // продолжит дёргать выключенный плагин.
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    private void tick() {
        // P22 — интервал и порог награды читаются из config.yml, а не из
        // захардкоженных 60 с / 3600 с. Начисляем ровно столько секунд,
        // сколько длится один tick, иначе при нестандартном интервале
        // онлайн учитывался бы неверно.
        final var online = plugin.getConfigManager().online();
        final long secondsPerTick = online.tickIntervalSeconds();
        final long rewardThresholdSeconds = online.rewardThresholdSeconds();

        // AUDIT-P1 — снимок онлайн-игроков: callback below выполняется на io-потоке.
        final Set<UUID> townsWithOnline = ConcurrentHashMap.newKeySet();
        final Set<OnlineTimeDao.PlayerTownKey> playerKeys = ConcurrentHashMap.newKeySet();

        for (Player player : Bukkit.getOnlinePlayers()) {
            var town = plugin.towny().getTownOf(player);
            if (town == null) continue;
            UUID townUuid = town.getUUID();
            UUID playerUuid = player.getUniqueId();
            townsWithOnline.add(townUuid);
            playerKeys.add(new OnlineTimeDao.PlayerTownKey(playerUuid, townUuid));

            batchWriter.addPlayer(playerUuid, townUuid, secondsPerTick);
        }

        for (UUID townUuid : townsWithOnline) {
            batchWriter.addTown(townUuid, secondsPerTick);
        }

        if (!playerKeys.isEmpty()) {
            dao.getPlayerSecondsBatch(playerKeys).thenAccept(map -> {
                // AUDIT-P1 — map может быть HashMap из io-потока: копируем в
                // потокобезопасную структуру до передачи в main-колбэк.
                final Map<OnlineTimeDao.PlayerTownKey, Long> safeMap = new java.util.concurrent.ConcurrentHashMap<>(map);
                Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                    for (OnlineTimeDao.PlayerTownKey k : playerKeys) {
                        Long sec = safeMap.get(k);
                        if (sec == null || sec < rewardThresholdSeconds) continue;
                        plugin.rewardService().checkAndGivePlayerRewards(
                                k.playerUuid(), k.townUuid());
                    }
                });
            }).exceptionally(ex -> {
                plugin.getLogger().warning("[Online] getPlayerSecondsBatch упал: " + ex.getMessage());
                return null;
            });
        }

        if (!townsWithOnline.isEmpty()) {
            plugin.townDao().getCitySecondsBatch(townsWithOnline).thenAccept(map -> {
                final Map<UUID, Long> safeMap = new java.util.concurrent.ConcurrentHashMap<>(map);
                Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                    for (UUID townUuid : townsWithOnline) {
                        Long sec = safeMap.get(townUuid);
                        if (sec == null || sec < rewardThresholdSeconds) continue;
                        plugin.rewardService().checkAndGiveCityRewards(townUuid);
                    }
                });
            }).exceptionally(ex -> {
                plugin.getLogger().warning("[Online] getCitySecondsBatch упал: " + ex.getMessage());
                return null;
            });
        }
    }
}
