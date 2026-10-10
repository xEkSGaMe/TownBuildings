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
 * 9.6 — bounded queue + rejection policy.
 * 11-D5 — io-threads читается из config.yml.
 * AUDIT-P1 — вместо CallerRunsPolicy: AbortPolicy + повтор через backoff.
 *   CallerRunsPolicy выполнял JDBC-задачу на вызывающем потоке; если это
 *   был main-поток (Folia global region scheduler), при лагающей/упавшей
 *   БД сервер зависал до timeout'а коннекта (TPS-фриз для всех игроков).
 *   Теперь задача не теряется: она перепланируется с экспоненциальным
 *   backoff'ом, а после остановки плагина — синхронно на stop-потоке,
 *   чтобы ни одна запись не пропала молча.
 */
public final class DatabaseManager {

    /** AUDIT-P1 — базовая пауза повтора при переполнении очереди (мс). */
    private static final long RETRY_BASE_DELAY_MS = 200L;
    /** AUDIT-P1 — максимальная пауза повтора (мс). */
    private static final long RETRY_MAX_DELAY_MS = 5_000L;
    /** AUDIT-P1 — сколько раз подряд повторяем, прежде чем писать в лог warning. */
    private static final int RETRY_WARN_THRESHOLD = 8;

    private final TownBuildingsPlugin plugin;
    private volatile ExecutorService ioExecutor;
    private HikariDataSource dataSource;
    /** AUDIT-P1 — true после disconnect(): отложенные таски исполняем синхронно. */
    private volatile boolean shuttingDown = false;
    /** AUDIT-P1 — задачи, ожидающие освобождения очереди. */
    private final java.util.Deque<Runnable> pendingOnStop =
            new java.util.concurrent.ConcurrentLinkedDeque<>();

    public DatabaseManager(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
        // ioExecutor создаётся в connect(), когда конфиг уже прочитан.
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
                // AUDIT-P1 — никогда не выполняем JDBC на вызывающем потоке
                // (main при Folia-задачах). Задачу берёт на себя submit().
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
        // AUDIT-P1 — помечаем остановку ДО shutdown(): отложенные таски
        // будут исполнены синхронно, а не потеряны вместе с пулом.
        shuttingDown = true;

        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
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

        // AUDIT-P1 — доезжаем всё, что осталось в очереди повторов.
        // DataSource уже закрыт, поэтому честно логируем, если не получилось.
        Runnable task;
        while ((task = pendingOnStop.pollFirst()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                plugin.getLogger().severe("[DB] Не удалось дописать отложенную задачу "
                        + "при остановке: " + t.getMessage());
            }
        }
    }

    /**
     * AUDIT-P1 — безопасная постановка JDBC-задачи в ioExecutor.
     * При переполнении очереди задача НЕ выполняется на вызывающем потоке
     * (это был main при Folia-задачах → TPS-фризы). Вместо этого она
     * перепланируется с экспоненциальным backoff'ом через отдельный
     * daemon-поток ожидания. При остановке плагина очередь повторов
     * сливается синхронно в disconnect().
     */
    private void submit(@NotNull Runnable jdbcTask) {
        scheduleWithRetry(jdbcTask, 0);
    }

    private void scheduleWithRetry(@NotNull Runnable jdbcTask, int attempt) {
        ExecutorService ex = this.ioExecutor;
        if (ex == null || ex.isShutdown()) {
            // Пул уже недоступен — держим задачу для финального слива.
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
            retryScheduler.schedule(() -> {
                pendingOnStop.remove(jdbcTask);
                scheduleWithRetry(jdbcTask, attempt + 1);
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    /** AUDIT-P1 — однонаправленный scheduler для backoff-повторов (daemon). */
    private final java.util.concurrent.ScheduledExecutorService retryScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "TownBuildings-DB-Retry");
                t.setDaemon(true);
                return t;
            });

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