package ru.example.townbuildings.service;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.database.dao.OnlineTimeDao;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.HashSet;
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

    private static final long TICK_INTERVAL_TICKS = 20L * 60L;
    private static final long SECONDS_PER_TICK = 60L;
    private static final long HOUR_SECONDS = 3600L;

    private final TownBuildingsPlugin plugin;
    private final OnlineTimeDao dao;
    private final OnlineBatchWriter batchWriter;

    /** P21 — handle повторяющейся минуты. */
    private ScheduledTask tickTask;

    public OnlineTimeService(@NotNull TownBuildingsPlugin plugin,
                             @NotNull OnlineTimeDao dao,
                             @NotNull OnlineBatchWriter batchWriter) {
        this.plugin = plugin;
        this.dao = dao;
        this.batchWriter = batchWriter;
    }

    public void start() {
        // P21 — сохраняем handle: без отмены в onDisable задача продолжала
        // выполняться после /reload и выключенного плагина.
        tickTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> tick(),
                TICK_INTERVAL_TICKS, TICK_INTERVAL_TICKS);
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

            batchWriter.addPlayer(playerUuid, townUuid, SECONDS_PER_TICK);
        }

        for (UUID townUuid : townsWithOnline) {
            batchWriter.addTown(townUuid, SECONDS_PER_TICK);
        }

        if (!playerKeys.isEmpty()) {
            dao.getPlayerSecondsBatch(playerKeys).thenAccept(map -> {
                // AUDIT-P1 — map может быть HashMap из io-потока: копируем в
                // потокобезопасную структуру до передачи в main-колбэк.
                final Map<OnlineTimeDao.PlayerTownKey, Long> safeMap = new java.util.concurrent.ConcurrentHashMap<>(map);
                Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                    for (OnlineTimeDao.PlayerTownKey k : playerKeys) {
                        Long sec = safeMap.get(k);
                        if (sec == null || sec < HOUR_SECONDS) continue;
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
                        if (sec == null || sec < HOUR_SECONDS) continue;
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