package ru.example.cloudcore.database;

import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.CloudCorePlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class MigrationRunner {

    /** Текущая версия схемы. v6 = institutes-data. */
    public static final int SCHEMA_VERSION = 6;

    private static final long MIGRATE_TIMEOUT_SECONDS = 30L;

    private final CloudCorePlugin plugin;
    private final DatabaseManager db;

    public MigrationRunner(@NotNull CloudCorePlugin plugin, @NotNull DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void migrate() {
        try {
            db.supplyAsync(conn -> {
                try {
                    int recorded = readVersion(conn);
                    int actual   = detectSchemaVersion(conn);

                    plugin.getLogger().info("[DB] Версия в tb_schema_version=" + recorded
                            + ", фактическая=" + actual + ", целевая=" + SCHEMA_VERSION);

                    if (actual >= SCHEMA_VERSION) {
                        if (recorded != actual) {
                            writeVersion(conn, actual);
                            plugin.getLogger().info("[DB] tb_schema_version синхронизирован с фактической ("
                                    + actual + ").");
                        } else {
                            plugin.getLogger().info("[DB] Схема актуальна (v" + actual + ").");
                        }
                        return null;
                    }

                    if (actual == 1) {
                        plugin.getLogger().warning("[DB] Обнаружена схема v1. Переименовываю старые таблицы.");
                        applyV1ToV2(conn);
                        actual = 0;
                    }

                    plugin.getLogger().info("[DB] Миграция: " + actual + " → " + SCHEMA_VERSION);
                    applySchema(conn);
                    applyV3(conn);
                    applyV4(conn);
                    applyV5(conn);
                    applyV6(conn);
                    writeVersion(conn, SCHEMA_VERSION);
                    plugin.getLogger().info("[DB] Миграция завершена (v" + SCHEMA_VERSION + ").");
                    return null;
                } catch (SQLException e) {
                    throw new RuntimeException("Миграция не удалась", e);
                }
            }).get(MIGRATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            throw new RuntimeException("Миграция БД не завершилась за "
                    + MIGRATE_TIMEOUT_SECONDS + " секунд. "
                    + "Проверьте соединение с MySQL и перезапустите сервер.", te);
        } catch (Exception e) {
            throw new RuntimeException("Миграция БД упала: " + e.getMessage(), e);
        }
    }

    private int readVersion(@NotNull Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS tb_schema_version (
                    id TINYINT PRIMARY KEY,
                    version INT NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            var rs = st.executeQuery("SELECT version FROM tb_schema_version WHERE id = 1");
            if (rs.next()) return rs.getInt(1);
            return 0;
        }
    }

    private void writeVersion(@NotNull Connection conn, int version) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("REPLACE INTO tb_schema_version (id, version) VALUES (1, " + version + ")");
        }
    }

    /**
     * v6 — добавлены institutes-таблицы.
     * v5 — tb_town_wonder_slots.
     * v4 — tb_wonders.
     * v3 — mayor_uuid + FK.
     * v2 — общая схема.
     */
    private int detectSchemaVersion(@NotNull Connection conn) throws SQLException {
        if (!tableExists(conn, "tb_towns")) return 0;
        if (columnExists(conn, "tb_towns", "town_id"))   return 1;
        if (!columnExists(conn, "tb_towns", "town_uuid")) return 0;
        if (columnExists(conn, "tb_towns", "mayor_uuid")) {
            if (!tableExists(conn, "tb_wonders")) return 3;
            if (!tableExists(conn, "tb_town_wonder_slots")) return 4;
            if (!tableExists(conn, "tb_institutes")) return 5;
            return 6;
        }
        return 2;
    }

    private void applySchema(@NotNull Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS tb_towns (
                    town_uuid CHAR(36) PRIMARY KEY,
                    town_name VARCHAR(255) NOT NULL,
                    city_online_seconds BIGINT NOT NULL DEFAULT 0,
                    mayor_uuid CHAR(36) NULL,
                    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        ON UPDATE CURRENT_TIMESTAMP,
                    INDEX idx_town_name (town_name)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS tb_buildings (
                    town_uuid CHAR(36) NOT NULL,
                    building_id VARCHAR(64) NOT NULL,
                    level INT NOT NULL DEFAULT 0,
                    PRIMARY KEY (town_uuid, building_id),
                    CONSTRAINT fk_buildings_town
                        FOREIGN KEY (town_uuid) REFERENCES tb_towns(town_uuid)
                        ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS tb_player_online (
                    player_uuid CHAR(36) NOT NULL,
                    town_uuid CHAR(36) NOT NULL,
                    online_seconds BIGINT NOT NULL DEFAULT 0,
                    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (player_uuid, town_uuid),
                    INDEX idx_online_town (town_uuid)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS tb_inventories (
                    town_uuid CHAR(36) NOT NULL,
                    page INT NOT NULL,
                    slot INT NOT NULL,
                    item_data BLOB,
                    PRIMARY KEY (town_uuid, page, slot),
                    INDEX idx_inv_town_page (town_uuid, page)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS tb_stocks (
                    town_uuid CHAR(36) NOT NULL,
                    page INT NOT NULL,
                    slot INT NOT NULL,
                    item_data BLOB,
                    PRIMARY KEY (town_uuid, page, slot),
                    INDEX idx_stock_town_page (town_uuid, page)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS tb_logs (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    town_uuid CHAR(36),
                    player_uuid CHAR(36),
                    action VARCHAR(64) NOT NULL,
                    details JSON,
                    INDEX idx_logs_town (town_uuid),
                    INDEX idx_logs_action (action),
                    INDEX idx_logs_ts (ts)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        }
    }

    private void applyV1ToV2(@NotNull Connection conn) throws SQLException {
        String suffix = "_v1_backup_" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());

        String[] tables = {
                "tb_towns", "tb_buildings", "tb_player_online",
                "tb_inventories", "tb_stocks", "tb_logs",
                "tb_schema_version"
        };

        for (String t : tables) {
            if (!tableExists(conn, t)) continue;
            try (Statement st = conn.createStatement()) {
                st.execute("RENAME TABLE " + t + " TO " + t + suffix);
                plugin.getLogger().info("[DB] v1→v2: " + t + " → " + t + suffix);
            }
        }

        plugin.getLogger().warning("[DB] ============================================================");
        plugin.getLogger().warning("[DB] Обнаружена схема v1. Старые таблицы переименованы в *_v1_backup_*.");
        plugin.getLogger().warning("[DB] Плагин начнёт с чистой схемы v" + SCHEMA_VERSION + ".");
        plugin.getLogger().warning("[DB] ============================================================");
    }

    private void applyV3(@NotNull Connection conn) throws SQLException {
        if (!columnExists(conn, "tb_towns", "mayor_uuid")) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE tb_towns ADD COLUMN mayor_uuid CHAR(36) NULL AFTER city_online_seconds");
                plugin.getLogger().info("[DB] v3: tb_towns.mayor_uuid добавлена.");
            }
        }
        cleanupOrphans(conn, "tb_inventories");
        cleanupOrphans(conn, "tb_stocks");
        cleanupOrphans(conn, "tb_player_online");
        addFkIfMissing(conn, "tb_inventories", "fk_inv_town", "town_uuid", "tb_towns", "town_uuid");
        addFkIfMissing(conn, "tb_stocks", "fk_stock_town", "town_uuid", "tb_towns", "town_uuid");
        addFkIfMissing(conn, "tb_player_online", "fk_online_town", "town_uuid", "tb_towns", "town_uuid");
    }

    private void applyV4(@NotNull Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS tb_wonders (
                    wonder_id VARCHAR(64) PRIMARY KEY,
                    town_uuid CHAR(36) NOT NULL,
                    built_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_wonders_town (town_uuid),
                    CONSTRAINT fk_wonders_town
                        FOREIGN KEY (town_uuid) REFERENCES tb_towns(town_uuid)
                        ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        }
        plugin.getLogger().info("[DB] v4: tb_wonders создана (если не существовала).");
    }

    private void applyV5(@NotNull Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS tb_town_wonder_slots (
                    town_uuid CHAR(36) PRIMARY KEY,
                    slots_bonus INT NOT NULL DEFAULT 0,
                    CONSTRAINT fk_wonder_slots_town
                        FOREIGN KEY (town_uuid) REFERENCES tb_towns(town_uuid)
                        ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        }
        plugin.getLogger().info("[DB] v5: tb_town_wonder_slots создана (если не существовала).");
    }

    /**
     * v6 — institutes (religions/forms/ideologies) + playtime.
     *
     * tb_institutes:
     *   religion/government_form/ideology    — nullable-строки (enum keys)
     *   applied_chunk_bonus                  — сколько чанков реально начислено плагином
     *   chunk_bonus_initialized              — прошла ли миграция (после первого reconcile)
     *
     * FK на tb_towns — при удалении города institutes-данные стираются каскадно.
     * Сохраняем institutes через getOrCreate в TownService перед записью (в коде).
     *
     * tb_institutes_playtime — секунды онлайна игрока для institutes-экономики.
     * PK player_uuid — одна запись на игрока. При flush — перезапись значения.
     */
    private void applyV6(@NotNull Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS tb_institutes (
                    town_uuid CHAR(36) PRIMARY KEY,
                    religion VARCHAR(32) NULL,
                    government_form VARCHAR(32) NULL,
                    ideology VARCHAR(32) NULL,
                    applied_chunk_bonus INT NOT NULL DEFAULT 0,
                    chunk_bonus_initialized TINYINT(1) NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    CONSTRAINT fk_institutes_town
                        FOREIGN KEY (town_uuid) REFERENCES tb_towns(town_uuid)
                        ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS tb_institutes_playtime (
                    player_uuid CHAR(36) PRIMARY KEY,
                    seconds BIGINT NOT NULL DEFAULT 0,
                    player_name VARCHAR(64) NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        }
        plugin.getLogger().info("[DB] v6: tb_institutes + tb_institutes_playtime созданы (если не существовали).");
    }

    // ======================== helpers ========================

    private boolean tableExists(@NotNull Connection conn, @NotNull String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.TABLES " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private boolean columnExists(@NotNull Connection conn, @NotNull String table,
                                 @NotNull String column) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private boolean fkExists(@NotNull Connection conn, @NotNull String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.TABLE_CONSTRAINTS " +
                        "WHERE CONSTRAINT_SCHEMA = DATABASE() " +
                        "AND CONSTRAINT_NAME = ? AND CONSTRAINT_TYPE = 'FOREIGN KEY'")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private void addFkIfMissing(@NotNull Connection conn,
                                @NotNull String table, @NotNull String fkName,
                                @NotNull String column, @NotNull String refTable,
                                @NotNull String refColumn) throws SQLException {
        if (fkExists(conn, fkName)) return;
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE " + table
                    + " ADD CONSTRAINT " + fkName
                    + " FOREIGN KEY (" + column + ") REFERENCES " + refTable + "(" + refColumn + ")"
                    + " ON DELETE CASCADE");
            plugin.getLogger().info("[DB] FK " + fkName + " добавлен на " + table + ".");
        }
    }

    private void cleanupOrphans(@NotNull Connection conn, @NotNull String table) throws SQLException {
        String sql = "DELETE t FROM " + table + " t " +
                "LEFT JOIN tb_towns tt ON t.town_uuid = tt.town_uuid " +
                "WHERE t.town_uuid IS NOT NULL AND tt.town_uuid IS NULL";
        try (Statement st = conn.createStatement()) {
            int removed = st.executeUpdate(sql);
            if (removed > 0) {
                plugin.getLogger().info("[DB] " + removed
                        + " осиротевших строк удалено из " + table + ".");
            }
        }
    }
}