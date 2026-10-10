package ru.example.townbuildings.database.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/** Запись важного действия в tb_logs. */
public record LogEntry(
        long id,
        @NotNull Instant timestamp,
        @Nullable UUID townUuid,
        @Nullable UUID playerUuid,
        @NotNull String action,
        @NotNull String detailsJson
) {
}