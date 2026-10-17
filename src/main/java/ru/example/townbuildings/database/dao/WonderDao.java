package ru.example.townbuildings.database.dao;

import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.database.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class WonderDao {

    public record Placement(@NotNull String wonderId, @NotNull UUID townUuid,
                            @NotNull String townName) {}

    private final DatabaseManager db;

    public WonderDao(@NotNull DatabaseManager db) {
        this.db = db;
    }

    public @NotNull CompletableFuture<List<Placement>> findAll() {
        return db.supplyAsync(conn -> {
            List<Placement> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT w.wonder_id, w.town_uuid, t.town_name " +
                            "FROM tb_wonders w " +
                            "LEFT JOIN tb_towns t ON w.town_uuid = t.town_uuid")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String wid = rs.getString("wonder_id");
                        String tu  = rs.getString("town_uuid");
                        String tn  = rs.getString("town_name");
                        if (wid == null || tu == null) continue;
                        out.add(new Placement(wid, UUID.fromString(tu),
                                tn != null ? tn : "?"));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("WonderDao.findAll: " + e.getMessage(), e);
            }
            return out;
        });
    }

    /**
     * @return true — INSERT успешен; false — чудо уже построено (PRIMARY KEY).
     */
    public @NotNull CompletableFuture<Boolean> tryInsert(
            @NotNull UUID townUuid, @NotNull String wonderId) {
        return db.supplyAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tb_wonders (wonder_id, town_uuid) VALUES (?, ?)")) {
                ps.setString(1, wonderId);
                ps.setString(2, townUuid.toString());
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                if (e.getErrorCode() == 1062) return false;
                throw new RuntimeException("WonderDao.tryInsert: " + e.getMessage(), e);
            }
        });
    }

    public @NotNull CompletableFuture<Void> deleteByTown(@NotNull UUID townUuid) {
        return db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM tb_wonders WHERE town_uuid = ?")) {
                ps.setString(1, townUuid.toString());
                ps.executeUpdate();
            }
        });
    }

    /** Точечное удаление по id (в отличие от deleteByTown). */
    public @NotNull CompletableFuture<Void> deleteById(@NotNull String wonderId) {
        return db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM tb_wonders WHERE wonder_id = ?")) {
                ps.setString(1, wonderId);
                ps.executeUpdate();
            }
        });
    }

    /** Все бонусы слотов (town_uuid → bonus). */
    public @NotNull CompletableFuture<Map<UUID, Integer>> findAllSlotsBonuses() {
        return db.supplyAsync(conn -> {
            Map<UUID, Integer> out = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT town_uuid, slots_bonus FROM tb_town_wonder_slots")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(UUID.fromString(rs.getString("town_uuid")),
                                rs.getInt("slots_bonus"));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("findAllSlotsBonuses: " + e.getMessage(), e);
            }
            return out;
        });
    }

    /** Установить бонус слотов. bonus == 0 → удалить запись. */
    public @NotNull CompletableFuture<Void> setSlotsBonus(
            @NotNull UUID townUuid, int bonus) {
        return db.runAsync(conn -> {
            if (bonus == 0) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM tb_town_wonder_slots WHERE town_uuid = ?")) {
                    ps.setString(1, townUuid.toString());
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO tb_town_wonder_slots (town_uuid, slots_bonus) VALUES (?, ?) " +
                                "ON DUPLICATE KEY UPDATE slots_bonus = VALUES(slots_bonus)")) {
                    ps.setString(1, townUuid.toString());
                    ps.setInt(2, bonus);
                    ps.executeUpdate();
                }
            }
        });
    }
}