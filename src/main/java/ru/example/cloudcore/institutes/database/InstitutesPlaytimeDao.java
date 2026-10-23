package ru.example.cloudcore.institutes.database;

import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.database.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * CRUD для tb_institutes_playtime.
 *
 * flush использует INSERT ... ON DUPLICATE KEY UPDATE seconds = VALUES(seconds),
 * то есть ПЕРЕЗАПИСЬ, не инкремент. Это корректно, потому что сервер один
 * и в памяти всегда лежит актуальное значение.
 */
public final class InstitutesPlaytimeDao {

    public record PlayerSeconds(@NotNull UUID playerUuid, long seconds) {}

    private final DatabaseManager db;

    public InstitutesPlaytimeDao(@NotNull DatabaseManager db) {
        this.db = db;
    }

    public @NotNull CompletableFuture<Map<UUID, Long>> findAll() {
        return db.supplyAsync(conn -> {
            Map<UUID, Long> out = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT player_uuid, seconds FROM tb_institutes_playtime")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long sec = rs.getLong("seconds");
                        if (sec > 0) {
                            out.put(UUID.fromString(rs.getString("player_uuid")), sec);
                        }
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("InstitutesPlaytimeDao.findAll: " + e.getMessage(), e);
            }
            return out;
        });
    }

    /**
     * Перезаписывает значения. playerName опционален.
     * Строки с seconds == 0 удаляются.
     */
    public @NotNull CompletableFuture<Void> flush(
            @NotNull Map<UUID, Long> snapshot,
            @NotNull Map<UUID, String> names) {
        if (snapshot.isEmpty()) return CompletableFuture.completedFuture(null);
        return db.runAsync(conn -> {
            boolean prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement up = conn.prepareStatement(
                        "INSERT INTO tb_institutes_playtime (player_uuid, seconds, player_name) " +
                                "VALUES (?, ?, ?) " +
                                "ON DUPLICATE KEY UPDATE seconds = VALUES(seconds), " +
                                "player_name = VALUES(player_name)");
                     PreparedStatement del = conn.prepareStatement(
                             "DELETE FROM tb_institutes_playtime WHERE player_uuid = ?")) {
                    for (Map.Entry<UUID, Long> e : snapshot.entrySet()) {
                        if (e.getValue() == null || e.getValue() <= 0) {
                            del.setString(1, e.getKey().toString());
                            del.addBatch();
                        } else {
                            up.setString(1, e.getKey().toString());
                            up.setLong(2, e.getValue());
                            String name = names.get(e.getKey());
                            if (name == null) up.setNull(3, java.sql.Types.VARCHAR);
                            else              up.setString(3, name);
                            up.addBatch();
                        }
                    }
                    up.executeBatch();
                    del.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw new RuntimeException("InstitutesPlaytimeDao.flush: " + e.getMessage(), e);
            } finally {
                try { conn.setAutoCommit(prevAuto); } catch (SQLException ignored) {}
            }
        });
    }
}