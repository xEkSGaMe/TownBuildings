package ru.example.cloudcore.institutes.service;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.CloudCorePlugin;
import ru.example.cloudcore.institutes.data.GovernmentForm;
import ru.example.cloudcore.institutes.data.Ideology;
import ru.example.cloudcore.institutes.data.Religion;
import ru.example.cloudcore.institutes.data.TownInstituteData;
import ru.example.cloudcore.institutes.database.InstitutesDao;
import ru.example.cloudcore.service.TownService;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Фасад над InstitutesDao + in-memory cache.
 *
 * Инвариант: cache всегда соответствует БД после успешного update().
 * Cache обновляется ТОЛЬКО после dao.save() → при падении БД кэш не
 * расходится с реальностью.
 *
 * При warmup() загружаем ВСЕ записи одним SELECT — один раз на старте.
 *
 * ★ 5a: contributeHealthBonus() — единая точка Institutes-вклада в health-бонус.
 *        Вызывается из BonusService.sum() и складывается с buildings + wonders.
 */
public final class InstitutesService {

    private final CloudCorePlugin plugin;
    private final InstitutesDao dao;
    private final TownService townService;

    private final Map<UUID, TownInstituteData> cache = new ConcurrentHashMap<>();

    public InstitutesService(@NotNull CloudCorePlugin plugin,
                             @NotNull InstitutesDao dao,
                             @NotNull TownService townService) {
        this.plugin = plugin;
        this.dao = dao;
        this.townService = townService;
    }

    /**
     * Загружает все institutes-данные в кэш. Вызывается из afterDatabaseReady.
     *
     * @return future, завершающийся после наполнения кэша
     */
    public @NotNull CompletableFuture<Void> warmup() {
        return dao.findAll().thenAccept(map -> {
            cache.clear();
            cache.putAll(map);
            plugin.getLogger().info("[Institutes] Кэш прогрет: " + map.size() + " городов.");
        });
    }

    /**
     * Синхронное чтение из кэша. null — данных нет (не загружено или город без институтов).
     */
    public @Nullable TownInstituteData getCached(@NotNull UUID townUuid) {
        return cache.get(townUuid);
    }

    /**
     * Все закэшированные записи (для reconcile).
     * Копия — безопасно итерировать.
     */
    public @NotNull Collection<TownInstituteData> getAllCached() {
        return List.copyOf(cache.values());
    }

    /**
     * Загружает институты города (или создаёт пустую запись, если в БД нет).
     *
     * Гарантирует, что tb_towns-строка существует — иначе FK в tb_institutes
     * не даст INSERT при последующем update().
     */
    public @NotNull CompletableFuture<TownInstituteData> getOrLoad(
            @NotNull UUID townUuid, @NotNull String townName) {

        TownInstituteData cached = cache.get(townUuid);
        if (cached != null) return CompletableFuture.completedFuture(cached);

        return townService.getOrCreate(townUuid, townName)
                .thenCompose(td -> dao.findById(townUuid))
                .thenApply(opt -> {
                    TownInstituteData data = opt.orElseGet(
                            () -> TownInstituteData.empty(townUuid));
                    cache.putIfAbsent(townUuid, data);
                    return data;
                });
    }

    /**
     * Сохраняет данные: сначала async dao.save(), затем кэш (только при успехе).
     *
     * Такой порядок гарантирует: кэш не «убежит» вперёд БД при ошибке.
     */
    public @NotNull CompletableFuture<Void> update(@NotNull TownInstituteData data) {
        return dao.save(data).thenRun(() -> cache.put(data.townUuid(), data));
    }

    /**
     * Атомарное обновление только chunk-полей. Используется reconcile'ом,
     * чтобы не перезаписывать religion/form/ideology.
     */
    public @NotNull CompletableFuture<Void> updateChunkState(
            @NotNull UUID townUuid, int appliedChunkBonus, boolean initialized) {
        return dao.saveChunkState(townUuid, appliedChunkBonus, initialized)
                .thenRun(() -> {
                    TownInstituteData cur = cache.get(townUuid);
                    if (cur != null) {
                        cache.put(townUuid, cur.withChunkBonus(appliedChunkBonus, initialized));
                    }
                });
    }

    /**
     * Удаление данных города (при DeleteTownEvent).
     * Из кэша убираем сразу — из БД удалит CASCADE при удалении tb_towns.
     */
    public void onTownDeleted(@NotNull UUID townUuid) {
        cache.remove(townUuid);
    }

    /** Очистить кэш (reload). */
    public void invalidateAll() {
        cache.clear();
    }

    /** Sync вызов dao.delete + удаление из кэша. */
    public @NotNull CompletableFuture<Void> delete(@NotNull UUID townUuid) {
        return dao.delete(townUuid).thenRun(() -> cache.remove(townUuid));
    }

    /**
     * ★ 5a — суммарный Institutes-вклад в health-бонус города.
     *
     * Возвращает «сердца» (1 = 2 HP), которые будут прибавлены поверх
     * buildings + wonders в BonusService.sum(). Читается только из кэша —
     * синхронно, дешёво. Если institutes ещё не прогреты — возвращаем 0.
     *
     * Правила (см. institutes.yml):
     *   Islam          → +institutesConfig.healthBonusHearts() (default 2)
     *   Buddhism       → +1
     *   Expansionism   → +institutesConfig.healthBonusHearts() (default 2)
     *   Republic       → +institutesConfig.republicHearts()    (default 1)
     *   Dictatorship   → +institutesConfig.dictatorshipHearts() (default -1)
     */
    public double contributeHealthBonus(@NotNull UUID townUuid) {
        TownInstituteData data = cache.get(townUuid);
        if (data == null) return 0.0;

        double hearts = 0.0;

        if (data.religion() == Religion.ISLAM)
            hearts += plugin.institutesConfig().healthBonusHearts();
        if (data.religion() == Religion.BUDDHISM)
            hearts += 1;
        if (data.ideology() == Ideology.EXPANSIONISM)
            hearts += plugin.institutesConfig().healthBonusHearts();
        if (data.form() == GovernmentForm.REPUBLIC)
            hearts += plugin.institutesConfig().republicHearts();
        if (data.form() == GovernmentForm.DICTATORSHIP)
            hearts += plugin.institutesConfig().dictatorshipHearts();

        return hearts;
    }
}