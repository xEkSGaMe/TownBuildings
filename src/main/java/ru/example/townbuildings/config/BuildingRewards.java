package ru.example.townbuildings.config;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Почасовые и пассивные бонусы здания/чуда.
 *
 * 11-C  — mintIncomeBonusPercent.
 * TW    — TownyWar-поля.
 * WND   — wonderSlotsBonus, claimDiscountPercent.
 */
public record BuildingRewards(
        long townCoinsPerHour,
        long citizenCoinsPerHour,
        @NotNull Map<Material, Integer> stockItemsPerHour,
        int townChunksBonus,
        double taxReductionPercent,
        double expBonusPercent,
        double harvestBonusPercent,
        double mintIncomeBonusPercent,
        long townyWarStartingPoints,
        int townyWarMaxFlagsBonus,
        double townyWarKillPointsMultiplier,
        int wonderSlotsBonus,
        double claimDiscountPercent
) {
    public static final BuildingRewards EMPTY = new BuildingRewards(
            0L, 0L, Map.of(), 0, 0.0, 0.0, 0.0, 0.0,
            0L, 0, 1.0, 0, 0.0);

    public boolean isEmpty() {
        return townCoinsPerHour == 0
                && citizenCoinsPerHour == 0
                && stockItemsPerHour.isEmpty()
                && townChunksBonus == 0
                && taxReductionPercent == 0
                && expBonusPercent == 0
                && harvestBonusPercent == 0
                && mintIncomeBonusPercent == 0
                && townyWarStartingPoints == 0
                && townyWarMaxFlagsBonus == 0
                && townyWarKillPointsMultiplier <= 1.0
                && wonderSlotsBonus == 0
                && claimDiscountPercent == 0;
    }
}