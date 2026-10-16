package ru.example.townbuildings.database;

import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Создаёт схему БД при первом запуске и обновляет при повышении
 * SCHEMA_VERSION. Все DDL — идемпотентные (IF NOT EXISTS).
 *
 * v2: town_id INT → town_uuid CHAR(36) во всех таблицах.
 * v3: mayor_uuid в tb_towns + FK ON DELETE CASCADE на города.
 *
 * Фактическая версия определяется по колонкам (detectSchemaVersion), а не
 * по tb_schema_version — она может врать (откатили дамп, удалили строку).
 *
 * 3.2 — migrate() имеет таймаут 30 сек; при таймауте плагин не стартует
 *       (явная ошибка в консоль), сервер не висит.
 * P19 — после applyV1ToV2 переменная actual сбрасывается в 0: таблиц v1
 *       уже нет, схема фактически fresh. Раньше лог врал «Миграция: 1 → 3».
 * E8  — tb_logs НЕ чистим от orphan'ов и FK fk_logs_town не создаём:
 *       исторические записи городов, удалённых из Towny, нужны для аудита.
 *       На работающих серверах, где fk_logs_town уже создан ранее, эта
 *       правка его не снимет — нужна отдельная миграция v4.
 */
public final class MigrationRunner {

    /** Текущая версия схемы. Повышайте при каждом изменении DDL. */
    public static final int SCHEMA_VERSION = 3;

    /** 3.2 — таймаут миграции. */
    private static final long MIGRATE_TIMEOUT_SECONDS = 30L;

    private final TownBuildingsPlugin plugin;
    private final DatabaseManager db;

    public MigrationRunner(@NotNull TownBuildingsPlugin plugin, @NotNull DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
    }

