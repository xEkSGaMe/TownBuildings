package ru.example.cloudcore.service;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.config.BuildingLevel;
import ru.example.cloudcore.database.dao.LogDao;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class LogService {

    private final LogDao logDao;

    public LogService(@NotNull LogDao logDao) {
        this.logDao = logDao;
    }

    public @NotNull CompletableFuture<Void> logUpgrade(
            @NotNull UUID townUuid,
            @Nullable UUID player,
            @NotNull String buildingId,
            int fromLevel,
            int toLevel,
            @NotNull BuildingLevel target
    ) {
        String details = json(
                "\"building\":\"" + escape(buildingId) + "\"",
                "\"from\":" + fromLevel,
                "\"to\":" + toLevel,
                "\"coins\":" + target.costCoins(),
                "\"items\":" + itemsJson(target.costItems())
        );
        return logDao.log(townUuid, player, "UPGRADE", details);
    }

    public @NotNull CompletableFuture<Void> logAdminGive(
            @NotNull UUID townUuid, @Nullable UUID player,
            @NotNull String buildingId, int level) {
        return logDao.log(townUuid, player, "ADMIN_GIVE",
                json("\"building\":\"" + escape(buildingId) + "\"",
                        "\"level\":" + level));
    }

    public @NotNull CompletableFuture<Void> logAdminRemove(
            @NotNull UUID townUuid, @Nullable UUID player,
            @NotNull String buildingId, int level) {
        return logDao.log(townUuid, player, "ADMIN_REMOVE",
                json("\"building\":\"" + escape(buildingId) + "\"",
                        "\"level\":" + level));
    }

    public @NotNull CompletableFuture<Void> logAdminSetLevel(
            @NotNull UUID townUuid, @Nullable UUID player,
            @NotNull String buildingId, int from, int to) {
        return logDao.log(townUuid, player, "ADMIN_SETLEVEL",
                json("\"building\":\"" + escape(buildingId) + "\"",
                        "\"from\":" + from,
                        "\"to\":" + to));
    }

    public @NotNull CompletableFuture<Void> logReload(@Nullable UUID player) {
        return logDao.log(null, player, "ADMIN_RELOAD", "{}");
    }

    /** Критичные сбои возврата ресурсов при фейле апгрейда — для аудита и ручного фикса. */
    public @NotNull CompletableFuture<Void> logRollbackFailed(
            @NotNull UUID townUuid,
            @Nullable UUID player,
            @NotNull String buildingId,
            long coins,
            @NotNull String note) {
        String details = json(
                "\"building\":\"" + escape(buildingId) + "\"",
                "\"coins\":" + coins,
                "\"note\":\"" + escape(note) + "\"");
        return logDao.log(townUuid, player, "ROLLBACK_FAILED", details);
    }

    // -------- ★ Wonder admin logs --------

    public @NotNull CompletableFuture<Void> logAdminWonderGive(
            @NotNull UUID townUuid, @Nullable UUID player, @NotNull String wonderId) {
        return logDao.log(townUuid, player, "ADMIN_WONDER_GIVE",
                json("\"wonder\":\"" + escape(wonderId) + "\""));
    }

    public @NotNull CompletableFuture<Void> logAdminWonderRemove(
            @NotNull UUID townUuid, @Nullable UUID player, @NotNull String wonderId) {
        return logDao.log(townUuid, player, "ADMIN_WONDER_REMOVE",
                json("\"wonder\":\"" + escape(wonderId) + "\""));
    }

    public @NotNull CompletableFuture<Void> logAdminWonderSlots(
            @NotNull UUID townUuid, @Nullable UUID player, int bonus) {
        return logDao.log(townUuid, player, "ADMIN_WONDER_SLOTS",
                json("\"bonus\":" + bonus));
    }

    // -------- helpers --------

    private static @NotNull String itemsJson(@NotNull Map<Material, Integer> items) {
        StringBuilder sb = new StringBuilder(64);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<Material, Integer> e : items.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(e.getKey().name()).append("\":").append(e.getValue());
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }

    private static @NotNull String json(@NotNull String... fields) {
        return "{" + String.join(",", fields) + "}";
    }

    /** 11-B2 — escape полный набор управляющих символов JSON. */
    private static @NotNull String escape(@NotNull String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}