package ru.example.cloudcore.config;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;

/**
 * Полное описание здания (все уровни).
 *
 * L1.5 — удалён levelOrMax(int): не вызывался. Используются level(int) и
 *        nextLevel(int).
 */
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

    /** @return следующий уровень после currentLevel или empty, если максимум. */
    public @NotNull Optional<BuildingLevel> nextLevel(int currentLevel) {
        if (currentLevel >= maxLevel) return Optional.empty();
        return Optional.ofNullable(levels.get(currentLevel + 1));
    }

    public boolean isMax(int currentLevel) {
        return currentLevel >= maxLevel;
    }
}