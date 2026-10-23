package ru.example.cloudcore.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

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
                        Math.max(2, cfg.getInt("database.pool.maximum-pool-size", 10)),
                        Math.max(0, cfg.getInt("database.pool.minimum-idle", 2)),
                        Math.max(2, cfg.getInt("database.pool.io-threads", 4)),
                        Math.max(1000L, cfg.getLong("database.pool.connection-timeout-ms", 5000L)),
                        Math.max(10_000L, cfg.getLong("database.pool.idle-timeout-ms", 600_000L)),
                        Math.max(30_000L, cfg.getLong("database.pool.max-lifetime-ms", 1_800_000L))
                ),
                cfg.getString("database.pool.properties.useSSL", "false"),
                cfg.getString("database.pool.properties.characterEncoding", "utf8"),
                cfg.getString("database.pool.properties.serverTimezone", "UTC"),
                cfg.getBoolean("database.pool.properties.rewriteBatchedStatements", true)
        );

        long cacheTtl = Math.max(0L, cfg.getLong("cache.town-ttl-seconds", 600L));
        if (cacheTtl == 0L) {
            plugin.getLogger().warning("[Config] cache.town-ttl-seconds = 0 — "
                    + "TTL-проверка при чтении отключена; eviction всё равно "
                    + "сработает раз в 24 ч по lastAccess.");
        }
        this.cache = new CacheSettings(cacheTtl,
                cfg.getBoolean("cache.warmup-on-start", true));

        this.online = new OnlineSettings(
                Math.max(1, cfg.getInt("online-tracking.tick-interval-seconds", 60)),
                Math.max(60L, cfg.getLong("online-tracking.reward-threshold-seconds", 3600L)),
                Math.max(1L, cfg.getLong("online-tracking.batch-flush-seconds", 30L)),
                Math.max(1, cfg.getInt("online-tracking.max-hours-per-batch", 24))
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
                cfg.getBoolean("features.university-perms", true),
                cfg.getBoolean("features.wonders", true)
        );

        this.sessions = new SessionSettings(
                Math.max(1L, cfg.getLong("sessions.save-debounce-ticks", 40L)),
                Math.max(10L, cfg.getLong("sessions.session-ttl-seconds", 300L))
        );

        this.tracking = new TrackingSettings(
                Math.max(1L, cfg.getLong("tracking.block-place-ttl-minutes", 60L))
        );

        this.effects = new EffectSettings(
                Math.max(1L, cfg.getLong("effects.permanent-refresh-seconds", 3L)),
                Math.max(2, cfg.getInt("effects.permanent-duration-seconds", 12))
        );

        this.trainingCamp = new TrainingCampSettings(
                Math.max(1, cfg.getInt("training-camp.fuse-ticks", 80))
        );

        if (debug) {
            plugin.getLogger().info("[Config] Загружено. DB=" + database.host() + ":" + database.port()
                    + "/" + database.name() + ", cache-ttl=" + cache.townTtlSeconds() + "s");
        }
    }

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
            boolean universityPerms, boolean wonders
    ) {}

    public record SessionSettings(long saveDebounceTicks, long sessionTtlSeconds) {}
    public record TrackingSettings(long blockPlaceTtlMinutes) {}
    public record EffectSettings(long permanentRefreshSeconds, int permanentDurationSeconds) {}
    public record TrainingCampSettings(int fuseTicks) {}
}