package ru.example.townbuildings.service;

import com.palmergames.bukkit.towny.object.Town;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.config.BuildingDefinition;
import ru.example.townbuildings.config.BuildingDefinitionRegistry;
import ru.example.townbuildings.config.BuildingLevel;
import ru.example.townbuildings.database.model.BuildingData;
import ru.example.townbuildings.util.RomanNumbers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Логика прокачки зданий.
 * Все публичные методы вызываются из ГЛАВНОГО потока.
 *
 * 3.1 — все критичные future обёрнуты в orTimeout(20s). Без этого
 *       зависшее соединение оставляло inFlight навсегда → вечный BUSY.
 */
public final class BuildingService {

    private static final long DB_TIMEOUT_SECONDS = 20L;

    private final TownBuildingsPlugin plugin;
    private final BuildingDefinitionRegistry registry;
    private final TownService townService;
    private final TownyIntegrationService towny;
    private final EconomyService economy;
    private final InventoryService inventory;
    private final LogService logService;

    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public BuildingService(@NotNull TownBuildingsPlugin plugin,
                           @NotNull BuildingDefinitionRegistry registry,
                           @NotNull TownService townService,
                           @NotNull TownyIntegrationService towny,
                           @NotNull EconomyService economy,
                           @NotNull InventoryService inventory,
                           @NotNull LogService logService) {
        this.plugin = plugin;
        this.registry = registry;
        this.townService = townService;
        this.towny = towny;
        this.economy = economy;
        this.inventory = inventory;
        this.logService = logService;
    }

    public void tryUpgrade(@NotNull UUID townUuid,
                           @NotNull String buildingId,
                           @Nullable Player player,
                           @NotNull Consumer<UpgradeResult> callback) {

        if (!inFlight.add(townUuid)) {
            callback.accept(UpgradeResult.busy());
            return;
        }

        // 3.1 — страховка от двойного callback при timeout + позднем
        //        ответе БД. Второй accept тихо логируется.
        AtomicBoolean called = new AtomicBoolean(false);
        Consumer<UpgradeResult> wrapped = result -> {
            if (called.compareAndSet(false, true)) {
                inFlight.remove(townUuid);
                callback.accept(result);
            } else {
                plugin.getLogger().warning("[Upgrade] Повторный callback для "
                        + townUuid + "/" + buildingId + ": " + result.status());
            }
        };

        try {
            tryUpgrade0(townUuid, buildingId, player, wrapped);
        } catch (Throwable t) {
            plugin.getLogger().severe("[Upgrade] tryUpgrade0 threw for "
                    + townUuid + "/" + buildingId + ": " + t);
            wrapped.accept(UpgradeResult.inventoryError(ResourceCheck.trivial()));
        }
    }

    // ============================================================
    //   Admin-lock — тот же inFlight, что используют GUI-апгрейды
    // ============================================================

    /** Идёт ли сейчас апгрейд/GUI-операция для города. */
    public boolean isInFlight(@NotNull UUID townUuid) {
        return inFlight.contains(townUuid);
    }

    /**
     * Захватить город под admin-операцию (setlevel/remove).
     * Возвращает false, если город уже занят — идёт апгрейд через GUI
     * или другая admin-операция. Освобождать обязательно через
     * {@link #releaseAdminLock(UUID)} в finally.
     */
    public boolean tryAdminLock(@NotNull UUID townUuid) {
        return inFlight.add(townUuid);
    }

    public void releaseAdminLock(@NotNull UUID townUuid) {
        inFlight.remove(townUuid);
    }

    private void tryUpgrade0(@NotNull UUID townUuid,
                             @NotNull String buildingId,
                             @Nullable Player player,
                             @NotNull Consumer<UpgradeResult> callback) {

        BuildingDefinition def = registry.byId(buildingId).orElse(null);
        if (def == null) {
            callback.accept(UpgradeResult.unknownBuilding());
            return;
        }
        if (def.inDevelopment()) {
            callback.accept(UpgradeResult.inDevelopment());
            return;
        }

        List<BuildingData> cached = plugin.townService().cache().getBuildings(townUuid);
        if (cached != null) {
            doUpgrade(townUuid, buildingId, def, cached, player, callback);
            return;
        }

        townService.getBuildings(townUuid)
                .orTimeout(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS)                // 3.1
                .whenComplete((list, ex) -> {
                    if (ex != null || list == null) {
                        plugin.getLogger().warning("[Upgrade] getBuildings timeout/fail для "
                                + townUuid + ": " + (ex != null ? ex.getMessage() : "null"));
                        Bukkit.getScheduler().runTask(plugin,
                                () -> callback.accept(UpgradeResult.timeout()));
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () ->
                            doUpgrade(townUuid, buildingId, def, list, player, callback));
                });
    }

