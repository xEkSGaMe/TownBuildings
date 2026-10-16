package ru.example.townbuildings.service;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.ArrayList;

/**
 * Буфер секунд онлайна. Раз в N секунд сбрасывает всё одной транзакцией.
 *
 * 11-D7 — интервал flush читается из config.yml (online-tracking.batch-flush-seconds).
 * 3.2   — flushSync больше не блокирует main бесконечно: окно 2 сек,
 *         шаг 50 мс, финальный flush с .get(2, SECONDS).
 * 4.4   — reloadSchedule(seconds) перепланирует задачу из /tadmin reload.
 * P10   — атомарный drain вместо forEach + clear(): не теряем параллельные
 *         addPlayer/addTown, пришедшие между snapshot и clear.
 * M-1   — flushTowns: INSERT ... ON DUPLICATE KEY вместо голого UPDATE.
 *         Гарантирует строку tb_towns, даже если getOrCreate не успел
 *         выполниться до первого flush. Иначе секунды городского онлайна
 *         молча терялись бы на 0-rows UPDATE. town_name оставляем пустым —
 *         его перезапишет следующий getOrCreate / TownSyncTask;
 *         findByName("") никто не зовёт.
 */
public final class OnlineBatchWriter {

    /** 3.2 — общее окно ожидания при shutdown. */
    private static final long FLUSH_SYNC_WINDOW_MS = 2_000L;

    /** 3.2 — шаг ожидания между проверками flushing. */
    private static final long FLUSH_SYNC_SLEEP_MS = 50L;

    private final TownBuildingsPlugin plugin;
    private final DatabaseManager db;

    /** 11-D7 — интервал из config.yml (для start()). */
    private final long flushIntervalTicks;

    /** 4.4 — handle для перепланирования. */
    private ScheduledTask flushTask;

    private final Map<String, long[]> playerDelta = new ConcurrentHashMap<>();
    private final Map<String, Long> townDelta = new ConcurrentHashMap<>();
    private final AtomicBoolean flushing = new AtomicBoolean(false);
    private volatile boolean shuttingDown = false;

    public OnlineBatchWriter(@NotNull TownBuildingsPlugin plugin, @NotNull DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
        this.flushIntervalTicks =
                20L * plugin.getConfigManager().online().batchFlushSeconds();
    }

