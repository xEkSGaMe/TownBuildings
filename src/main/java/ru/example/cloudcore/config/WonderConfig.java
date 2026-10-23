package ru.example.cloudcore.config;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

public record WonderConfig(
        @NotNull String id,
        @NotNull String displayName,
        @NotNull WonderRarity rarity,
        @NotNull Material icon,
        @NotNull String symbol,
        @NotNull String color,
        @NotNull String gradFrom,
        @NotNull String gradTo,
        int guiSlot,
        long costCoins,
        @NotNull Map<Material, Integer> costItems,
        @NotNull BuildingRewards rewards,
        @NotNull List<EffectSpec> effects,
        @NotNull List<TimedItem> timedItems,
        @NotNull List<String> lore,
        int wonderSlotsBonus
) {}