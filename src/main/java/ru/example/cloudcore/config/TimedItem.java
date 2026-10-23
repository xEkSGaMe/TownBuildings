package ru.example.cloudcore.config;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

/**
 * Предмет, выдаваемый городу раз в {@code periodHours} часов.
 * Например: тотем бессмертия раз в 12 ч, зачарованное яблоко раз в 6 ч.
 *
 * Учитывается только «городское время» (то, что копится, пока жители
 * онлайн и RewardService выдаёт награды). Прогресс живёт in-memory
 * в WonderService и сбрасывается при перезапуске сервера — это осознанный
 * компромисс: полноценный персист потребовал бы отдельной таблицы и
 * атомарных апдейтов на каждый час.
 */
public record TimedItem(
        @NotNull Material material,
        int amount,
        int periodHours
) {}