    public void start() {
        if (flushTask != null) return; // P21 — защита от двойного планирования
        flushTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                task -> flushAsync(), flushIntervalTicks, flushIntervalTicks);
    }

    public void stop() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
    }

    public void reloadSchedule(long flushSeconds) {
        if (flushTask != null) flushTask.cancel();
        long ticks = Math.max(20L, 20L * flushSeconds);
        flushTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                task -> flushAsync(), ticks, ticks);
        plugin.getLogger().info("[OnlineBatch] Интервал flush пересчитан: "
                + flushSeconds + " сек");
    }

    public void addPlayer(@NotNull UUID playerUuid, @NotNull UUID townUuid, long seconds) {
        String key = playerUuid + "|" + townUuid;
        playerDelta.compute(key, (k, v) -> {
            if (v == null) return new long[]{seconds};
            v[0] += seconds;
            return v;
        });
    }

    public void addTown(@NotNull UUID townUuid, long seconds) {
        townDelta.merge(townUuid.toString(), seconds, Long::sum);
    }

    public void flushAsync() {
        if (shuttingDown) return;
        if (!flushing.compareAndSet(false, true)) return;

        Map<String, long[]> playerSnapshot = new HashMap<>();
        for (String k : new ArrayList<>(playerDelta.keySet())) {
            long[] drained = playerDelta.remove(k);
            if (drained != null) playerSnapshot.put(k, drained.clone());
        }

        Map<String, Long> townSnapshot = new HashMap<>();
        for (String k : new ArrayList<>(townDelta.keySet())) {
            Long drained = townDelta.remove(k);
            if (drained != null) townSnapshot.put(k, drained);
        }

        if (playerSnapshot.isEmpty() && townSnapshot.isEmpty()) {
            flushing.set(false);
            return;
        }

        db.runAsync(conn -> {
            try {
                conn.setAutoCommit(false);
                flushPlayers(conn, playerSnapshot);
                flushTowns(conn, townSnapshot);
                conn.commit();
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                plugin.getLogger().warning("[OnlineBatch] Ошибка flush: " + e.getMessage());
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        }).whenComplete((v, ex) -> flushing.set(false));
    }

    public void flushSync() {
        shuttingDown = true;

        long deadline = System.currentTimeMillis() + FLUSH_SYNC_WINDOW_MS;
        while (flushing.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(FLUSH_SYNC_SLEEP_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        Map<String, long[]> playerSnapshot = new HashMap<>();
        for (String k : new ArrayList<>(playerDelta.keySet())) {
            long[] drained = playerDelta.remove(k);
            if (drained != null) playerSnapshot.put(k, drained.clone());
        }

        Map<String, Long> townSnapshot = new HashMap<>();
        for (String k : new ArrayList<>(townDelta.keySet())) {
            Long drained = townDelta.remove(k);
            if (drained != null) townSnapshot.put(k, drained);
        }

        if (playerSnapshot.isEmpty() && townSnapshot.isEmpty()) return;

        final java.util.concurrent.atomic.AtomicBoolean writeOk =
                new java.util.concurrent.atomic.AtomicBoolean(true);

        try {
            db.runAsync(conn -> {
                try {
                    conn.setAutoCommit(false);
                    flushPlayers(conn, playerSnapshot);
                    flushTowns(conn, townSnapshot);
                    conn.commit();
                } catch (SQLException e) {
                    writeOk.set(false);
                    try { conn.rollback(); } catch (SQLException ignored) {}
                    plugin.getLogger().warning("[OnlineBatch] Ошибка финального flush: "
                            + e.getMessage());
                } finally {
                    try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
                }
            }).get(2, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            writeOk.set(false);
            plugin.getLogger().warning("[OnlineBatch] flushSync timeout — "
                    + "секунды возвращены в буфер для повторной записи");
        } catch (Throwable t) {
            writeOk.set(false);
            plugin.getLogger().warning("[OnlineBatch] flushSync error: " + t.getMessage());
        }

        if (!writeOk.get()) {
            for (var e : playerSnapshot.entrySet()) {
                playerDelta.merge(e.getKey(), e.getValue(),
                        (oldV, newV) -> { oldV[0] += newV[0]; return oldV; });
            }
            for (var e : townSnapshot.entrySet()) {
                townDelta.merge(e.getKey(), e.getValue(), Long::sum);
            }
        }
    }

    private void flushPlayers(@NotNull Connection conn, @NotNull Map<String, long[]> snapshot)
            throws SQLException {
        if (snapshot.isEmpty()) return;
        String sql = "INSERT INTO tb_player_online (player_uuid, town_uuid, online_seconds) " +
                "VALUES (?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE online_seconds = online_seconds + VALUES(online_seconds)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (var e : snapshot.entrySet()) {
                String[] parts = e.getKey().split("\\|", 2);
                ps.setString(1, parts[0]);
                ps.setString(2, parts[1]);
                ps.setLong(3, e.getValue()[0]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * M-1 — INSERT ... ON DUPLICATE KEY вместо голого UPDATE.
     * Гарантирует строку tb_towns, даже если getOrCreate (C-1) ещё не
     * отработал. town_name оставляем пустым — его перезапишет следующий
     * getOrCreate / TownSyncTask; findByName("") никто не зовёт.
     */
    private void flushTowns(@NotNull Connection conn, @NotNull Map<String, Long> snapshot)
            throws SQLException {
        if (snapshot.isEmpty()) return;
        String sql = "INSERT INTO tb_towns (town_uuid, town_name, city_online_seconds) " +
                "VALUES (?, '', ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "city_online_seconds = city_online_seconds + VALUES(city_online_seconds)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (var e : snapshot.entrySet()) {
                ps.setString(1, e.getKey());
                ps.setLong(2, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}