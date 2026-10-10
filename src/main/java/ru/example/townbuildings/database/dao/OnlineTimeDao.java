package ru.example.townbuildings.database.dao;

import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.database.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class OnlineTimeDao {

    private final DatabaseManager db;

    public OnlineTimeDao(@NotNull DatabaseManager db) {
        this.db = db;
    }

    public @NotNull CompletableFuture<Long> getPlayerSeconds(@NotNull UUID playerUuid,
                                                             @NotNull UUID townUuid) {
        return db.supplyAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT online_seconds FROM tb_player_online " +
                            "WHERE player_uuid = ? AND town_uuid = ?"
            )) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, townUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (SQLException e) {
                throw new RuntimeException("getPlayerSeconds: " + e.getMessage(), e);
            }
        });
    }

    public @NotNull CompletableFuture<Void> deleteAllForTown(@NotNull UUID townUuid) {
        return db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM tb_player_online WHERE town_uuid = ?"
            )) {
                ps.setString(1, townUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("deleteAllForTown: " + e.getMessage(), e);
            }
        });
    }

    public @NotNull CompletableFuture<Integer> consumePlayerHours(
            @NotNull UUID playerUuid, @NotNull UUID townUuid, int maxHours) {
        return db.supplyAsync(conn -> {
            boolean prevAuto = true;
            try {
                prevAuto = conn.getAutoCommit();
                conn.setAutoCommit(false);

                long seconds;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT online_seconds FROM tb_player_online " +
                                "WHERE player_uuid = ? AND town_uuid = ? FOR UPDATE")) {
                    ps.setString(1, playerUuid.toString());
                    ps.setString(2, townUuid.toString());
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
                        "UPDATE tb_player_online SET online_seconds = online_seconds - ? " +
                                "WHERE player_uuid = ? AND town_uuid = ?")) {
                    ps.setLong(1, (long) hours * 3600L);
                    ps.setString(2, playerUuid.toString());
                    ps.setString(3, townUuid.toString());
                    ps.executeUpdate();
                }

                conn.commit();
                return hours;
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw new RuntimeException("consumePlayerHours: " + e.getMessage(), e);
            } finally {
                try { conn.setAutoCommit(prevAuto); } catch (SQLException ignored) {}
            }
        });
    }

    /**
     * 1.3 — вернуть часы игроку, если награда не была выдана
     * (например, игрок вышел между consume и выдачей).
     *
     * Дописывает online_seconds обратно. Если строки уже нет
     * (town удалён, игрок удалён) — UPDATE затронет 0 строк, это ок:
     * терять там всё равно нечего.
     */
    public @NotNull CompletableFuture<Void> refundPlayerHours(
            @NotNull UUID playerUuid, @NotNull UUID townUuid, int hours) {
        if (hours <= 0) return CompletableFuture.completedFuture(null);
        long seconds = (long) hours * 3600L;
        return db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE tb_player_online SET online_seconds = online_seconds + ? " +
                            "WHERE player_uuid = ? AND town_uuid = ?")) {
                ps.setLong(1, seconds);
                ps.setString(2, playerUuid.toString());
                ps.setString(3, townUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("refundPlayerHours: " + e.getMessage(), e);
            }
        });
    }

    // ============================================================
    //   11-D1 — batch-чтение
    // ============================================================

    public record PlayerTownKey(@NotNull UUID playerUuid, @NotNull UUID townUuid) {}

    public @NotNull CompletableFuture<Map<PlayerTownKey, Long>> getPlayerSecondsBatch(
            @NotNull Collection<PlayerTownKey> keys) {
        if (keys.isEmpty()) return CompletableFuture.completedFuture(Map.of());

        return db.supplyAsync(conn -> {
            StringBuilder sb = new StringBuilder(
                    "SELECT player_uuid, town_uuid, online_seconds " +
                            "FROM tb_player_online WHERE (player_uuid, town_uuid) IN (");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append("(?,?)");
            }
            sb.append(')');

            Map<PlayerTownKey, Long> out = new HashMap<>(keys.size());
            try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
                int idx = 1;
                for (PlayerTownKey k : keys) {
                    ps.setString(idx++, k.playerUuid().toString());
                    ps.setString(idx++, k.townUuid().toString());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(new PlayerTownKey(
                                        UUID.fromString(rs.getString("player_uuid")),
                                        UUID.fromString(rs.getString("town_uuid"))),
                                rs.getLong("online_seconds"));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("getPlayerSecondsBatch: " + e.getMessage(), e);
            }
            return out;
        });
    }
}