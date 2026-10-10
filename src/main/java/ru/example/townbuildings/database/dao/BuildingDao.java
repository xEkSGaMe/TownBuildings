package ru.example.townbuildings.database.dao;

import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.database.DatabaseManager;
import ru.example.townbuildings.database.model.BuildingData;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** CRUD для tb_buildings. */
public final class BuildingDao {

    private final DatabaseManager db;

    public BuildingDao(@NotNull DatabaseManager db) {
        this.db = db;
    }

    public @NotNull CompletableFuture<Optional<BuildingData>> find(
            @NotNull UUID townUuid, @NotNull String buildingId) {
        return db.supplyAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT town_uuid, building_id, level FROM tb_buildings " +
                            "WHERE town_uuid = ? AND building_id = ?"
            )) {
                ps.setString(1, townUuid.toString());
                ps.setString(2, buildingId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(new BuildingData(
                            UUID.fromString(rs.getString("town_uuid")),
                            rs.getString("building_id"),
                            rs.getInt("level")
                    ));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public @NotNull CompletableFuture<List<BuildingData>> findAllByTown(@NotNull UUID townUuid) {
        return db.supplyAsync(conn -> {
            List<BuildingData> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT town_uuid, building_id, level FROM tb_buildings WHERE town_uuid = ?"
            )) {
                ps.setString(1, townUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new BuildingData(
                                UUID.fromString(rs.getString("town_uuid")),
                                rs.getString("building_id"),
                                rs.getInt("level")
                        ));
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return out;
        });
    }

    /** Установить уровень (0 = удалить здание). UPSERT. */
    public @NotNull CompletableFuture<Void> setLevel(
            @NotNull UUID townUuid, @NotNull String buildingId, int level) {
        return db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tb_buildings (town_uuid, building_id, level) VALUES (?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE level = VALUES(level)"
            )) {
                ps.setString(1, townUuid.toString());
                ps.setString(2, buildingId);
                ps.setInt(3, level);
                ps.executeUpdate();
            }
        });
    }

    /**
     * CAS-апгрейд: пишет newLevel, только если в БД сейчас ровно expectedLevel.
     * Если строки ещё нет и expectedLevel == 0 — INSERT.
     *
     * @return true  — применилось (row inserted или updated);
     *         false — состояние в БД не совпало с ожидаемым (админ-команда /
     *                 другой сервер / устаревший кэш), ресурсы надо вернуть.
     */
    public @NotNull CompletableFuture<Boolean> upgradeIfExpected(
            @NotNull UUID townUuid, @NotNull String buildingId,
            int expectedLevel, int newLevel) {
        if (newLevel <= expectedLevel) {
            throw new IllegalArgumentException("newLevel must be > expectedLevel");
        }
        return db.supplyAsync(conn -> {
            // 1. Атомарный UPDATE с проверкой уровня.
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE tb_buildings SET level = ? " +
                            "WHERE town_uuid = ? AND building_id = ? AND level = ?")) {
                ps.setInt(1, newLevel);
                ps.setString(2, townUuid.toString());
                ps.setString(3, buildingId);
                ps.setInt(4, expectedLevel);
                if (ps.executeUpdate() == 1) return true;
            } catch (SQLException e) {
                throw new RuntimeException("upgradeIfExpected UPDATE: " + e.getMessage(), e);
            }

            // 2. Строки нет — INSERT только для первого апгрейда (expected == 0).
            if (expectedLevel == 0) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO tb_buildings (town_uuid, building_id, level) VALUES (?, ?, ?)")) {
                    ps.setString(1, townUuid.toString());
                    ps.setString(2, buildingId);
                    ps.setInt(3, newLevel);
                    ps.executeUpdate();
                    return true;
                } catch (SQLException e) {
                    // MySQL 1062 = ER_DUP_ENTRY: кто-то вставил строку между UPDATE и INSERT.
                    if (e.getErrorCode() == 1062) return false;
                    throw new RuntimeException("upgradeIfExpected INSERT: " + e.getMessage(), e);
                }
            }
            return false;
        });
    }

    public @NotNull CompletableFuture<Void> remove(
            @NotNull UUID townUuid, @NotNull String buildingId) {
        return db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM tb_buildings WHERE town_uuid = ? AND building_id = ?"
            )) {
                ps.setString(1, townUuid.toString());
                ps.setString(2, buildingId);
                ps.executeUpdate();
            }
        });
    }
}