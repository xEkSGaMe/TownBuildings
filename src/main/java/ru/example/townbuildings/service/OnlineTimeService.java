package ru.example.townbuildings.service;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.database.dao.OnlineTimeDao;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Учёт онлайна игрока и города.
 * 11-D3 — batch-проверки наград: 2 SELECT/мин вместо 200+.
 * 5.3   — удалено мёртвое поле activeSessions (никогда не читалось).
 */
public final class OnlineTimeService {

    private static final long TICK_INTERVAL_TICKS = 20L * 60L;
    private static final long SECONDS_PER_TICK = 60L;
    private static final long HOUR_SECONDS = 3600L;

    private final TownBuildingsPlugin plugin;
    private final OnlineTimeDao dao;
    private final OnlineBatchWriter batchWriter;

    public OnlineTimeService(@NotNull TownBuildingsPlugin plugin,
                             @NotNull OnlineTimeDao dao,
                             @NotNull OnlineBatchWriter batchWriter) {
        this.plugin = plugin;
        this.dao = dao;
        this.batchWriter = batchWriter;
    }

    public void start() {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> tick(),
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
        // no-op: нечего очищать.
    }

    private void tick() {
        Set<UUID> townsWithOnline = ConcurrentHashMap.newKeySet();
        Set<OnlineTimeDao.PlayerTownKey> playerKeys = new HashSet<>();

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
                for (OnlineTimeDao.PlayerTownKey k : playerKeys) {
                    Long sec = map.get(k);
                    if (sec == null || sec < HOUR_SECONDS) continue;
                    plugin.rewardService().checkAndGivePlayerRewards(
                            k.playerUuid(), k.townUuid());
                }
            });
        }

        if (!townsWithOnline.isEmpty()) {
            plugin.townDao().getCitySecondsBatch(townsWithOnline).thenAccept(map -> {
                for (UUID townUuid : townsWithOnline) {
                    Long sec = map.get(townUuid);
                    if (sec == null || sec < HOUR_SECONDS) continue;
                    plugin.rewardService().checkAndGiveCityRewards(townUuid);
                }
            });
        }
    }
}