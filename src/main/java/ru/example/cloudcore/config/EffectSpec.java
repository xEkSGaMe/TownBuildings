package ru.example.cloudcore.config;

import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

/**
 * Описание одного эффекта на уровне здания.
 *
 * PERMANENT — перевыдаётся каждые refresh-секунд длительностью duration-секунд
 *             (визуально «постоянный», пока игрок в городе).
 * PERIODIC  — срабатывает раз в interval-секунд на duration-секунд
 *             (визуально «на время», с паузами).
 */
public record EffectSpec(
        @NotNull PotionEffectType type,
        int amplifier,
        @NotNull Mode mode,
        int intervalSeconds,
        int durationSeconds
) {
    public enum Mode { PERMANENT, PERIODIC }

    /** Для PERMANENT: durationSeconds и intervalSeconds игнорируются. */
    public static @NotNull EffectSpec permanent(@NotNull PotionEffectType type, int amplifier) {
        return new EffectSpec(type, amplifier, Mode.PERMANENT, 0, 0);
    }

    public static @NotNull EffectSpec periodic(@NotNull PotionEffectType type, int amplifier,
                                               int intervalSeconds, int durationSeconds) {
        return new EffectSpec(type, amplifier, Mode.PERIODIC, intervalSeconds, durationSeconds);
    }
}