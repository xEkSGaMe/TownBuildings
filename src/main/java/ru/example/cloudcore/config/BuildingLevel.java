package ru.example.cloudcore.config;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/** Один уровень здания: описание, стоимость, награды, эффекты. */
public record BuildingLevel(
        int level,
        @NotNull List<String> bonusLore,
        long costCoins,
        @NotNull Map<Material, Integer> costItems,
        @NotNull BuildingRewards rewards,
        @NotNull List<EffectSpec> effects
) {
}