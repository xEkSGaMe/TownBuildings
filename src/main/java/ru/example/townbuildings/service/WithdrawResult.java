package ru.example.townbuildings.service;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Результат атомарного списания ресурсов с /t inventory.
 *
 * ok=true  — всё списано, missing пуст.
 * ok=false — НИЧЕГО не списано, missing содержит «сколько ещё нужно»
 *            по каждому материалу, которого не хватило.
 */
public record WithdrawResult(
        boolean ok,
        @NotNull Map<Material, Integer> missing
) {

    public static @NotNull WithdrawResult success() {
        return new WithdrawResult(true, Map.of());
    }

    public static @NotNull WithdrawResult fail(@NotNull Map<Material, Integer> missing) {
        return new WithdrawResult(false, Map.copyOf(missing));
    }
}