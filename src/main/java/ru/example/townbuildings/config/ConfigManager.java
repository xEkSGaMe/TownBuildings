package ru.example.townbuildings.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Обёртка над config.yml.
 * 11-D6 — SessionSettings, TrackingSettings, EffectSettings, PoolSettings.ioThreads.
 * 12.5  — EffectSettings: permanent-refresh + permanent-duration.
 * 13.4  — FeatureToggles: +libraryExp.
 * 14.3  — +TrainingCampSettings, +trainingCampTnt.
 * 15.4  — FeatureToggles: +taxOffice.
 * 16.3  — FeatureToggles: +topographicInstitute.
 * 17.3  — FeatureToggles: +headquartersStacking.
 * 18.6  — FeatureToggles: +universityPerms.
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

        // 13.4 + 14.3 + 15.4 + 16.3 + 17.3 + 18.6
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

    /** 13.4 + 14.3 + 15.4 + 16.3 + 17.3 + 18.6. */
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