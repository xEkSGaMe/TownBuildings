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

/**
 * Прогрев кэша городов при старте.
 *
 * 13.3 — дополнительно грузит buildings каждого города, чтобы
 * LibraryListener (и другие проверки) работали без обращения к БД.
 *
 * ВАЖНО: вызывать из главного потока.
 *
 * Прогрев идёт БАТЧАМИ по ioThreads*4 (макс 64). Внутри батча — параллельно,
 * между батчами — последовательно. Это не даёт забить ioExecutor очередь
 * (500 задач) на 200+ городах и не вызывает CallerRunsPolicy в main.
 */
public final class CacheWarmup {

    /** Потолок на размер батча — чтобы гарантированно влезать в очередь io-pool. */
    private static final int MAX_BATCH = 64;

    private final TownBuildingsPlugin plugin;
    private final TownService townService;
    private final TownDao townDao;

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

        // ★ Считываем все UUID/имена в главном потоке — дальше работаем
        //   только с POJO, никаких обращений к Towny из io-потоков.
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
                        .map(this::warmOne)
                        .toArray(CompletableFuture[]::new);
                return CompletableFuture.allOf(futures);
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
                    .thenApply(list -> (Void) null);
        });
    }

    /** Снимок Towny-данных, безопасный для использования в io-потоке. */
    private record WarmupTarget(@NotNull UUID uuid, @NotNull String name) {}
}