    private void doUpgrade(@NotNull UUID townUuid,
                           @NotNull String buildingId,
                           @NotNull BuildingDefinition def,
                           @NotNull List<BuildingData> snapshot,
                           @Nullable Player player,
                           @NotNull Consumer<UpgradeResult> callback) {

        int currentLevel = snapshot.stream()
                .filter(b -> b.buildingId().equals(buildingId))
                .mapToInt(BuildingData::level)
                .findFirst()
                .orElse(0);

        if (def.isMax(currentLevel)) {
            callback.accept(UpgradeResult.maxLevel(currentLevel));
            return;
        }

        BuildingLevel next = def.nextLevel(currentLevel).orElse(null);
        if (next == null) {
            callback.accept(UpgradeResult.maxLevel(currentLevel));
            return;
        }

        Town town = (player != null ? towny.getTownOf(player) : towny.getTownByUuid(townUuid));
        if (town == null || !towny.getTownUuid(town).equals(townUuid)) {
            callback.accept(UpgradeResult.noTown());
            return;
        }

        final long townBalance = (long) Math.floor(economy.getTownBalance(town));

        // ★ Быстрый отказ по монетам — до похода в БД за предметами.
        if (next.costCoins() > townBalance) {
            ResourceCheck check = buildCheck(next, townBalance, next.costItems());
            if (player != null) plugin.soundService().playError(player);
            callback.accept(UpgradeResult.notEnough(check));
            return;
        }

        // 1.2 — сохраняем все незаписанные сессии /t inventory, чтобы
        //        withdraw увидел актуальное состояние и не оставил дюпа.
        //        (Синхронный join по dirty-сессиям; обычно их нет — fast-path.)
        plugin.inventorySessions().flushTownSync(townUuid);

        // ★ Атомарное списание предметов. Если чего-то не хватает —
        //   узнаём ЧЕГО и НИЧЕГО не списываем.
        inventory.withdrawIfAvailable(townUuid, next.costItems())
                .orTimeout(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS)               // 3.1
                .whenComplete((result, ex) -> {
                    if (ex != null || result == null) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (player != null) plugin.soundService().playError(player);
                            callback.accept(UpgradeResult.timeout());
                        });
                        return;
                    }

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!result.ok()) {
                            // Предметов не хватает — собираем ResourceCheck из missing.
                            Map<Material, Integer> have = new HashMap<>();
                            for (Map.Entry<Material, Integer> e : next.costItems().entrySet()) {
                                int miss = result.missing().getOrDefault(e.getKey(), 0);
                                have.put(e.getKey(), Math.max(0, e.getValue() - miss));
                            }
                            ResourceCheck check = buildCheck(next, townBalance, have);
                            if (player != null) plugin.soundService().playError(player);
                            callback.accept(UpgradeResult.notEnough(check));
                            return;
                        }

                        // Предметы списаны. Осталось монеты.
                        if (next.costCoins() > 0) {
                            if (!economy.withdrawFromTown(town, next.costCoins(),
                                    "TownBuildings: upgrade " + buildingId + " to lvl " + next.level())) {
                                // Крайне маловероятно (мы только что проверили баланс),
                                // но если упало — возвращаем предметы обратно.
                                rollbackItems(townUuid, next.costItems());
                                if (player != null) plugin.soundService().playError(player);
                                callback.accept(UpgradeResult.economyError(
                                        buildCheck(next, townBalance, next.costItems())));
                                return;
                            }
                        }

                        // ★ Инвалидируем открытые сессии /t inventory: ресурсы уже
                        //   списаны в БД, GUI должен увидеть новое состояние.
                        //   invalidateTown сам сначала делает flushTownSync — no-op,
                        //   т.к. мы только что всё сбросили.
                        plugin.inventorySessions().invalidateTown(townUuid);

                        finishUpgrade(townUuid, buildingId, def, currentLevel, next,
                                town, player, callback);
                    });
                });
    }

    /** Собрать ResourceCheck по need/have (для GUI-лора). */
    private @NotNull ResourceCheck buildCheck(@NotNull BuildingLevel next,
                                              long townBalance,
                                              @NotNull Map<Material, Integer> haveItems) {
        ResourceCheck.Builder b = ResourceCheck.builder()
                .coins(next.costCoins(), townBalance);
        for (Map.Entry<Material, Integer> e : next.costItems().entrySet()) {
            b.item(e.getKey(), e.getValue(),
                    haveItems.getOrDefault(e.getKey(), 0));
        }
        return b.build();
    }

    /**
     * 1.4 — резолвим реальное число страниц /t inventory города.
     * Не даём писать в страницы, которых у игрока нет.
     */
    private int resolveWarehouseInvPages(@NotNull UUID townUuid) {
        List<BuildingData> buildings = plugin.townService().cache().getBuildings(townUuid);
        if (buildings == null) return 1;
        int level = buildings.stream()
                .filter(b -> "warehouse_inv".equals(b.buildingId()))
                .mapToInt(BuildingData::level)
                .findFirst()
                .orElse(0);
        return 1 + Math.max(0, Math.min(3, level));
    }

    /**
     * 1.4 — возвращаем списанные предметы обратно на /t inventory (rollback).
     * Только в существующие страницы. Если не влезло — пишем WARNING с точным
     * количеством, чтобы админ мог поправить вручную (не теряем молча).
     */
    private void rollbackItems(@NotNull UUID townUuid,
                               @NotNull Map<Material, Integer> items) {
        if (items.isEmpty()) return;
        int maxPages = resolveWarehouseInvPages(townUuid);

        plugin.inventoryDao().depositBack(townUuid, maxPages, items)
                .whenComplete((ok, ex) -> {
                    if (ex != null) {
                        plugin.getLogger().severe("[Upgrade] rollback items FAILED для "
                                + townUuid + ": " + ex.getMessage()
                                + " | потеряно: " + items);
                        return;
                    }
                    if (!Boolean.TRUE.equals(ok)) {
                        // depositBack сам логирует, чего не хватило, но продублируем
                        // контекст апгрейда, чтобы было видно, к какому зданию относится.
                        plugin.getLogger().warning("[Upgrade] rollback items overflow для "
                                + townUuid + " (maxPages=" + maxPages + "): " + items);
                    }
                });
    }

    private void finishUpgrade(@NotNull UUID townUuid,
                               @NotNull String buildingId,
                               @NotNull BuildingDefinition def,
                               int currentLevel,
                               @NotNull BuildingLevel next,
                               @NotNull Town town,
                               @Nullable Player player,
                               @NotNull Consumer<UpgradeResult> callback) {

        int newLevel = currentLevel + 1;

        townService.setBuildingLevel(townUuid, buildingId, newLevel)
                .orTimeout(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS)               // 3.1
                .whenComplete((v, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ex != null) {
                        plugin.getLogger().severe("[Upgrade] setBuildingLevel failed for "
                                + townUuid + "/" + buildingId + ": " + ex.getMessage());
                        if (next.costCoins() > 0) {
                            economy.depositToTown(town, next.costCoins(),
                                    "TownBuildings: rollback " + buildingId);
                        }
                        // P3 — предметы уже списаны из /t inv (транзакция закоммичена
                        // в withdrawIfAvailable). Апгрейд не состоялся — возвращаем их
                        // обратно. Раньше возвращались только монеты, предметы терялись
                        // молча при фейле финального UPDATE.
                        rollbackItems(townUuid, next.costItems());
                        if (player != null) plugin.soundService().playError(player);
                        callback.accept(UpgradeResult.economyError(ResourceCheck.trivial()));
                        return;
                    }

                    logService.logUpgrade(townUuid,
                            player != null ? player.getUniqueId() : null,
                            buildingId, currentLevel, newLevel, next);

                    plugin.effectService().invalidate(townUuid);
                    plugin.bonusService().invalidate(townUuid);

                    UUID mayor = towny.getMayorUuid(town);
                    if (mayor != null) {
                        plugin.mayorChangeListener().refreshPermissions(townUuid, mayor);
                    }

                    // 18.5 — университет: синхронизация пермишенов Essentials.
                    plugin.universityService().syncTown(town);

                    if (player != null) {
                        plugin.soundService().playUpgradeSuccess(player);
                        plugin.soundService().showUpgradeTitle(
                                player,
                                plugin.getMessagesConfig().get("buildings.upgrade.success-title"),
                                plugin.getMessagesConfig().get("buildings.upgrade.success-subtitle",
                                        Map.of(
                                                "building", def.displayName(),
                                                "level", RomanNumbers.toRoman(newLevel)
                                        ))
                        );
                    }

                    broadcastUpgrade(town, def, newLevel);
                    callback.accept(UpgradeResult.success(newLevel, next));
                }));
    }

    private void broadcastUpgrade(@NotNull Town town,
                                  @NotNull BuildingDefinition def,
                                  int newLevel) {
        Map<String, String> ph = Map.of(
                "town", town.getName(),
                "building", def.displayName(),
                "level", Integer.toString(newLevel)
        );
        Component msg = plugin.getMessagesConfig().get("buildings.upgrade.broadcast", ph);

        for (Player member : towny.getOnlineMembers(town)) {
            member.sendMessage(msg);
        }
        plugin.getLogger().info("[Upgrade] " + town.getName() + ": "
                + def.id() + " -> " + newLevel);
    }
}