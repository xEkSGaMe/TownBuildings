package ru.example.townbuildings.listener;

import com.palmergames.bukkit.towny.event.TownUpkeepCalculationEvent;
import com.palmergames.bukkit.towny.event.TownUpkeepPenalityCalculationEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.config.BuildingRewards;

import java.util.UUID;

/**
 * tax_office: снижение ежедневного содержания города.
 *
 * Towny при расчёте upkeep стреляет TownUpkeepCalculationEvent.
 * Мы перехватываем и уменьшаем сумму на taxReductionPercent,
 * суммарный по всем зданиям города.
 *
 * Дополнительно обрабатываем TownUpkeepPenalityCalculationEvent
 * (штраф за overclaim) — иначе города с превышением лимита чанков
 * не получат скидку.
 */
public final class TaxOfficeListener implements Listener {

    private final TownBuildingsPlugin plugin;

    public TaxOfficeListener(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUpkeepCalc(@NotNull TownUpkeepCalculationEvent event) {
        if (!plugin.getConfigManager().features().taxOffice()) return;

        double base = event.getUpkeep();
        if (base <= 0) return;

        double reduction = resolveReductionPercent(event.getTown().getUUID());
        if (reduction <= 0) return;

        double newUpkeep = base * (1.0 - Math.min(reduction, 100.0) / 100.0);
        event.setUpkeep(Math.max(0.0, newUpkeep));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPenaltyCalc(@NotNull TownUpkeepPenalityCalculationEvent event) {
        if (!plugin.getConfigManager().features().taxOffice()) return;

        double base = event.getUpkeep();
        if (base <= 0) return;

        double reduction = resolveReductionPercent(event.getTown().getUUID());
        if (reduction <= 0) return;

        double newPenalty = base * (1.0 - Math.min(reduction, 100.0) / 100.0);
        event.setUpkeep(Math.max(0.0, newPenalty));
    }

    // ============================================================
    //   Внутреннее
    // ============================================================

    /**
     * Возвращает суммарный taxReductionPercent для города.
     * Если кэш BonusService пуст — используем EMPTY rewards (0% скидки).
     */
    private double resolveReductionPercent(@NotNull UUID townUuid) {
        try {
            BuildingRewards rewards = plugin.bonusService().getRewards(townUuid);
            return rewards.taxReductionPercent();
        } catch (Throwable t) {
            plugin.getLogger().warning("[TaxOffice] resolveReductionPercent("
                    + townUuid + "): " + t.getMessage());
            return 0.0;
        }
    }
}