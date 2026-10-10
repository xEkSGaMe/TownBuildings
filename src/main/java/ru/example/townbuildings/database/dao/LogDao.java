package ru.example.townbuildings.database.dao;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.townbuildings.database.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class LogDao {

    private final DatabaseManager db;

    public LogDao(@NotNull DatabaseManager db) {
        this.db = db;
    }

    public @NotNull CompletableFuture<Void> log(
            @Nullable UUID townUuid,
            @Nullable UUID playerUuid,
            @NotNull String action,
            @NotNull String detailsJson
    ) {
        return db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tb_logs (town_uuid, player_uuid, action, details) VALUES (?, ?, ?, ?)"
            )) {
                if (townUuid != null) ps.setString(1, townUuid.toString());
                else ps.setNull(1, java.sql.Types.CHAR);

                if (playerUuid != null) ps.setString(2, playerUuid.toString());
                else ps.setNull(2, java.sql.Types.CHAR);

                ps.setString(3, action);
                ps.setString(4, detailsJson);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("log: " + e.getMessage(), e);
            }
        });
    }

    public @NotNull CompletableFuture<List<String>> recentByTown(@NotNull UUID townUuid, int limit) {
        return db.supplyAsync(conn -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT ts, player_uuid, action, details FROM tb_logs " +
                            "WHERE town_uuid = ? ORDER BY id DESC LIMIT ?"
            )) {
                ps.setString(1, townUuid.toString());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getTimestamp("ts") + " | "
                                + rs.getString("player_uuid") + " | "
                                + rs.getString("action") + " | "
                                + rs.getString("details"));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("recentByTown: " + e.getMessage(), e);
            }
            return out;
        });
    }
}