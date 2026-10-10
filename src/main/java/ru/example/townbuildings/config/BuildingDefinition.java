package ru.example.townbuildings.config;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;

/** Полное описание здания (все уровни). */
public record BuildingDefinition(
        @NotNull String id,
        @NotNull String displayName,
        @NotNull BuildingBranch branch,
        @NotNull Material icon,
        int maxLevel,
        boolean inDevelopment,
        @NotNull Map<Integer, BuildingLevel> levels
) {
    public @NotNull Optional<BuildingLevel> level(int n) {
        return Optional.ofNullable(levels.get(n));
    }

    /** @return уровень, если здание уже прокачано до этой ступени. */
    public @NotNull Optional<BuildingLevel> levelOrMax(int currentLevel) {
        if (currentLevel <= 0) return Optional.empty();
        int target = Math.min(currentLevel, maxLevel);
        return Optional.ofNullable(levels.get(target));
    }

    /** @return следующий уровень после currentLevel или empty, если максимум. */
    public @NotNull Optional<BuildingLevel> nextLevel(int currentLevel) {
        if (currentLevel >= maxLevel) return Optional.empty();
        return Optional.ofNullable(levels.get(currentLevel + 1));
    }

    public boolean isMax(int currentLevel) {
        return currentLevel >= maxLevel;
    }
}