package ru.example.cloudcore.service;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Асинхронный контракт над /t inventory. */
public interface InventoryService {

    /**
     * Проверить наличие ресурсов.
     * ResourceCheck.coins всегда (0, 0) — монеты не здесь.
     */
    @NotNull CompletableFuture<ResourceCheck> check(
            @NotNull UUID townUuid, @NotNull Map<Material, Integer> required);

    /** Списать ресурсы. true — успех, false — не хватает. */
    @NotNull CompletableFuture<Boolean> withdraw(
            @NotNull UUID townUuid, @NotNull Map<Material, Integer> required);

    /**
     * Атомарно списать ресурсы. В отличие от {@link #withdraw},
     * позволяет узнать, чего именно не хватило, и НИЧЕГО не списывает
     * при неудаче.
     */
    @NotNull CompletableFuture<WithdrawResult> withdrawIfAvailable(
            @NotNull UUID townUuid, @NotNull Map<Material, Integer> required);

    /**
     * 9.4 — единый снапшот содержимого склада: Material → суммарное количество.
     * Используется BranchMenu, чтобы не делать N отдельных `check(...)`.
     */
    @NotNull CompletableFuture<Map<Material, Integer>> countAll(@NotNull UUID townUuid);
}