    /** Синхронный запуск при старте — до регистрации команд. */
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
                        // P19 — после rename таблиц нет, схема фактически fresh.
                        actual = 0;
                    }

                    plugin.getLogger().info("[DB] Миграция: " + actual + " → " + SCHEMA_VERSION);
                    applySchema(conn);   // CREATE TABLE IF NOT EXISTS (безопасно)
                    applyV3(conn);       // идемпотентно
                    writeVersion(conn, SCHEMA_VERSION);
                    plugin.getLogger().info("[DB] Миграция завершена (v" + SCHEMA_VERSION + ").");
                    return null;
                } catch (SQLException e) {
                    throw new RuntimeException("Миграция не удалась", e);
                }
            }).get(MIGRATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);              // 3.2
        } catch (TimeoutException te) {
            throw new RuntimeException("Миграция БД не завершилась за "
                    + MIGRATE_TIMEOUT_SECONDS + " секунд. "
                    + "Проверьте соединение с MySQL и перезапустите сервер.", te);
        } catch (Exception e) {
            throw new RuntimeException("Миграция БД упала: " + e.getMessage(), e);
        }
    }

    // ============================================================
    //   Версия схемы
    // ============================================================

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
     * 7.1.2 — определяет фактическое состояние схемы по колонкам,
     * НЕ доверяя tb_schema_version.
     *
     * @return 0 — таблиц нет (fresh);
     *         1 — v1 (есть tb_towns.town_id);
     *         2 — v2 (есть town_uuid, нет mayor_uuid);
     *         3 — v3 (есть mayor_uuid).
     */
    private int detectSchemaVersion(@NotNull Connection conn) throws SQLException {
        if (!tableExists(conn, "tb_towns")) return 0;
        if (columnExists(conn, "tb_towns", "town_id"))   return 1;
        if (!columnExists(conn, "tb_towns", "town_uuid")) return 0; // структура чужая
        if (columnExists(conn, "tb_towns", "mayor_uuid")) return 3;
        return 2;
    }

    // ============================================================
    //   DDL — базовые таблицы
    // ============================================================

    private void applySchema(@NotNull Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            // tb_towns
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

            // tb_buildings
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

            // tb_player_online
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

            // tb_inventories (склад города, /t inventory)
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

            // tb_stocks (хранилище города, /t stock)
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

            // tb_logs
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

    // ============================================================
    //   V1 → V2 (rename to backup)
    // ============================================================

    /**
     * 7.1.3 — v1 → v2: старые таблицы с town_id INT переименовываются
     * в *_v1_backup_<ts>. Автоматический перелив INT → UUID невозможен:
     * старые id-шки не связаны с UUID Towny.
     *
     * Данные сохраняются на всякий случай, но плагин их больше не читает.
     */
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
        plugin.getLogger().warning("[DB] Плагин начнёт с чистой схемы v3. Данные v1 НЕ переносятся");
        plugin.getLogger().warning("[DB] (town_id INT не связан с UUID Towny).");
        plugin.getLogger().warning("[DB] Если нужно сохранить вручную — таблицы доступны под старым именем");
        plugin.getLogger().warning("[DB] с суффиксом " + suffix + ".");
        plugin.getLogger().warning("[DB] ============================================================");
    }

    // ============================================================
    //   V3 — mayor_uuid + FK ON DELETE CASCADE
    // ============================================================

    /**
     * v3: mayor_uuid + FK ON DELETE CASCADE на города.
     * Идемпотентно: проверяет наличие колонки/констрейнтов через information_schema.
     *
     * ★ E8 — tb_logs исключён из cleanup и FK: исторические записи удалённых
     *        городов нужны для аудита, а orphan-строки без FK ничего не ломают.
     */
    private void applyV3(@NotNull Connection conn) throws SQLException {
        // 1. mayor_uuid (если ещё нет)
        if (!columnExists(conn, "tb_towns", "mayor_uuid")) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE tb_towns ADD COLUMN mayor_uuid CHAR(36) NULL AFTER city_online_seconds");
                plugin.getLogger().info("[DB] v3: tb_towns.mayor_uuid добавлена.");
            }
        }

        // 2. Чистим осиротевшие строки (иначе FK не создастся)
        cleanupOrphans(conn, "tb_inventories");
        cleanupOrphans(conn, "tb_stocks");
        cleanupOrphans(conn, "tb_player_online");
        // ★ E8 — tb_logs НЕ чистим: исторические записи городов, удалённых из
        // Towny, могут понадобиться для аудита. FK на tb_logs тоже не создаём,
        // поэтому orphan-строки ничего не ломают.
        // cleanupOrphans(conn, "tb_logs");

        // 3. FK ON DELETE CASCADE
        addFkIfMissing(conn, "tb_inventories",
                "fk_inv_town", "town_uuid", "tb_towns", "town_uuid");
        addFkIfMissing(conn, "tb_stocks",
                "fk_stock_town", "town_uuid", "tb_towns", "town_uuid");
        addFkIfMissing(conn, "tb_player_online",
                "fk_online_town", "town_uuid", "tb_towns", "town_uuid");
        // ★ E8 — FK на tb_logs не создаём: удаление города не должно каскадно
        // удалять логи. Историю аудита сохраняем.
        // addFkIfMissing(conn, "tb_logs",
        //         "fk_logs_town", "town_uuid", "tb_towns", "town_uuid");
    }

    // ============================================================
    //   Helpers — information_schema
    // ============================================================

    /** 7.1.1 — проверка существования таблицы. */
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
            plugin.getLogger().info("[DB] v3: FK " + fkName + " добавлен на " + table + ".");
        }
    }

    private void cleanupOrphans(@NotNull Connection conn, @NotNull String table) throws SQLException {
        String sql = "DELETE t FROM " + table + " t " +
                "LEFT JOIN tb_towns tt ON t.town_uuid = tt.town_uuid " +
                "WHERE t.town_uuid IS NOT NULL AND tt.town_uuid IS NULL";
        try (Statement st = conn.createStatement()) {
            int removed = st.executeUpdate(sql);
            if (removed > 0) {
                plugin.getLogger().info("[DB] v3: " + removed
                        + " осиротевших строк удалено из " + table + ".");
            }
        }
    }
}