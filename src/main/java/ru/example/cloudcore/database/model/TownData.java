package ru.example.cloudcore.database.model;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Снимок данных города из БД.
 * Не привязан к Towny — это «сырое» представление строки tb_towns.
 */
public record TownData(
        @NotNull UUID townUuid,
        @NotNull String townName,
        long cityOnlineSeconds
) {
}