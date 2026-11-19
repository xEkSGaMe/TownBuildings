package ru.example.cloudcore.database.dao;

import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.database.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Доступ к tb_holy_sites.
 *
 * site_id — PRIMARY KEY (одно место в мире строится только один раз),
 * town_uuid — UNIQUE (у города не может быть двух Священных мест).
 */
public final class HolySiteDao {

    public record Placement(@NotNull String siteId, @NotNull UUID townUuid,
                            @NotNull String townName) {}

    private final DatabaseManager db;

    public HolySiteDao(@NotNull DatabaseManager db) {
        this.db = db;
    }

    public @NotNull CompletableFuture<List<Placement>> findAll() {
        return db.supplyAsync(conn -> {
            List<Placement> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT h.site_id, h.town_uuid, t.town_name " +
                            "FROM tb_holy_sites h " +
                            "LEFT JOIN tb_towns t ON h.town_uuid = t.town_uuid")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String sid = rs.getString("site_id");
                        String tu  = rs.getString("town_uuid");
                        String tn  = rs.getString("town_name");
                        if (sid == null || tu == null) continue;
                        out.add(new Placement(sid, UUID.fromString(tu),
                                tn != null ? tn : "?"));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("HolySiteDao.findAll: " + e.getMessage(), e);
            }
            return out;
        });
    }

    /**
     * @return true — INSERT успешен; false — место уже занято (site_id PK
     *         либо town_uuid UNIQUE).
     */
    public @NotNull CompletableFuture<Boolean> tryInsert(
            @NotNull UUID townUuid, @NotNull String siteId) {
        return db.supplyAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tb_holy_sites (site_id, town_uuid) VALUES (?, ?)")) {
                ps.setString(1, siteId);
                ps.setString(2, townUuid.toString());
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                // 1062 — MySQL duplicate key; SQLState 23xxx — общий
                // integrity-constraint (на случай другой СУБД/драйвера).
                String state = e.getSQLState();
                if (e.getErrorCode() == 1062
                        || (state != null && state.startsWith("23"))) {
                    return false;
                }
                throw new RuntimeException("HolySiteDao.tryInsert: " + e.getMessage(), e);
            }
        });
    }

    public @NotNull CompletableFuture<Void> deleteBySite(@NotNull String siteId) {
        return db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM tb_holy_sites WHERE site_id = ?")) {
                ps.setString(1, siteId);
                ps.executeUpdate();
            }
        });
    }

    public @NotNull CompletableFuture<Void> deleteByTown(@NotNull UUID townUuid) {
        return db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM tb_holy_sites WHERE town_uuid = ?")) {
                ps.setString(1, townUuid.toString());
                ps.executeUpdate();
            }
        });
    }
}
