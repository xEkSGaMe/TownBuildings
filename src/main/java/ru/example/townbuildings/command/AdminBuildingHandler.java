package ru.example.townbuildings.command;

import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.config.BuildingDefinition;
import ru.example.townbuildings.database.model.BuildingData;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Обработка /tadmin give, /tadmin setlevel, /tadmin remove. */
public final class AdminBuildingHandler {

    /** 3.1 — тот же таймаут, что в BuildingService. */
    private static final long DB_TIMEOUT_SECONDS = 20L;

    private final TownBuildingsPlugin plugin;

    public AdminBuildingHandler(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
    }

    public void giveOrSet(@NotNull CommandSender sender,
                          @NotNull String townName,
                          @NotNull String buildingId,
                          int level,
                          boolean isSetLevel) {

        Town town = plugin.towny().getTownByName(townName);
        if (town == null) {
            sender.sendMessage(plugin.getMessagesConfig().get("admin.town-not-found",
                    Map.of("town", townName)));
            return;
        }
        BuildingDefinition def = plugin.buildingRegistry().byId(buildingId).orElse(null);
        if (def == null) {
            sender.sendMessage(plugin.getMessagesConfig().get("admin.building-not-found",
                    Map.of("building", buildingId)));
            return;
        }

        UUID townUuid = town.getUUID();
        plugin.townService().getBuildings(townUuid).thenAccept(buildings -> {
            int currentLevel = findLevel(buildings, buildingId);
            int target;
            if (isSetLevel) {
                target = level;
            } else if (level <= 0) {
                target = currentLevel + 1;
            } else {
                target = level;
            }
            target = Math.max(0, Math.min(target, def.maxLevel()));

            final int finalTarget = target;
            final int finalCurrent = currentLevel;

            Bukkit.getScheduler().runTask(plugin, () ->
                    applyLevel(sender, town, townUuid, buildingId, finalCurrent, finalTarget, isSetLevel));
        });
    }

    public void remove(@NotNull CommandSender sender,
                       @NotNull String townName,
                       @NotNull String buildingId,
                       int level) {

        Town town = plugin.towny().getTownByName(townName);
        if (town == null) {
            sender.sendMessage(plugin.getMessagesConfig().get("admin.town-not-found",
                    Map.of("town", townName)));
            return;
        }
        if (plugin.buildingRegistry().byId(buildingId).isEmpty()) {
            sender.sendMessage(plugin.getMessagesConfig().get("admin.building-not-found",
                    Map.of("building", buildingId)));
            return;
        }

        UUID townUuid = town.getUUID();
        plugin.townService().getBuildings(townUuid).thenAccept(buildings -> {
            int currentLevel = findLevel(buildings, buildingId);
            int target;
            if (level <= 0) {
                target = 0;
            } else {
                target = Math.max(0, currentLevel - level);
            }
            final int finalTarget = target;
            final int finalCurrent = currentLevel;

            Bukkit.getScheduler().runTask(plugin, () ->
                    applyLevel(sender, town, townUuid, buildingId, finalCurrent, finalTarget, false));
        });
    }

