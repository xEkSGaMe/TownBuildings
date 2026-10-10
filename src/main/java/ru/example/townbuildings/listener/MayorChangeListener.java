package ru.example.townbuildings.listener;

import com.palmergames.bukkit.towny.event.TownAddResidentEvent;
import com.palmergames.bukkit.towny.event.TownRemoveResidentEvent;
import com.palmergames.bukkit.towny.event.town.TownMayorChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.database.model.BuildingData;
import ru.example.townbuildings.service.PermissionService;

import java.util.List;
import java.util.UUID;

/**
 * Следит за сменой мэра и перевыдаёт пермишены /raid и /t war.
 * Также обновляет пермишены при изменении уровня «Сторожевой башни» и «Штаба».
 */
public final class MayorChangeListener implements Listener {

    private final TownBuildingsPlugin plugin;
    private final PermissionService permissionService;

    public MayorChangeListener(@NotNull TownBuildingsPlugin plugin,
                               @NotNull PermissionService permissionService) {
        this.plugin = plugin;
        this.permissionService = permissionService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMayorChange(@NotNull TownMayorChangeEvent event) {
        UUID townUuid = event.getTown().getUUID();
        UUID oldMayor = event.getOldMayor() != null ? event.getOldMayor().getUUID() : null;
        UUID newMayor = event.getNewMayor() != null ? event.getNewMayor().getUUID() : null;

        // 6.4 — сохраняем текущего мэра в БД. Понадобится при удалении города,
        // когда Towny уже не отдаст mayor.
        plugin.townService().setMayor(townUuid, newMayor)
                .exceptionally(ex -> {
                    plugin.getLogger().warning("[Mayor] setMayor failed for "
                            + townUuid + ": " + ex.getMessage());
                    return null;
                });

        if (oldMayor != null) {
            permissionService.revokeMayorPermissions(oldMayor);
        }
        if (newMayor != null) {
            refreshPermissions(townUuid, newMayor);
        }
    }

    /** Обновить пермишены мэру по текущим зданиям города. */
    public void refreshPermissions(@NotNull UUID townUuid, @NotNull UUID mayorUuid) {
        plugin.townService().getBuildings(townUuid).whenComplete((buildings, ex) -> {
            if (ex != null || buildings == null) {
                plugin.getLogger().warning("[Mayor] getBuildings failed for "
                        + townUuid + ": " + ex);
                return;
            }
            boolean hasWatchtower = hasBuilding(buildings, "watchtower");
            boolean hasHeadquarters = hasBuilding(buildings, "headquarters");

            // 3.5 — grantMayorPermissions дёргает LuckPerms, держим в main.
            Bukkit.getScheduler().runTask(plugin, () ->
                    permissionService.grantMayorPermissions(
                            mayorUuid, hasWatchtower, hasHeadquarters));
        });
    }

    private boolean hasBuilding(@NotNull List<BuildingData> buildings, @NotNull String buildingId) {
        return buildings.stream().anyMatch(b -> b.buildingId().equals(buildingId) && b.level() > 0);
    }
}