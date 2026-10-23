package ru.example.cloudcore.database.dao;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.database.DatabaseManager;
import ru.example.cloudcore.database.model.TownData;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** CRUD для tb_towns. */
public final class TownDao {

    private final DatabaseManager db;

    public TownDao(@NotNull DatabaseManager db) {
        this.db = db;
    }

    public @NotNull CompletableFuture<Optional<TownData>> findById(@NotNull UUID townUuid) {
        return db.supplyAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT town_uuid, town_name, city_online_seconds FROM tb_towns WHERE town_uuid = ?"
            )) {
                ps.setString(1, townUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(new TownData(
                            UUID.fromString(rs.getString("town_uuid")),
                            rs.getString("town_name"),
                            rs.getLong("city_online_seconds")
                    ));
                }
            } catch (SQLException e) {
                throw new RuntimeException("findById: " + e.getMessage(), e);
            }
        });
    }

    /** Поиск по имени (для обработки DeleteTownEvent, где есть только name). */
    public @NotNull CompletableFuture<Optional<TownData>> findByName(@NotNull String name) {
        return db.supplyAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT town_uuid, town_name, city_online_seconds FROM tb_towns WHERE town_name = ?"
            )) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(new TownData(
                            UUID.fromString(rs.getString("town_uuid")),
                            rs.getString("town_name"),
                            rs.getLong("city_online_seconds")
                    ));
                }
            } catch (SQLException e) {
                throw new RuntimeException("findByName: " + e.getMessage(), e);
            }
        });
    }

    /** INSERT ... ON DUPLICATE KEY UPDATE — атомарный upsert. */
    public @NotNull CompletableFuture<Void> upsert(@NotNull UUID townUuid, @NotNull String name) {
        return db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tb_towns (town_uuid, town_name, city_online_seconds) " +
                            "VALUES (?, ?, 0) " +
                            "ON DUPLICATE KEY UPDATE town_name = VALUES(town_name)"
            )) {
                ps.setString(1, townUuid.toString());
                ps.setString(2, name);
                ps.executeUpdate();
            }
        });
    }

    /** Переименование города — сохраняет ВСЕ связанные данные (не слетают). */
    public @NotNull CompletableFuture<Void> rename(@NotNull UUID townUuid, @NotNull String newName) {
        return db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE tb_towns SET town_name = ? WHERE town_uuid = ?"
            )) {
                ps.setString(1, newName);
                ps.setString(2, townUuid.toString());
                ps.executeUpdate();
            }
        });
    }

    /**
     * 4.3 — батч-переименование. Один batch, один round-trip
     * для массового syncNames (TownSyncTask).
     */
    public @NotNull CompletableFuture<Void> batchRename(@NotNull List<TownData> towns) {
        if (towns.isEmpty()) return CompletableFuture.completedFuture(null);
        return db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE tb_towns SET town_name = ? WHERE town_uuid = ?")) {
                for (TownData t : towns) {
                    ps.setString(1, t.townName());
                    ps.setString(2, t.townUuid().toString());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    /**
     * 6.2.1 — удаляет город и все связанные данные одной транзакцией.
     * FK ON DELETE CASCADE сделает это и сам, но multi-table delete —
     * страховка для инсталляций, где FK не встал (старые данные).
     */
    public @NotNull CompletableFuture<Void> delete(@NotNull UUID townUuid) {
        return db.runAsync(conn -> {
            boolean prevAuto = true;
            try {
                prevAuto = conn.getAutoCommit();
                conn.setAutoCommit(false);

                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM tb_inventories WHERE town_uuid = ?")) {
                    ps.setString(1, townUuid.toString());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM tb_stocks WHERE town_uuid = ?")) {
                    ps.setString(1, townUuid.toString());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM tb_player_online WHERE town_uuid = ?")) {
                    ps.setString(1, townUuid.toString());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM tb_logs WHERE town_uuid = ?")) {
                    ps.setString(1, townUuid.toString());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM tb_buildings WHERE town_uuid = ?")) {
                    ps.setString(1, townUuid.toString());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM tb_towns WHERE town_uuid = ?")) {
                    ps.setString(1, townUuid.toString());
                    ps.executeUpdate();
                }

                conn.commit();
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw new RuntimeException("delete town: " + e.getMessage(), e);
            } finally {
                try { conn.setAutoCommit(prevAuto); } catch (SQLException ignored) {}
            }
        });
    }

    /** Прибавить секунды городского онлайна и вернуть новое значение. */
    public @NotNull CompletableFuture<Long> addOnlineSeconds(@NotNull UUID townUuid, long seconds) {
        return db.supplyAsync(conn -> {
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE tb_towns SET city_online_seconds = city_online_seconds + ? " +
                                "WHERE town_uuid = ?"
                )) {
                    ps.setLong(1, seconds);
                    ps.setString(2, townUuid.toString());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT city_online_seconds FROM tb_towns WHERE town_uuid = ?"
                )) {
                    ps.setString(1, townUuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getLong(1) : 0L;
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("addOnlineSeconds: " + e.getMessage(), e);
            }
        });
    }

    public @NotNull CompletableFuture<Void> setOnlineSeconds(@NotNull UUID townUuid, long seconds) {
        return db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE tb_towns SET city_online_seconds = ? WHERE town_uuid = ?"
            )) {
                ps.setLong(1, seconds);
                ps.setString(2, townUuid.toString());
                ps.executeUpdate();
            }
        });
    }

    /**
     * 5.1 — атомарно «забирает» до maxHours часов из city_online_seconds.
     * SELECT ... FOR UPDATE + UPDATE в одной транзакции.
     *
     * @return реально списанное число часов; 0 — если строки нет или < 3600 сек.
     */
    public @NotNull CompletableFuture<Integer> consumeCityHours(@NotNull UUID townUuid, int maxHours) {
        return db.supplyAsync(conn -> {
            boolean prevAuto = true;
            try {
                prevAuto = conn.getAutoCommit();
                conn.setAutoCommit(false);

                long seconds;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT city_online_seconds FROM tb_towns WHERE town_uuid = ? FOR UPDATE")) {
                    ps.setString(1, townUuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return 0;
                        }
                        seconds = rs.getLong(1);
                    }
                }

                int hours = (int) (seconds / 3600L);
                if (hours <= 0) {
                    conn.rollback();
                    return 0;
                }
                if (hours > maxHours) hours = maxHours;

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE tb_towns SET city_online_seconds = city_online_seconds - ? " +
                                "WHERE town_uuid = ?")) {
                    ps.setLong(1, (long) hours * 3600L);
                    ps.setString(2, townUuid.toString());
                    ps.executeUpdate();
                }

                conn.commit();
                return hours;
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw new RuntimeException("consumeCityHours: " + e.getMessage(), e);
            } finally {
                try { conn.setAutoCommit(prevAuto); } catch (SQLException ignored) {}
            }
        });
    }

    /**
     * 1.3 — вернуть часы городу, если город был удалён между consume и выдачей.
     *
     * Если строки уже нет (город снесли) — UPDATE затронет 0 строк.
     * Отдельно обрабатывать не нужно: восстанавливать нечего.
     */
    public @NotNull CompletableFuture<Void> refundCityHours(@NotNull UUID townUuid, int hours) {
        if (hours <= 0) return CompletableFuture.completedFuture(null);
        long seconds = (long) hours * 3600L;
        return db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE tb_towns SET city_online_seconds = city_online_seconds + ? " +
                            "WHERE town_uuid = ?")) {
                ps.setLong(1, seconds);
                ps.setString(2, townUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("refundCityHours: " + e.getMessage(), e);
            }
        });
    }

    // ============================================================
    //   6.2.2 — mayor_uuid
    // ============================================================

    public @NotNull CompletableFuture<Optional<UUID>> findMayorUuid(@NotNull UUID townUuid) {
        return db.supplyAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT mayor_uuid FROM tb_towns WHERE town_uuid = ?")) {
                ps.setString(1, townUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.<UUID>empty();
                    String s = rs.getString("mayor_uuid");
                    return s == null
                            ? Optional.<UUID>empty()
                            : Optional.of(UUID.fromString(s));
                }
            } catch (SQLException e) {
                throw new RuntimeException("findMayorUuid: " + e.getMessage(), e);
            }
        });
    }

    public @NotNull CompletableFuture<Void> setMayor(@NotNull UUID townUuid,
                                                     @Nullable UUID mayorUuid) {
        return db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE tb_towns SET mayor_uuid = ? WHERE town_uuid = ?")) {
                if (mayorUuid != null) ps.setString(1, mayorUuid.toString());
                else                   ps.setNull(1, Types.CHAR);
                ps.setString(2, townUuid.toString());
                ps.executeUpdate();
            }
        });
    }

    // ============================================================
    //   11-D2 — batch-чтение city_online_seconds
    // ============================================================

    public @NotNull CompletableFuture<Map<UUID, Long>> getCitySecondsBatch(
            @NotNull Collection<UUID> townUuids) {
        if (townUuids.isEmpty()) return CompletableFuture.completedFuture(Map.of());

        StringBuilder sb = new StringBuilder(
                "SELECT town_uuid, city_online_seconds FROM tb_towns WHERE town_uuid IN (");
        for (int i = 0; i < townUuids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        sb.append(')');

        return db.supplyAsync(conn -> {
            Map<UUID, Long> out = new HashMap<>(townUuids.size());
            try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
                int idx = 1;
                for (UUID u : townUuids) ps.setString(idx++, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(UUID.fromString(rs.getString("town_uuid")),
                                rs.getLong("city_online_seconds"));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("getCitySecondsBatch: " + e.getMessage(), e);
            }
            return out;
        });
    }
}