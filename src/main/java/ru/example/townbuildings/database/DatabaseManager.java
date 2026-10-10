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
 * 9.6 — bounded queue + CallerRunsPolicy.
 * 11-D5 — io-threads читается из config.yml.
 */
public final class DatabaseManager {

    private final TownBuildingsPlugin plugin;
    private volatile ExecutorService ioExecutor;
    private HikariDataSource dataSource;

    public DatabaseManager(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
        // ioExecutor создаётся в connect(), когда конфиг уже прочитан.
    }

    public void connect() {
        ConfigManager.DatabaseSettings s = plugin.getConfigManager().database();
        int ioThreads = Math.max(2, s.pool().ioThreads());

        this.ioExecutor = new ThreadPoolExecutor(
                ioThreads, ioThreads,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(500),
                r -> {
                    Thread t = new Thread(r, "TownBuildings-DB");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
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
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        ExecutorService ex = this.ioExecutor;
        if (ex != null) ex.shutdown();
    }

    public <T> @NotNull CompletableFuture<T> supplyAsync(
            @NotNull Function<Connection, T> action
    ) {
        ExecutorService ex = this.ioExecutor;
        if (ex == null) throw new IllegalStateException("DatabaseManager не инициализирован.");
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                return action.apply(conn);
            } catch (SQLException e) {
                throw new RuntimeException("Ошибка БД", e);
            }
        }, ex);
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