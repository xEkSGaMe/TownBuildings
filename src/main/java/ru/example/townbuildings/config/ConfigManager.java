package ru.example.townbuildings.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Обёртка над config.yml.
 *
 * L-6 — warnIfNegative: -1 в yml давало townTtlMs = 0 (кэш без TTL),
 *       -5 — тоже 0 после *1000. Явно warning'им и clamp'им к 0.
 */
public final class ConfigManager {

    private final JavaPlugin plugin;

    private boolean debug;
    private String language;

    private DatabaseSettings database;
    private CacheSettings cache;
    private OnlineSettings online;
    private FeatureToggles features;
    private SessionSettings sessions;
    private TrackingSettings tracking;
    private EffectSettings effects;
    private TrainingCampSettings trainingCamp;

    public ConfigManager(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        this.plugin.reloadConfig();
        FileConfiguration cfg = this.plugin.getConfig();

        // ★ L-6 — защита от отрицательных значений TTL/размеров.
        warnIfNegative(cfg, "cache.town-ttl-seconds");
        warnIfNegative(cfg, "sessions.save-debounce-ticks");
        warnIfNegative(cfg, "sessions.session-ttl-seconds");
        warnIfNegative(cfg, "tracking.block-place-ttl-minutes");
        warnIfNegative(cfg, "effects.permanent-refresh-seconds");
        warnIfNegative(cfg, "effects.permanent-duration-seconds");
        warnIfNegative(cfg, "online-tracking.tick-interval-seconds");
        warnIfNegative(cfg, "online-tracking.reward-threshold-seconds");
        warnIfNegative(cfg, "online-tracking.batch-flush-seconds");
        warnIfNegative(cfg, "online-tracking.max-hours-per-batch");
        warnIfNegative(cfg, "database.pool.maximum-pool-size");
        warnIfNegative(cfg, "database.pool.minimum-idle");
        warnIfNegative(cfg, "database.pool.io-threads");
        warnIfNegative(cfg, "training-camp.fuse-ticks");

        this.debug = cfg.getBoolean("debug", false);
        this.language = cfg.getString("language", "ru");

        this.database = new DatabaseSettings(
                cfg.getString("database.host", "127.0.0.1"),
                cfg.getInt("database.port", 3306),
                cfg.getString("database.name", "townbuildings"),
                cfg.getString("database.user", "root"),
                cfg.getString("database.password", ""),
                new DatabaseSettings.PoolSettings(
                        cfg.getInt("database.pool.maximum-pool-size", 10),
                        cfg.getInt("database.pool.minimum-idle", 2),
                        cfg.getInt("database.pool.io-threads", 4),
                        cfg.getLong("database.pool.connection-timeout-ms", 5000L),
                        cfg.getLong("database.pool.idle-timeout-ms", 600_000L),
                        cfg.getLong("database.pool.max-lifetime-ms", 1_800_000L)
                ),
                cfg.getString("database.pool.properties.useSSL", "false"),
                cfg.getString("database.pool.properties.characterEncoding", "utf8"),
                cfg.getString("database.pool.properties.serverTimezone", "UTC"),
                cfg.getBoolean("database.pool.properties.rewriteBatchedStatements", true)
        );

        this.cache = new CacheSettings(
                cfg.getLong("cache.town-ttl-seconds", 600L),
                cfg.getBoolean("cache.warmup-on-start", true)
        );

        this.online = new OnlineSettings(
                cfg.getInt("online-tracking.tick-interval-seconds", 60),
                cfg.getLong("online-tracking.reward-threshold-seconds", 3600L),
                cfg.getLong("online-tracking.batch-flush-seconds", 30L),
                cfg.getInt("online-tracking.max-hours-per-batch", 24)
        );

        this.features = new FeatureToggles(
                cfg.getBoolean("features.buildings", true),
                cfg.getBoolean("features.inventory", true),
                cfg.getBoolean("features.stock", true),
                cfg.getBoolean("features.bonuses", true),
                cfg.getBoolean("features.effects", true),
                cfg.getBoolean("features.admin-commands", true),
                cfg.getBoolean("features.library-exp", true),
                cfg.getBoolean("features.training-camp-tnt", true),
                cfg.getBoolean("features.tax-office", true),
                cfg.getBoolean("features.topographic-institute", true),
                cfg.getBoolean("features.headquarters-stacking", true),
                cfg.getBoolean("features.university-perms", true)
        );

        this.sessions = new SessionSettings(
                cfg.getLong("sessions.save-debounce-ticks", 40L),
                cfg.getLong("sessions.session-ttl-seconds", 300L)
        );

        this.tracking = new TrackingSettings(
                cfg.getLong("tracking.block-place-ttl-minutes", 60L)
        );

        this.effects = new EffectSettings(
                cfg.getLong("effects.permanent-refresh-seconds", 3L),
                cfg.getInt("effects.permanent-duration-seconds", 12)
        );

        this.trainingCamp = new TrainingCampSettings(
                cfg.getInt("training-camp.fuse-ticks", 80)
        );

        if (debug) {
            plugin.getLogger().info("[Config] Загружено. DB=" + database.host() + ":" + database.port()
                    + "/" + database.name() + ", cache-ttl=" + cache.townTtlSeconds() + "s");
        }
    }

