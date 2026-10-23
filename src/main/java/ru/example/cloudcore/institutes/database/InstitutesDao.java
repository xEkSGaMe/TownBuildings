package ru.example.cloudcore.institutes.database;

import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.institutes.data.GovernmentForm;
import ru.example.cloudcore.institutes.data.Ideology;
import ru.example.cloudcore.institutes.data.Religion;
import ru.example.cloudcore.institutes.data.TownInstituteData;
import ru.example.cloudcore.database.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * CRUD для tb_institutes.
 *
 * Все операции атомарны на уровне строки: изменение любого поля +
 * applied_chunk_bonus + chunk_bonus_initialized выполняется одним
 * INSERT ... ON DUPLICATE KEY UPDATE.
 *
 * Гарантия от дюпа: до addBonusBlocks(delta) в main-thread состояние
 * БД уже содержит новый applied_chunk_bonus. Если сервер крашится между
 * UPDATE и addBonusBlocks — при следующем reconcile desired == applied,
 * значит дельта НЕ применится (недобор, но не дюп).
 */
public final class InstitutesDao {

    private final DatabaseManager db;

    public InstitutesDao(@NotNull DatabaseManager db) {
        this.db = db;
    }

    public @NotNull CompletableFuture<Optional<TownInstituteData>> findById(@NotNull UUID townUuid) {
        return db.supplyAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT town_uuid, religion, government_form, ideology, " +
                            "applied_chunk_bonus, chunk_bonus_initialized " +
                            "FROM tb_institutes WHERE town_uuid = ?")) {
                ps.setString(1, townUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(readRow(rs));
                }
            } catch (SQLException e) {
                throw new RuntimeException("InstitutesDao.findById: " + e.getMessage(), e);
            }
        });
    }

    public @NotNull CompletableFuture<Map<UUID, TownInstituteData>> findAll() {
        return db.supplyAsync(conn -> {
            Map<UUID, TownInstituteData> out = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT town_uuid, religion, government_form, ideology, " +
                            "applied_chunk_bonus, chunk_bonus_initialized FROM tb_institutes")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        TownInstituteData data = readRow(rs);
                        out.put(data.townUuid(), data);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("InstitutesDao.findAll: " + e.getMessage(), e);
            }
            return out;
        });
    }

    /**
     * Полный upsert: атомарно пишет все поля. Используется при смене
     * выбора (religion/form/ideology) и при reconcile.
     */
    public @NotNull CompletableFuture<Void> save(@NotNull TownInstituteData data) {
        return db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tb_institutes " +
                            "(town_uuid, religion, government_form, ideology, " +
                            "applied_chunk_bonus, chunk_bonus_initialized) " +
                            "VALUES (?, ?, ?, ?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE " +
                            "religion = VALUES(religion), " +
                            "government_form = VALUES(government_form), " +
                            "ideology = VALUES(ideology), " +
                            "applied_chunk_bonus = VALUES(applied_chunk_bonus), " +
                            "chunk_bonus_initialized = VALUES(chunk_bonus_initialized)")) {
                ps.setString(1, data.townUuid().toString());
                setNullableString(ps, 2, data.religion() != null ? data.religion().key() : null);
                setNullableString(ps, 3, data.form()     != null ? data.form().key()     : null);
                setNullableString(ps, 4, data.ideology() != null ? data.ideology().key() : null);
                ps.setInt(5, data.appliedChunkBonus());
                ps.setBoolean(6, data.chunkBonusInitialized());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("InstitutesDao.save: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Атомарное обновление только chunk-полей (для reconcile).
     * Не трогает religion/form/ideology.
     */
    public @NotNull CompletableFuture<Void> saveChunkState(
            @NotNull UUID townUuid, int appliedChunkBonus, boolean initialized) {
        return db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE tb_institutes SET applied_chunk_bonus = ?, chunk_bonus_initialized = ? " +
                            "WHERE town_uuid = ?")) {
                ps.setInt(1, appliedChunkBonus);
                ps.setBoolean(2, initialized);
                ps.setString(3, townUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("InstitutesDao.saveChunkState: " + e.getMessage(), e);
            }
        });
    }

    public @NotNull CompletableFuture<Void> delete(@NotNull UUID townUuid) {
        return db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM tb_institutes WHERE town_uuid = ?")) {
                ps.setString(1, townUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("InstitutesDao.delete: " + e.getMessage(), e);
            }
        });
    }

    private static @NotNull TownInstituteData readRow(@NotNull ResultSet rs) throws SQLException {
        return new TownInstituteData(
                UUID.fromString(rs.getString("town_uuid")),
                Religion.byKey(rs.getString("religion")),
                GovernmentForm.byKey(rs.getString("government_form")),
                Ideology.byKey(rs.getString("ideology")),
                rs.getInt("applied_chunk_bonus"),
                rs.getBoolean("chunk_bonus_initialized")
        );
    }

    private static void setNullableString(PreparedStatement ps, int idx, String value)
            throws SQLException {
        if (value == null) ps.setNull(idx, Types.VARCHAR);
        else ps.setString(idx, value);
    }
}