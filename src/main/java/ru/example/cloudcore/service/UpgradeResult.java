package ru.example.cloudcore.service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.config.BuildingLevel;

/** Результат попытки улучшить здание. */
public record UpgradeResult(
        @NotNull Status status,
        int newLevel,
        @Nullable ResourceCheck check,
        @Nullable BuildingLevel upgradedTo
) {

    public enum Status {
        SUCCESS,
        UNKNOWN_BUILDING,
        IN_DEVELOPMENT,
        MAX_LEVEL,
        NO_TOWN,
        NOT_ENOUGH_RESOURCES,
        ECONOMY_ERROR,
        INVENTORY_ERROR,
        BUSY,
        TIMEOUT                    // 3.1
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    // -------- Фабрики --------

    public static @NotNull UpgradeResult success(int newLevel, @NotNull BuildingLevel level) {
        return new UpgradeResult(Status.SUCCESS, newLevel, null, level);
    }

    public static @NotNull UpgradeResult unknownBuilding() {
        return new UpgradeResult(Status.UNKNOWN_BUILDING, 0, null, null);
    }

    public static @NotNull UpgradeResult inDevelopment() {
        return new UpgradeResult(Status.IN_DEVELOPMENT, 0, null, null);
    }

    public static @NotNull UpgradeResult maxLevel(int currentLevel) {
        return new UpgradeResult(Status.MAX_LEVEL, currentLevel, null, null);
    }

    public static @NotNull UpgradeResult noTown() {
        return new UpgradeResult(Status.NO_TOWN, 0, null, null);
    }

    public static @NotNull UpgradeResult notEnough(@NotNull ResourceCheck check) {
        return new UpgradeResult(Status.NOT_ENOUGH_RESOURCES, 0, check, null);
    }

    public static @NotNull UpgradeResult economyError(@NotNull ResourceCheck check) {
        return new UpgradeResult(Status.ECONOMY_ERROR, 0, check, null);
    }

    public static @NotNull UpgradeResult inventoryError(@NotNull ResourceCheck check) {
        return new UpgradeResult(Status.INVENTORY_ERROR, 0, check, null);
    }

    public static @NotNull UpgradeResult busy() {
        return new UpgradeResult(Status.BUSY, 0, null, null);
    }

    /** 3.1 — DB-операция не завершилась за отведённое время. */
    public static @NotNull UpgradeResult timeout() {
        return new UpgradeResult(Status.TIMEOUT, 0, null, null);
    }
}