    /** ★ L-6 — clamp отрицательных значений к 0 с warning'ом в лог. */
    private void warnIfNegative(@NotNull FileConfiguration cfg, @NotNull String key) {
        long v = cfg.getLong(key, 0L);
        if (v < 0) {
            plugin.getLogger().warning("[Config] " + key + " = " + v
                    + " (отрицательное) — использую 0. Проверьте config.yml.");
            cfg.set(key, 0);
        }
    }

    // -------- Геттеры --------

    public boolean isDebug()          { return debug; }
    public @NotNull String language() { return Objects.requireNonNullElse(language, "ru"); }
    public @NotNull DatabaseSettings database() { return Objects.requireNonNull(database); }
    public @NotNull CacheSettings cache()       { return Objects.requireNonNull(cache); }
    public @NotNull OnlineSettings online()     { return Objects.requireNonNull(online); }
    public @NotNull FeatureToggles features()   { return Objects.requireNonNull(features); }
    public @NotNull SessionSettings sessions()  { return Objects.requireNonNull(sessions); }
    public @NotNull TrackingSettings tracking() { return Objects.requireNonNull(tracking); }
    public @NotNull EffectSettings effects()    { return Objects.requireNonNull(effects); }
    public @NotNull TrainingCampSettings trainingCamp() { return Objects.requireNonNull(trainingCamp); }

    // -------- Вложенные настройки --------

    public record DatabaseSettings(
            String host, int port, String name, String user, String password,
            PoolSettings pool,
            String useSSL, String characterEncoding, String serverTimezone,
            boolean rewriteBatchedStatements
    ) {
        public @NotNull String jdbcUrl() {
            return "jdbc:mysql://" + host + ":" + port + "/" + name
                    + "?useSSL=" + useSSL
                    + "&characterEncoding=" + characterEncoding
                    + "&serverTimezone=" + serverTimezone
                    + "&rewriteBatchedStatements=" + rewriteBatchedStatements;
        }

        public record PoolSettings(
                int maximumPoolSize, int minimumIdle, int ioThreads,
                long connectionTimeoutMs, long idleTimeoutMs, long maxLifetimeMs
        ) {}
    }

    public record CacheSettings(long townTtlSeconds, boolean warmupOnStart) {}

    public record OnlineSettings(
            int tickIntervalSeconds, long rewardThresholdSeconds,
            long batchFlushSeconds, int maxHoursPerBatch
    ) {}

    public record FeatureToggles(
            boolean buildings, boolean inventory, boolean stock,
            boolean bonuses, boolean effects, boolean adminCommands,
            boolean libraryExp, boolean trainingCampTnt, boolean taxOffice,
            boolean topographicInstitute, boolean headquartersStacking,
            boolean universityPerms
    ) {}

    public record SessionSettings(long saveDebounceTicks, long sessionTtlSeconds) {}
    public record TrackingSettings(long blockPlaceTtlMinutes) {}
    public record EffectSettings(long permanentRefreshSeconds, int permanentDurationSeconds) {}
    public record TrainingCampSettings(int fuseTicks) {}
}