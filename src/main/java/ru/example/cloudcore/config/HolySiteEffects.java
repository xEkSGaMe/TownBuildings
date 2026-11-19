package ru.example.cloudcore.config;

import org.jetbrains.annotations.NotNull;

/**
 * Особые (боевые и бытовые) бонусы Священного места.
 *
 * Большая часть экономических бонусов описывается через {@link BuildingRewards}
 * (монеты, чанки, слоты чудес, опыт, сток). Здесь — только то, что нельзя
 * выразить полями BuildingRewards и что применяется в слушателях событий.
 *
 * Нейтральные значения: всё false / 0 / 1.0.
 */
public record HolySiteEffects(
        boolean doubleRawMeat,
        double damageVsReligiousPercent,
        double potionDurationMultiplier,
        double lowHealthDamagePercent,
        double lowHealthThresholdPercent,
        int gladiatorRegenSeconds,
        int gladiatorRegenAmplifier,
        double monsterDamageMultiplier,
        double dodgeChancePercent
) {

    public static final HolySiteEffects EMPTY = new HolySiteEffects(
            false, 0.0, 1.0, 0.0, 40.0, 0, 0, 1.0, 0.0);

    public boolean hasAny() {
        return doubleRawMeat
                || damageVsReligiousPercent > 0.0
                || potionDurationMultiplier > 1.0
                || lowHealthDamagePercent > 0.0
                || gladiatorRegenSeconds > 0
                || monsterDamageMultiplier > 1.0
                || dodgeChancePercent > 0.0;
    }

    public @NotNull HolySiteEffects withDefaults() {
        return new HolySiteEffects(
                doubleRawMeat,
                Math.max(0.0, damageVsReligiousPercent),
                Math.max(1.0, potionDurationMultiplier),
                Math.max(0.0, lowHealthDamagePercent),
                Math.max(1.0, Math.min(100.0, lowHealthThresholdPercent)),
                Math.max(0, gladiatorRegenSeconds),
                Math.max(0, gladiatorRegenAmplifier),
                Math.max(1.0, monsterDamageMultiplier),
                Math.max(0.0, Math.min(100.0, dodgeChancePercent)));
    }
}
