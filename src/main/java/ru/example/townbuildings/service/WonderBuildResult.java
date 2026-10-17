package ru.example.townbuildings.service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.townbuildings.config.WonderConfig;

public record WonderBuildResult(
        @NotNull Status status,
        @Nullable WonderConfig wonder,
        @Nullable String ownerTownName,
        int usedSlots,
        int slotLimit,
        @Nullable ResourceCheck check
) {

    public enum Status {
        SUCCESS, ALREADY_OWNED, ALREADY_TAKEN, NO_SLOTS, NOT_ENOUGH,
        BUSY, TIMEOUT, ERROR, IN_DEVELOPMENT
    }

    public boolean isSuccess() { return status == Status.SUCCESS; }

    public static @NotNull WonderBuildResult success(@NotNull WonderConfig w) {
        return new WonderBuildResult(Status.SUCCESS, w, null, 0, 0, null);
    }
    public static @NotNull WonderBuildResult alreadyOwned() {
        return new WonderBuildResult(Status.ALREADY_OWNED, null, null, 0, 0, null);
    }
    public static @NotNull WonderBuildResult alreadyTaken(@Nullable String owner) {
        return new WonderBuildResult(Status.ALREADY_TAKEN, null, owner, 0, 0, null);
    }
    public static @NotNull WonderBuildResult noSlots(int used, int limit) {
        return new WonderBuildResult(Status.NO_SLOTS, null, null, used, limit, null);
    }
    public static @NotNull WonderBuildResult notEnough(@NotNull ResourceCheck check) {
        return new WonderBuildResult(Status.NOT_ENOUGH, null, null, 0, 0, check);
    }
    public static @NotNull WonderBuildResult busy() {
        return new WonderBuildResult(Status.BUSY, null, null, 0, 0, null);
    }
    public static @NotNull WonderBuildResult timeout() {
        return new WonderBuildResult(Status.TIMEOUT, null, null, 0, 0, null);
    }
    public static @NotNull WonderBuildResult error() {
        return new WonderBuildResult(Status.ERROR, null, null, 0, 0, null);
    }
}