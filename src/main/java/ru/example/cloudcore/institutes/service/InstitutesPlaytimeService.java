package ru.example.cloudcore.institutes.service;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.CloudCorePlugin;
import ru.example.cloudcore.institutes.database.InstitutesPlaytimeDao;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory write-back cache секунд онлайна для institutes-экономики.
 *
 * Секунды обновляются на main-thread синхронно (быстро, без race).
 * Периодический flush (async) перезаписывает значения в БД
 * через INSERT ... ON DUPLICATE KEY UPDATE seconds = VALUES(seconds).
 * На disable — flushSync.
 *
 * Corrupted state (DB down) не блокирует игру: секунды копятся в памяти,
 * flush повторяется при следующем тике.
 */
public final class InstitutesPlaytimeService {

    private static final long FLUSH_SYNC_TIMEOUT_SECONDS = 3L;

    private final CloudCorePlugin plugin;
    private final InstitutesPlaytimeDao dao;

    private final Map<UUID, Long> seconds = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    private ScheduledTask flushTask;

    public InstitutesPlaytimeService(@NotNull CloudCorePlugin plugin,
                                     @NotNull InstitutesPlaytimeDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    /**
     * Загружает все записи из БД в память.
     */
    public @NotNull java.util.concurrent.CompletableFuture<Void> warmup() {
        return dao.findAll().thenAccept(map -> {
            seconds.clear();
            seconds.putAll(map);
            plugin.getLogger().info("[Institutes/Playtime] Загружено: " + map.size() + " игроков.");
        });
    }

    public void start() {
        if (flushTask != null) return;
        long ticks = 20L * 60L; // раз в минуту
        flushTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                t -> flushAsync(), ticks, ticks);
    }

    public void stop() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
    }

    public long getSeconds(@NotNull UUID playerUuid) {
        return seconds.getOrDefault(playerUuid, 0L);
    }

    public long addSeconds(@NotNull UUID playerUuid, long delta, @NotNull String playerName) {
        if (delta <= 0) return getSeconds(playerUuid);
        long updated = seconds.merge(playerUuid, delta, Long::sum);
        names.put(playerUuid, playerName);
        dirty.set(true);
        return updated;
    }

    public void setSeconds(@NotNull UUID playerUuid, long value) {
        if (value <= 0) seconds.remove(playerUuid);
        else            seconds.put(playerUuid, value);
        dirty.set(true);
    }

    public void remove(@NotNull UUID playerUuid) {
        seconds.remove(playerUuid);
        names.remove(playerUuid);
        dirty.set(true);
    }

    /** Периодический flush (async). */
    private void flushAsync() {
        if (!dirty.get()) return;
        if (!flushing.compareAndSet(false, true)) return;

        Map<UUID, Long> snapshot = new HashMap<>(seconds);
        Map<UUID, String> nameSnapshot = new HashMap<>(names);
        dirty.set(false);

        dao.flush(snapshot, nameSnapshot).whenComplete((v, ex) -> {
            flushing.set(false);
            if (ex != null) {
                plugin.getLogger().warning("[Institutes/Playtime] flush error: " + ex.getMessage());
                dirty.set(true); // повторим на следующем тике
            }
        });
    }

    /**
     * Публичный триггер async-flush. Идемпотентен: coalesces через
     * флаг flushing. Используется на PlayerQuitEvent, чтобы уменьшить
     * окно потери данных между тиками.
     */
    public void requestFlush() {
        flushAsync();
    }

    /** Синхронный flush на disable. Ждёт до 3 сек. */
    public void flushSync() {
        if (!dirty.get() && !flushing.get()) return;
        Map<UUID, Long> snapshot = new HashMap<>(seconds);
        Map<UUID, String> nameSnapshot = new HashMap<>(names);
        try {
            dao.flush(snapshot, nameSnapshot).get(FLUSH_SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            dirty.set(false);
        } catch (Throwable t) {
            plugin.getLogger().severe("[Institutes/Playtime] flushSync failed: " + t.getMessage());
        }
    }

    /** Сброс кэша (используется при reload — данные подтянутся warmup'ом заново). */
    public void clearCache() {
        seconds.clear();
        names.clear();
        dirty.set(false);
    }
}