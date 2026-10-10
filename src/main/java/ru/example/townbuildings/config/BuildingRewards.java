package ru.example.townbuildings.config;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Почасовые и пассивные бонусы здания.
 * 11-C — добавлен mintIncomeBonusPercent: +% к доходу монетных дворов.
 */
public record BuildingRewards(
        long townCoinsPerHour,
        long citizenCoinsPerHour,
        @NotNull Map<Material, Integer> stockItemsPerHour,
        int townChunksBonus,
        double taxReductionPercent,
        double expBonusPercent,
        double harvestBonusPercent,
        double mintIncomeBonusPercent
) {

    public static final BuildingRewards EMPTY = new BuildingRewards(
            0L, 0L, Map.of(), 0, 0.0, 0.0, 0.0, 0.0);

    public boolean isEmpty() {
        return townCoinsPerHour == 0
                && citizenCoinsPerHour == 0
                && stockItemsPerHour.isEmpty()
                && townChunksBonus == 0
                && taxReductionPercent == 0
                && expBonusPercent == 0
                && harvestBonusPercent == 0
                && mintIncomeBonusPercent == 0;
    }
}