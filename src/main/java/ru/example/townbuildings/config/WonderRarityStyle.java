package ru.example.townbuildings.config;

import org.jetbrains.annotations.NotNull;

public record WonderRarityStyle(
        @NotNull String displayName,
        @NotNull String color,
        @NotNull String gradFrom,
        @NotNull String gradTo
) {}