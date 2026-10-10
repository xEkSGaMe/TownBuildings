package ru.example.townbuildings.service;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.database.dao.StockDao;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Высокоуровневый сервис над StockDao. */
public final class StockService {

    /** id здания, повышающего страницы /t stock. */
    public static final String WAREHOUSE_STOCK_BUILDING_ID = "warehouse_stock";

    private final StockDao dao;

    /**
     * 4.4 — очередь депозитов per town.
     * Без неё два параллельных tryDeposit для одного города видят пустые
     * слоты и перетирают друг друга (CRIT-15).
     */
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> depositTails
            = new ConcurrentHashMap<>();

    public StockService(@NotNull StockDao dao) {
        this.dao = dao;
    }

    /** Положить предмет в хранилище (для задач и бонусов). */
    public @NotNull CompletableFuture<Boolean> tryDeposit(
            @NotNull UUID townUuid, int maxPages, @NotNull ItemStack stack) {

        CompletableFuture<Boolean> result = new CompletableFuture<>();

        depositTails.compute(townUuid, (uuid, tail) -> {
            CompletableFuture<Void> start = (tail == null || tail.isCompletedExceptionally())
                    ? CompletableFuture.completedFuture(null)
                    : tail.exceptionally(t -> null);

            CompletableFuture<Void> done = start
                    .thenCompose(v -> dao.tryDeposit(townUuid, maxPages, stack))
                    .whenComplete((ok, ex) -> {
                        if (ex != null) result.completeExceptionally(ex);
                        else            result.complete(Boolean.TRUE.equals(ok));
                    })
                    .thenApply(ok -> (Void) null);

            done.whenComplete((v, ex) -> depositTails.remove(townUuid, done));
            return done;
        });

        return result;
    }

    /**
     * 9.5.2 — batch-депозит нескольких материалов одной транзакцией.
     * Та же per-town сериализация, чтобы не пересекаться с одиночными tryDeposit.
     */
    public @NotNull CompletableFuture<Boolean> depositBatch(
            @NotNull UUID townUuid, int maxPages, @NotNull Map<Material, Integer> required) {

        if (required.isEmpty()) return CompletableFuture.completedFuture(true);

        CompletableFuture<Boolean> result = new CompletableFuture<>();

        depositTails.compute(townUuid, (uuid, tail) -> {
            CompletableFuture<Void> start = (tail == null || tail.isCompletedExceptionally())
                    ? CompletableFuture.completedFuture(null)
                    : tail.exceptionally(t -> null);

            CompletableFuture<Void> done = start
                    .thenCompose(v -> dao.depositBatch(townUuid, maxPages, required))
                    .whenComplete((ok, ex) -> {
                        if (ex != null) result.completeExceptionally(ex);
                        else            result.complete(Boolean.TRUE.equals(ok));
                    })
                    .thenApply(ok -> (Void) null);

            done.whenComplete((v, ex) -> depositTails.remove(townUuid, done));
            return done;
        });

        return result;
    }

    /** Получить суммарное содержимое (для логов и отладки). */
    public @NotNull CompletableFuture<Map<Material, Integer>> stats(@NotNull UUID townUuid) {
        return dao.countAll(townUuid);
    }

    public @NotNull StockDao dao() {
        return dao;
    }
}