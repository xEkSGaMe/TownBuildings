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

/**
 * Обработка /tadmin give, /tadmin setlevel, /tadmin remove.
 *
 * B1 — флаг isRemove.
 * B3 — flushTownAsync(...).orTimeout(...) не проглатывает TimeoutException.
 * C4 — no-op (oldLevel == newLevel) проверяется в САМОМ НАЧАЛЕ applyLevelDb.
 * C-1 — getOrCreate перед getBuildings.
 * H-2 — invalidateTown → invalidateTownAsync.
 * L-4 — remove: если у города нет такого здания — явное сообщение
 *       admin.remove-not-found, а не «no-change».
 */
public final class AdminBuildingHandler {

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

        plugin.townService().getOrCreate(townUuid, town.getName())
                .thenCompose(td -> plugin.townService().getBuildings(townUuid))
                .thenAccept(buildings -> {
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
                            applyLevel(sender, town, townUuid, buildingId,
                                    finalCurrent, finalTarget, isSetLevel, false));
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("[Admin] giveOrSet(" + townUuid
                            + ") упал: " + ex.getMessage());
                    Bukkit.getScheduler().runTask(plugin, () ->
                            sender.sendMessage(plugin.getMessagesConfig()
                                    .get("general.internal-error")));
                    return null;
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

        plugin.townService().getOrCreate(townUuid, town.getName())
                .thenCompose(td -> plugin.townService().getBuildings(townUuid))
                .thenAccept(buildings -> {
                    int currentLevel = findLevel(buildings, buildingId);

                    // ★ L-4 — здания нет у города: сообщаем явно, не «no-change».
                    if (currentLevel == 0) {
                        Bukkit.getScheduler().runTask(plugin, () ->
                                sender.sendMessage(plugin.getMessagesConfig()
                                        .get("admin.remove-not-found",
                                                Map.of("building", buildingId))));
                        return;
                    }

                    int target;
                    if (level <= 0) {
                        target = 0;
                    } else {
                        target = Math.max(0, currentLevel - level);
                    }
                    final int finalTarget = target;
                    final int finalCurrent = currentLevel;

                    Bukkit.getScheduler().runTask(plugin, () ->
                            applyLevel(sender, town, townUuid, buildingId,
                                    finalCurrent, finalTarget, false, true));
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("[Admin] remove(" + townUuid
                            + ") упал: " + ex.getMessage());
                    Bukkit.getScheduler().runTask(plugin, () ->
                            sender.sendMessage(plugin.getMessagesConfig()
                                    .get("general.internal-error")));
                    return null;
                });
    }

    private void applyLevel(@NotNull CommandSender sender,
                            @NotNull Town town,
                            @NotNull UUID townUuid,
                            @NotNull String buildingId,
                            int oldLevel,
                            int newLevel,
                            boolean isSetLevel,
                            boolean isRemove) {

        if (!plugin.buildingService().tryAdminLock(townUuid)) {
            sender.sendMessage(plugin.getMessagesConfig().get("buildings.upgrade.busy"));
            return;
        }

        CompletableFuture<Void> started;
        try {
            started = CompletableFuture.allOf(
                    plugin.inventorySessions().flushTownAsync(townUuid),
                    plugin.stockSessions().flushTownAsync(townUuid)
            );
        } catch (Throwable t) {
            started = CompletableFuture.failedFuture(t);
        }

        started.orTimeout(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .handle((v, ex) -> ex == null)
                .thenAccept(ok -> {
                    if (!ok) {
                        plugin.getLogger().warning("[Admin] flush timeout/fail для "
                                + townUuid + " — setlevel пропущен");
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            plugin.buildingService().releaseAdminLock(townUuid);
                            sender.sendMessage(plugin.getMessagesConfig()
                                    .get("buildings.upgrade.timeout"));
                        });
                        return;
                    }
                    applyLevelDb(sender, town, townUuid, buildingId,
                            oldLevel, newLevel, isSetLevel, isRemove);
                });
    }

    private void applyLevelDb(@NotNull CommandSender sender,
                              @NotNull Town town,
                              @NotNull UUID townUuid,
                              @NotNull String buildingId,
                              int oldLevel,
                              int newLevel,
                              boolean isSetLevel,
                              boolean isRemove) {

        if (oldLevel == newLevel) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    sender.sendMessage(plugin.getMessagesConfig().get("admin.no-change"));
                } finally {
                    plugin.buildingService().releaseAdminLock(townUuid);
                }
            });
            return;
        }

        plugin.townService().setBuildingLevel(townUuid, buildingId, newLevel)
                .orTimeout(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((v, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (ex != null) {
                            sender.sendMessage(plugin.getMessagesConfig().get("general.internal-error"));
                            plugin.getLogger().severe("[Admin] Ошибка applyLevel: " + ex.getMessage());
                            return;
                        }

                        plugin.effectService().invalidate(townUuid);
                        plugin.bonusService().invalidate(townUuid);

                        plugin.inventorySessions().invalidateTownAsync(townUuid);
                        plugin.stockSessions().invalidateTownAsync(townUuid);

                        plugin.universityService().syncTown(town);

                        try {
                            plugin.townyWarService().onBuildingLevelChanged(
                                    town, buildingId, oldLevel, newLevel);
                        } catch (Throwable t) {
                            plugin.getLogger().warning("[Admin] TownyWar sync failed: "
                                    + t.getMessage());
                        }

                        UUID mayor = plugin.towny().getMayorUuid(town);
                        if (mayor != null) {
                            plugin.mayorChangeListener().refreshPermissions(townUuid, mayor);
                        }

                        UUID actor = sender instanceof Player p ? p.getUniqueId() : null;
                        Map<String, String> ph = Map.of(
                                "town", town.getName(),
                                "building", buildingId,
                                "level", Integer.toString(newLevel)
                        );

                        if (isRemove) {
                            plugin.logService().logAdminRemove(townUuid, actor, buildingId, oldLevel);
                            if (newLevel <= 0) {
                                sender.sendMessage(plugin.getMessagesConfig()
                                        .get("admin.remove-success", ph));
                            } else {
                                sender.sendMessage(plugin.getMessagesConfig()
                                        .get("admin.downgrade-success", ph));
                            }
                        } else if (newLevel <= 0) {
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