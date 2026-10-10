package ru.example.townbuildings.service;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Результат атомарного списания ресурсов с /t inventory.
 *
 * ok=true  — всё списано, missing пуст, takenFrom содержит (page, slot)
 *            каждого источника, откуда брали материал.
 * ok=false — НИЧЕГО не списано, missing содержит «сколько ещё нужно»
 *            по каждому материалу, которого не хватило.
 *
 * P15 — takenFrom используется rollback'ом при фейле апгрейда, чтобы
 * depositBack положил предметы обратно в исходные слоты, а не сваливал
 * всё на первую страницу.
 */
public record WithdrawResult(
        boolean ok,
        @NotNull Map<Material, Integer> missing,
        @NotNull Map<Material, List<SlotRef>> takenFrom
) {

    /** P15 — точная ссылка на слот, откуда взяли материал. */
    public record SlotRef(int page, int slot) {}

    public static @NotNull WithdrawResult success() {
        return new WithdrawResult(true, Map.of(), Map.of());
    }

    public static @NotNull WithdrawResult success(
            @NotNull Map<Material, List<SlotRef>> takenFrom) {
        return new WithdrawResult(true, Map.of(), Map.copyOf(takenFrom));
    }

    public static @NotNull WithdrawResult fail(@NotNull Map<Material, Integer> missing) {
        return new WithdrawResult(false, Map.copyOf(missing), Map.of());
    }
}