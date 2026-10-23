package ru.example.cloudcore.config;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

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
        double claimDiscountPercent,
        double productionBonusPercent,
        double healthBonusHearts            // ★ 5a
) {
    public static final BuildingRewards EMPTY = new BuildingRewards(
            0L, 0L, Map.of(), 0, 0.0, 0.0, 0.0, 0.0,
            0L, 0, 1.0, 0, 0.0, 0.0, 0.0);

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
                && claimDiscountPercent == 0
                && productionBonusPercent == 0
                && healthBonusHearts == 0;
    }

    public @NotNull BuildingRewards withHealthBonusHearts(double hearts) {
        return new BuildingRewards(
                townCoinsPerHour, citizenCoinsPerHour, stockItemsPerHour,
                townChunksBonus, taxReductionPercent, expBonusPercent,
                harvestBonusPercent, mintIncomeBonusPercent,
                townyWarStartingPoints, townyWarMaxFlagsBonus, townyWarKillPointsMultiplier,
                wonderSlotsBonus, claimDiscountPercent, productionBonusPercent,
                hearts);
    }
}