package ru.example.townbuildings.service;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.config.BuildingDefinition;
import ru.example.townbuildings.config.BuildingDefinitionRegistry;
import ru.example.townbuildings.config.BuildingLevel;
import ru.example.townbuildings.config.BuildingRewards;
import ru.example.townbuildings.database.model.BuildingData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P22 — единая точка расчёта суммарных бонусов города.
 *
 * Раньше BonusService.sum(...) и RewardService.sumRewards(...) держали
 * две почти одинаковые копии:
 *   - абсолютные бонусы (coins/chunks/tax/exp/harvest/mint) берутся
 *     ТОЛЬКО по текущему уровню здания;
 *   - stockItemsPerHour — кумулятивно по всем уровням 1..current.
 * При правках правило легко разъезжалось. Теперь обе службы зовут
 * один метод.
 *
 * Чистый расчёт, без побочных эффектов, без обращений к БД/кэшу.
 * Реестр передаётся явно — удобно тестировать.
 */
public final class BuildingRewardsMath {

    private BuildingRewardsMath() {}

    public static @NotNull BuildingRewards sum(
            @NotNull BuildingDefinitionRegistry registry,
            @NotNull List<BuildingData> buildings) {

        long townCoins = 0, citizenCoins = 0;
        int chunks = 0;
        double tax = 0, exp = 0, harvest = 0, mint = 0;
        Map<Material, Integer> stock = new HashMap<>();

        for (BuildingData bd : buildings) {
            BuildingDefinition def = registry.byId(bd.buildingId()).orElse(null);
            if (def == null) continue;

            // Абсолютные бонусы — только текущий уровень.
            BuildingRewards rCur = def.level(bd.level())
                    .map(BuildingLevel::rewards)
                    .orElse(BuildingRewards.EMPTY);

            townCoins    += rCur.townCoinsPerHour();
            citizenCoins += rCur.citizenCoinsPerHour();
            chunks       += rCur.townChunksBonus();
            tax          += rCur.taxReductionPercent();
            exp          += rCur.expBonusPercent();
            harvest      += rCur.harvestBonusPercent();
            mint         += rCur.mintIncomeBonusPercent();

            // stockItemsPerHour — кумулятивно по всем уровням 1..current.
            int maxLvl = Math.min(bd.level(), def.maxLevel());
            for (int lvl = 1; lvl <= maxLvl; lvl++) {
                BuildingRewards r = def.level(lvl)
                        .map(BuildingLevel::rewards)
                        .orElse(BuildingRewards.EMPTY);
                for (Map.Entry<Material, Integer> e : r.stockItemsPerHour().entrySet()) {
                    stock.merge(e.getKey(), e.getValue(), Integer::sum);
                }
            }
        }

        return new BuildingRewards(townCoins, citizenCoins,
                Map.copyOf(stock), chunks, tax, exp, harvest, mint);
    }
}