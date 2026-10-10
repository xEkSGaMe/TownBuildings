package ru.example.townbuildings.database.model;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Снимок одного здания города. */
public record BuildingData(
        @NotNull UUID townUuid,
        @NotNull String buildingId,
        int level
) {
}