    private void applyLevel(@NotNull CommandSender sender,
                            @NotNull Town town,
                            @NotNull UUID townUuid,
                            @NotNull String buildingId,
                            int oldLevel,
                            int newLevel,
                            boolean isSetLevel) {

        // Конкурируем за тот же lock, что и GUI-апгрейд. Если мэр уже
        // апгрейдит это здание через /tbuildings — отказываем.
        if (!plugin.buildingService().tryAdminLock(townUuid)) {
            sender.sendMessage(plugin.getMessagesConfig().get("buildings.upgrade.busy"));
            return;
        }

        // P13 — async-flush: раньше был синхронный flushTownSync (join до 2 сек
        // на каждую сессию прямо в main). При зависшей/медленной БД это фризило
        // сервер. Теперь сброс dirty-сессий идёт в io, а смена уровня стартует
        // по завершении (thenCompose) — порядок «flush → setBuildingLevel»
        // сохранён, но main не блокируется.
        // P13b — capture()/diff() внутри doFlushAsync читают Inventory и
        // обязаны запускаться из main: планируем запуск через runTask, а
        // продолжение цепочки идёт уже на io-потоке DatabaseManager.
        CompletableFuture<Void> started = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                CompletableFuture.allOf(
                        plugin.inventorySessions().flushTownAsync(townUuid),
                        plugin.stockSessions().flushTownAsync(townUuid))
                        // P13 — unwrap: inner future — это собственно flush;
                        // outer (started) завершается вместе с ним.
                        .whenComplete((v, ex) -> {
                            if (ex != null) started.completeExceptionally(ex);
                            else started.complete(null);
                        });
            } catch (Throwable t) {
                started.completeExceptionally(t);
            }
        });

        started
                .orTimeout(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS)               // 3.1
                .exceptionally(ex -> {
                    // Таймаут/ошибка flush: продолжаем без него, но предупреждаем —
                    // незаписанные сессии останутся dirty; invalidateTown в
                    // applyLevelDb сам делает финальный flush перед дропом снапшотов.
                    plugin.getLogger().warning("[Admin] flush перед setlevel("
                            + townUuid + "): " + ex.getMessage());
                    return null;
                })
                .thenRun(() -> applyLevelDb(sender, town, townUuid, buildingId,
                        oldLevel, newLevel, isSetLevel))
                .exceptionally(ex -> {
                    plugin.getLogger().severe("[Admin] Ошибка applyLevel: " + ex.getMessage());
                    sender.sendMessage(plugin.getMessagesConfig().get("general.internal-error"));
                    // P13 — lock захвачен в main (tryAdminLock), освобождать
                    // обязаны тоже в main (ConcurrentHashMap сам по себе
                    // потокобезопасен, но держим дисциплину единой точки).
                    Bukkit.getScheduler().runTask(plugin,
                            () -> plugin.buildingService().releaseAdminLock(townUuid));
                    return null;
                });
    }

    /** P13 — часть applyLevel, идущая ПОСЛЕ async-flush: БД-операция + финализация. */
    private void applyLevelDb(@NotNull CommandSender sender,
                              @NotNull Town town,
                              @NotNull UUID townUuid,
                              @NotNull String buildingId,
                              int oldLevel,
                              int newLevel,
                              boolean isSetLevel) {

        // P13 — orTimeout возвращает CompletableFuture<Void>, а не Void:
        // раньше это вызывало "incompatible types: CompletableFuture<Void>
        // cannot be converted to Void". Продолжение цепочки берём из
        // результата orTimeout(...).whenComplete(...) (он тоже CompletionStage<Void>).
        plugin.townService().setBuildingLevel(townUuid, buildingId, newLevel)
                .orTimeout(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS)               // 3.1
                .whenComplete((Void v, Throwable ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (ex != null) {
                            sender.sendMessage(plugin.getMessagesConfig().get("general.internal-error"));
                            plugin.getLogger().severe("[Admin] Ошибка applyLevel: " + ex.getMessage());
                            return;
                        }

                        plugin.effectService().invalidate(townUuid);
                        plugin.bonusService().invalidate(townUuid);

                        // Уровень зданий warehouse_inv / warehouse_stock
                        // меняет число страниц склада и хранилища — открытые
                        // GUI должны быть переоткрыты со свежим снапшотом.
                        // invalidateTown сам делает flushTownSync — no-op,
                        // т.к. мы только что всё сбросили.
                        plugin.inventorySessions().invalidateTown(townUuid);
                        plugin.stockSessions().invalidateTown(townUuid);

                        // 18.5 — университет: синхронизация пермишенов Essentials.
                        plugin.universityService().syncTown(town);

                        // 2.1 — watchtower / headquarters: мэрские пермишены.
                        //        Без этого /tadmin give watchtower не даёт /raid мэру.
                        //        (finishUpgrade в BuildingService делает то же самое
                        //         для обычного апгрейда через GUI.)
                        UUID mayor = plugin.towny().getMayorUuid(town);
                        if (mayor != null) {
                            plugin.mayorChangeListener().refreshPermissions(townUuid, mayor);
                        }

                        if (oldLevel == newLevel) {
                            sender.sendMessage(plugin.getMessagesConfig().get("admin.no-change"));
                            return;
                        }

                        UUID actor = sender instanceof Player p ? p.getUniqueId() : null;
                        Map<String, String> ph = Map.of(
                                "town", town.getName(),
                                "building", buildingId,
                                "level", Integer.toString(newLevel)
                        );

                        if (newLevel <= 0) {
                            plugin.logService().logAdminRemove(townUuid, actor, buildingId, oldLevel);
                            sender.sendMessage(plugin.getMessagesConfig()
                                    .get("admin.remove-success", ph));
                        } else if (isSetLevel) {
                            plugin.logService().logAdminSetLevel(townUuid, actor, buildingId,
                                    oldLevel, newLevel);
                            sender.sendMessage(plugin.getMessagesConfig()
                                    .get("admin.setlevel-success", ph));
                        } else {
                            plugin.logService().logAdminGive(townUuid, actor, buildingId, newLevel);
                            sender.sendMessage(plugin.getMessagesConfig()
                                    .get("admin.give-success", ph));
                        }
                    } finally {
                        plugin.buildingService().releaseAdminLock(townUuid);
                    }
                }));
    }

    private int findLevel(@NotNull List<BuildingData> buildings, @NotNull String buildingId) {
        return buildings.stream()
                .filter(b -> b.buildingId().equals(buildingId))
                .mapToInt(BuildingData::level)
                .findFirst()
                .orElse(0);
    }
}