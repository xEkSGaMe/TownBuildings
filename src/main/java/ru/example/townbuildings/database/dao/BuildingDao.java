package ru.example.townbuildings.database.dao;

import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.database.DatabaseManager;
import ru.example.townbuildings.database.model.BuildingData;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
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