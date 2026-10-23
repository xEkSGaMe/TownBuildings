package ru.example.cloudcore.service;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.config.*;
import ru.example.cloudcore.database.model.BuildingData;
import java.util.*;

public final class BuildingRewardsMath {
    private BuildingRewardsMath() {}

    public static @NotNull BuildingRewards sum(@NotNull BuildingDefinitionRegistry registry, @NotNull List<BuildingData> buildings) {
        long townCoins = 0, citizenCoins = 0;
        int chunks = 0;
        double tax = 0, exp = 0, harvest = 0, mint = 0, production = 0, health = 0;
        Map<Material, Integer> stock = new HashMap<>();
        long townyWarStartingPoints = 0L;
        int townyWarMaxFlagsBonus = 0;
        double townyWarKillPointsMultiplier = 1.0;
        int wonderSlotsBonus = 0;
        double claimDiscountPercent = 0.0;

        for (BuildingData bd : buildings) {
            BuildingDefinition def = registry.byId(bd.buildingId()).orElse(null);
            if (def == null) continue;

            BuildingRewards rCur = def.level(bd.level()).map(BuildingLevel::rewards).orElse(BuildingRewards.EMPTY);
            townCoins += rCur.townCoinsPerHour(); citizenCoins += rCur.citizenCoinsPerHour();
            chunks += rCur.townChunksBonus(); tax += rCur.taxReductionPercent();
            exp += rCur.expBonusPercent(); harvest += rCur.harvestBonusPercent();
            mint += rCur.mintIncomeBonusPercent(); production += rCur.productionBonusPercent();
            health += rCur.healthBonusHearts();

            int maxLvl = Math.min(bd.level(), def.maxLevel());
            for (int lvl = 1; lvl <= maxLvl; lvl++) {
                BuildingRewards r = def.level(lvl).map(BuildingLevel::rewards).orElse(BuildingRewards.EMPTY);
                for (Map.Entry<Material, Integer> e : r.stockItemsPerHour().entrySet()) stock.merge(e.getKey(), e.getValue(), Integer::sum);
                townyWarStartingPoints += r.townyWarStartingPoints();
                townyWarMaxFlagsBonus += r.townyWarMaxFlagsBonus();
                if (r.townyWarKillPointsMultiplier() > townyWarKillPointsMultiplier) townyWarKillPointsMultiplier = r.townyWarKillPointsMultiplier();
                wonderSlotsBonus += r.wonderSlotsBonus(); claimDiscountPercent += r.claimDiscountPercent();
                health += r.healthBonusHearts();
            }
        }
        return new BuildingRewards(townCoins, citizenCoins, Map.copyOf(stock), chunks, tax, exp, harvest, mint,
                townyWarStartingPoints, townyWarMaxFlagsBonus, townyWarKillPointsMultiplier,
                wonderSlotsBonus, claimDiscountPercent, production, health);
    }

    public static @NotNull BuildingRewards merge(@NotNull BuildingRewards a, @NotNull BuildingRewards b) {
        Map<Material, Integer> stock = new HashMap<>(a.stockItemsPerHour());
        for (var e : b.stockItemsPerHour().entrySet()) stock.merge(e.getKey(), e.getValue(), Integer::sum);
        return new BuildingRewards(
                a.townCoinsPerHour() + b.townCoinsPerHour(), a.citizenCoinsPerHour() + b.citizenCoinsPerHour(),
                Map.copyOf(stock), a.townChunksBonus() + b.townChunksBonus(),
                a.taxReductionPercent() + b.taxReductionPercent(), a.expBonusPercent() + b.expBonusPercent(),
                a.harvestBonusPercent() + b.harvestBonusPercent(), a.mintIncomeBonusPercent() + b.mintIncomeBonusPercent(),
                a.townyWarStartingPoints() + b.townyWarStartingPoints(), a.townyWarMaxFlagsBonus() + b.townyWarMaxFlagsBonus(),
                Math.max(a.townyWarKillPointsMultiplier(), b.townyWarKillPointsMultiplier()),
                a.wonderSlotsBonus() + b.wonderSlotsBonus(), a.claimDiscountPercent() + b.claimDiscountPercent(),
                a.productionBonusPercent() + b.productionBonusPercent(), a.healthBonusHearts() + b.healthBonusHearts()
        );
    }
}