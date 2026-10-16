package ru.example.townbuildings.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.config.ConfigManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Обёртка над HikariCP.
 *
 * A4 — SET SESSION innodb_lock_wait_timeout = 15 < orTimeout(20s).
 * A5 — disconnect() сливает pendingOnStop ПОКА DataSource жив.
 * L-7 — у слива есть бюджет времени (5 сек): на 500+ задачах синхронный
 *       слив мог растянуться до десятков секунд при тормозящей БД,
 *       блокируя остановку сервера. Остаток логируем.
 */
public final class DatabaseManager {

    private static final long RETRY_BASE_DELAY_MS = 200L;
    private static final long RETRY_MAX_DELAY_MS = 5_000L;
    private static final int RETRY_WARN_THRESHOLD = 8;

    /** ★ L-7 — бюджет синхронного слива pendingOnStop при disconnect(). */
    private static final long DRAIN_BUDGET_MS = 5_000L;

    private final TownBuildingsPlugin plugin;
    private volatile ExecutorService ioExecutor;
    private HikariDataSource dataSource;
    private volatile boolean shuttingDown = false;
    private final java.util.Deque<Runnable> pendingOnStop =
            new java.util.concurrent.ConcurrentLinkedDeque<>();

    private final java.util.concurrent.ScheduledExecutorService retryScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "TownBuildings-DB-Retry");
                t.setDaemon(true);
                return t;
            });

    public DatabaseManager(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
    }

    public void connect() {
        ConfigManager.DatabaseSettings s = plugin.getConfigManager().database();
        int ioThreads = Math.max(2, s.pool().ioThreads());

        shuttingDown = false;

        this.ioExecutor = new ThreadPoolExecutor(
                ioThreads, ioThreads,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(500),
                r -> {
                    Thread t = new Thread(r, "TownBuildings-DB");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(s.jdbcUrl());
        cfg.setUsername(s.user());
        cfg.setPassword(s.password());
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");

        ConfigManager.DatabaseSettings.PoolSettings pool = s.pool();
        cfg.setMaximumPoolSize(pool.maximumPoolSize());
        cfg.setMinimumIdle(pool.minimumIdle());
        cfg.setConnectionTimeout(pool.connectionTimeoutMs());
        cfg.setIdleTimeout(pool.idleTimeoutMs());
        cfg.setMaxLifetime(pool.maxLifetimeMs());

        cfg.setPoolName("TownBuildings-Pool");
        cfg.setAutoCommit(true);
        // ★ A4 — 15 сек < orTimeout(20s) — MySQL сам убьёт запрос по row-lock.
        cfg.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout = 15");
        cfg.addDataSourceProperty("cachePrepStmts", "true");
        cfg.addDataSourceProperty("prepStmtCacheSize", "250");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        cfg.addDataSourceProperty("useServerPrepStmts", "true");

        this.dataSource = new HikariDataSource(cfg);
        plugin.getLogger().info("[DB] Пул соединений создан. maxPoolSize="
                + pool.maximumPoolSize() + ", ioThreads=" + ioThreads
                + ", maxLifetime=" + pool.maxLifetimeMs() + "ms");
    }

    public void disconnect() {
        shuttingDown = true;

        ExecutorService ex = this.ioExecutor;
        if (ex != null) {
            ex.shutdown();
            try {
                if (!ex.awaitTermination(5, TimeUnit.SECONDS)) {
                    plugin.getLogger().warning("[DB] ioExecutor не завершился за 5 сек — "
                            + "часть задач будет дописана синхронно на stop-потоке.");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        retryScheduler.shutdown();
        try {
            retryScheduler.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        // ★ A5 — сливаем отложенные задачи, ПОКА DataSource ещё жив.
        // ★ L-7 — но с бюджетом времени: остаток логируем.
        long drainDeadline = System.currentTimeMillis() + DRAIN_BUDGET_MS;
        int drained = 0, remaining = 0;
        Runnable task;
        while ((task = pendingOnStop.pollFirst()) != null) {
            if (System.currentTimeMillis() > drainDeadline) {
                remaining++;
                continue;
            }
            try {
                task.run();
                drained++;
            } catch (Throwable t) {
                plugin.getLogger().severe("[DB] Не удалось дописать отложенную задачу "
                        + "при остановке: " + t.getMessage());
            }
        }
        if (remaining > 0) {
            plugin.getLogger().warning("[DB] " + remaining
                    + " отложенных задач не успели слиться за "
                    + (DRAIN_BUDGET_MS / 1000) + " сек (записано " + drained
                    + "). Возможна потеря части данных.");
        }

        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private void submit(@NotNull Runnable jdbcTask) {
        scheduleWithRetry(jdbcTask, 0);
    }

    private void scheduleWithRetry(@NotNull Runnable jdbcTask, int attempt) {
        if (shuttingDown) {
            pendingOnStop.addLast(jdbcTask);
            return;
        }
        ExecutorService ex = this.ioExecutor;
        if (ex == null || ex.isShutdown()) {
            pendingOnStop.addLast(jdbcTask);
            return;
        }
        try {
            ex.execute(() -> {
                pendingOnStop.remove(jdbcTask);
                jdbcTask.run();
            });
        } catch (java.util.concurrent.RejectedExecutionException rej) {
            if (attempt == 0 || attempt >= RETRY_WARN_THRESHOLD) {
                plugin.getLogger().warning("[DB] Очередь БД переполнена, повтор #"
                        + attempt + " через backoff (данные НЕ потеряны).");
            }
            long delay = Math.min(RETRY_MAX_DELAY_MS,
                    RETRY_BASE_DELAY_MS * (1L << Math.min(attempt, 20)));
            pendingOnStop.addLast(jdbcTask);
            try {
                retryScheduler.schedule(() -> {
                    if (shuttingDown) return;
                    pendingOnStop.remove(jdbcTask);
                    scheduleWithRetry(jdbcTask, attempt + 1);
                }, delay, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException ree) {
                // retryScheduler уже остановлен — задача дождётся слива.
            }
        }
    }

    public <T> @NotNull CompletableFuture<T> supplyAsync(
            @NotNull Function<Connection, T> action
    ) {
        ExecutorService ex = this.ioExecutor;
        if (ex == null) throw new IllegalStateException("DatabaseManager не инициализирован.");
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable jdbcTask = () -> {
            try (Connection conn = dataSource.getConnection()) {
                future.complete(action.apply(conn));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };
        submit(jdbcTask);
        return future;
    }

    public @NotNull CompletableFuture<Void> runAsync(
            @NotNull SqlConsumer<Connection> action
    ) {
        return supplyAsync(conn -> {
            try {
                action.accept(conn);
            } catch (SQLException e) {
                throw new RuntimeException("Ошибка БД", e);
            }
            return null;
        });
    }

    @FunctionalInterface
    public interface SqlConsumer<T> {
        void accept(T t) throws SQLException;
    }

    public @NotNull HikariDataSource dataSource() {
        if (dataSource == null) {
            throw new IllegalStateException("DatabaseManager не инициализирован.");
        }
        return dataSource;
    }
}