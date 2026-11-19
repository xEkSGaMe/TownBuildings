package ru.example.cloudcore.service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.config.HolySiteConfig;

/**
 * Результат попытки построить Священное место.
 */
public record HolyBuildResult(
        @NotNull Status status,
        @Nullable HolySiteConfig site,
        @Nullable String ownerTownName
) {

    public enum Status {
        SUCCESS, ALREADY_OWNED, ALREADY_TAKEN, NO_SCROLL, WRONG_RELIGION,
        BUSY, TIMEOUT, ERROR, FEATURE_DISABLED
    }

    public boolean isSuccess() { return status == Status.SUCCESS; }

    public static @NotNull HolyBuildResult success(@NotNull HolySiteConfig site) {
        return new HolyBuildResult(Status.SUCCESS, site, null);
    }

    public static @NotNull HolyBuildResult alreadyOwned() {
        return new HolyBuildResult(Status.ALREADY_OWNED, null, null);
    }

    public static @NotNull HolyBuildResult alreadyTaken(@Nullable String ownerTownName) {
        return new HolyBuildResult(Status.ALREADY_TAKEN, null, ownerTownName);
    }

    public static @NotNull HolyBuildResult noScroll() {
        return new HolyBuildResult(Status.NO_SCROLL, null, null);
    }

    public static @NotNull HolyBuildResult wrongReligion() {
        return new HolyBuildResult(Status.WRONG_RELIGION, null, null);
    }

    public static @NotNull HolyBuildResult busy() {
        return new HolyBuildResult(Status.BUSY, null, null);
    }

    public static @NotNull HolyBuildResult timeout() {
        return new HolyBuildResult(Status.TIMEOUT, null, null);
    }

    public static @NotNull HolyBuildResult error() {
        return new HolyBuildResult(Status.ERROR, null, null);
    }

    public static @NotNull HolyBuildResult featureDisabled() {
        return new HolyBuildResult(Status.FEATURE_DISABLED, null, null);
    }
}
