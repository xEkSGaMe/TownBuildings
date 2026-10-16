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
 * A4 — SET SESSION innodb_lock_wait_timeout = 15 < orTimeout(20s):
 *   MySQL сам убьёт запрос по row-lock раньше, чем сработает Java-таймаут,
 *   и мы корректно откатим ресурсы (иначе — «бесплатный апгрейд»).
 * A5 — disconnect() сливает pendingOnStop ПОКА DataSource жив; добавлен
 *   guard shuttingDown в scheduleWithRetry и retry-задаче, чтобы при
 *   остановке не плодить новые цепочки.
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
    /** AUDIT-P1 / A5 — true после disconnect(): отложенные таски сливаются из очереди. */
    private volatile boolean shuttingDown = false;
    /** AUDIT-P1 — задачи, ожидающие освобождения очереди. */
    private final java.util.Deque<Runnable> pendingOnStop =
            new java.util.concurrent.ConcurrentLinkedDeque<>();

    /** AUDIT-P1 — однонаправленный scheduler для backoff-повторов (daemon). */
    private final java.util.concurrent.ScheduledExecutorService retryScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "TownBuildings-DB-Retry");
                t.setDaemon(true);
                return t;
            });

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
        // ★ A4 — не даём row-lock держать нас дольше, чем наш orTimeout(20s).
        //   Без этого MySQL по умолчанию ждёт 50 сек, а BuildingService откатывал
        //   апгрейд через 20 — и UPDATE завершался позже, создавая «бесплатный
        //   апгрейд». При 15 сек MySQL сам убьёт запрос 1205 (lock wait timeout),
        //   наша цепочка получит нормальную ошибку и корректно откатит ресурсы.
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
        // ★ A5 — помечаем остановку ДО любых операций: scheduleWithRetry
        // перестанет плодить новые попытки, отложенные задачи стекутся в
        // pendingOnStop для финального слива (п.3).
        shuttingDown = true;

        // 1. Останавливаем io-исполнитель, ждём завершения уже запущенных задач.
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

        // 2. Останавливаем retry-scheduler: shutdown() не отменяет уже
        //    поставленные задачи, но блокирует новые schedule(...). Ждём
        //    коротко, чтобы уже бегущие попытки успели увидеть shuttingDown
        //    и не плодили новые цепочки.
        retryScheduler.shutdown();
        try {
            retryScheduler.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        // 3. ★ A5 — сливаем отложенные задачи, ПОКА DataSource ещё жив.
        //    Раньше ds.close() шёл ДО этого блока, и все задачи падали с
        //    SQLException на закрытом пуле — данные терялись молча.
        //    Теперь задачи могут успешно доехать.
        Runnable task;
        while ((task = pendingOnStop.pollFirst()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                plugin.getLogger().severe("[DB] Не удалось дописать отложенную задачу "
                        + "при остановке: " + t.getMessage());
            }
        }

        // 4. Только теперь закрываем пул — последним.
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
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
        // ★ A5 — при остановке не пытаемся планировать: задача дождётся
        // слива из disconnect().
        if (shuttingDown) {
            pendingOnStop.addLast(jdbcTask);
            return;
        }
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
            try {
                retryScheduler.schedule(() -> {
                    // ★ A5 — при остановке не трогаем задачу: она остаётся
                    // в pendingOnStop для слива из disconnect().
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