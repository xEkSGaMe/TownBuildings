package ru.example.townbuildings.cache;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Town;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.database.dao.TownDao;
import ru.example.townbuildings.database.model.TownData;
import ru.example.townbuildings.service.TownService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Прогрев кэша городов при старте.
 *
 * B2  — каждая warmOne обёрнута в .exceptionally(...).
 * M12 — счётчик подряд идущих ошибок; при пороге — backoff-пауза,
 *       чтобы при деградации БД не долбить её батчами.
 */
public final class CacheWarmup {

    private static final int MAX_BATCH = 64;
    private static final int FAILURE_THRESHOLD = 16;
    private static final long BACKOFF_MS = 1_000L;

    private final TownBuildingsPlugin plugin;
    private final TownService townService;
    private final TownDao townDao;

    /** ★ M12 — счётчик подряд идущих ошибок. */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    public CacheWarmup(@NotNull TownBuildingsPlugin plugin,
                       @NotNull TownService townService,
                       @NotNull TownDao townDao) {
        this.plugin = plugin;
        this.townService = townService;
        this.townDao = townDao;
    }

    public @NotNull CompletableFuture<Void> run() {
        Collection<Town> towns = TownyAPI.getInstance().getTowns();
        if (towns.isEmpty()) {
            plugin.getLogger().info("[Cache] Нет городов для прогрева.");
            return CompletableFuture.completedFuture(null);
        }

        List<WarmupTarget> targets = new ArrayList<>(towns.size());
        for (Town t : towns) {
            targets.add(new WarmupTarget(t.getUUID(), t.getName()));
        }

        int ioThreads = Math.max(2,
                plugin.getConfigManager().database().pool().ioThreads());
        int batchSize = Math.max(4, Math.min(ioThreads * 4, MAX_BATCH));

        plugin.getLogger().info("[Cache] Прогрев " + targets.size()
                + " городов (батч " + batchSize + ")...");

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < targets.size(); i += batchSize) {
            List<WarmupTarget> batch =
                    targets.subList(i, Math.min(i + batchSize, targets.size()));

            chain = chain.thenCompose(v -> {
                CompletableFuture<?>[] futures = batch.stream()
                        .map(t -> warmOne(t).exceptionally(ex -> {
                            plugin.getLogger().warning("[Cache] warmOne(" + t.name()
                                    + " / " + t.uuid() + ") упал: " + ex.getMessage());
                            consecutiveFailures.incrementAndGet();
                            return null;
                        }))
                        .toArray(CompletableFuture[]::new);
                return CompletableFuture.allOf(futures).thenCompose(ignored -> {
                    // ★ M12 — если предыдущие батчи дружно падали, даём БД передохнуть.
                    if (consecutiveFailures.get() >= FAILURE_THRESHOLD) {
                        plugin.getLogger().warning("[Cache] " + consecutiveFailures.get()
                                + " подряд ошибок БД — пауза " + BACKOFF_MS + " мс");
                        consecutiveFailures.set(0);
                        return CompletableFuture.runAsync(() -> {
                            try { Thread.sleep(BACKOFF_MS); }
                            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        });
                    }
                    return CompletableFuture.completedFuture(null);
                });
            });
        }

        return chain.thenRun(() -> plugin.getLogger().info(
                "[Cache] Прогрев завершён. Городов в кэше: "
                        + townService.cache().size()));
    }

    private @NotNull CompletableFuture<Void> warmOne(@NotNull WarmupTarget t) {
        return townDao.findById(t.uuid()).thenCompose(opt -> {
            CompletableFuture<TownData> townFuture = opt
                    .map(td -> {
                        townService.cacheTown(td);
                        return CompletableFuture.completedFuture(td);
                    })
                    .orElseGet(() -> townService.getOrCreate(t.uuid(), t.name()));

            return townFuture
                    .thenCompose(td -> townService.getBuildings(t.uuid()))
                    .thenApply(list -> {
                        // ★ M12 — успех сбрасывает счётчик.
                        consecutiveFailures.set(0);
                        return (Void) null;
                    });
        });
    }

    private record WarmupTarget(@NotNull UUID uuid, @NotNull String name) {}
}