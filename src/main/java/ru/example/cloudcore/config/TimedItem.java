package ru.example.cloudcore.config;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

/**
 * Предмет, выдаваемый городу раз в {@code periodMinutes} минут.
 * Например: тотем бессмертия раз в 12 ч, зачарованное яблоко раз в 150 мин.
 *
 * Период задаётся в минутах, чтобы поддержать интервалы, не кратные часу
 * (150 мин = 2.5 ч). В конфиге можно писать как period-minutes, так и
 * привычные period-hours — парсер переводит часы в минуты.
 *
 * Учитывается только «городское время» (то, что копится, пока жители
 * онлайн и RewardService выдаёт награды). Прогресс живёт in-memory
 * в WonderService/HolySiteService и сбрасывается при перезапуске сервера —
 * это осознанный компромисс: полноценный персист потребовал бы отдельной
 * таблицы и атомарных апдейтов на каждый час.
 */
public record TimedItem(
        @NotNull Material material,
        int amount,
        int periodMinutes
) {}
