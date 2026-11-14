package ru.example.cloudcore.listener;

import com.palmergames.bukkit.towny.event.DeleteTownEvent;
import com.palmergames.bukkit.towny.event.RenameTownEvent; // ★ исправлено имя класса
import com.palmergames.bukkit.towny.event.TownAddResidentEvent;
import com.palmergames.bukkit.towny.event.TownRemoveResidentEvent;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.service.TownService;
import ru.example.cloudcore.CloudCorePlugin;

import java.util.Optional;
import java.util.UUID;

public final class TownListener implements Listener {

    private final CloudCorePlugin plugin;
    private final TownService townService;

    public TownListener(@NotNull CloudCorePlugin plugin, @NotNull TownService townService) {
        this.plugin = plugin;
        this.townService = townService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTownDelete(@NotNull DeleteTownEvent event) {
        UUID townUuid = event.getTownUUID();
        String name = event.getTownName();
        if (townUuid == null) return;

        Optional<UUID> mayorOpt = Optional.ofNullable(event.getMayorUUID());
        if (mayorOpt.isPresent()) {
            UUID mayorUuid = mayorOpt.get();
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.permissionService().revokeMayorPermissions(mayorUuid);
                plugin.getLogger().info("[Town] Удалён город " + name
                        + ", пермишены мэра " + mayorUuid + " сняты.");
            });
        } else {
            townService.findMayorUuid(townUuid).thenAccept(fallbackOpt ->
                    fallbackOpt.ifPresent(mayorUuid ->
                            Bukkit.getScheduler().runTask(plugin, () ->
                                    plugin.permissionService().revokeMayorPermissions(mayorUuid))));
        }

        if (plugin.wonderService() != null) {
            plugin.wonderService().onTownDeleted(townUuid);
        }
        if (plugin.institutesService() != null) {
            plugin.institutesService().onTownDeleted(townUuid);
        }

        townService.delete(townUuid)
                .exceptionally(ex -> {
                    plugin.getLogger().severe("[Town] Не удалось удалить город "
                            + name + " из БД: " + ex.getMessage());
                    return null;
                });
    }

    /**
     * ★ Rename: обновляем БД + TownCache через townService.rename() и
     *   сразу чиним кэш WonderService.
     *
     *   ВАЖНО: RenameTownEvent не имеет getNewName() — город уже переименован,
     *   поэтому новое имя берём из town.getName(). Старое доступно через
     *   event.getOldName() (можно использовать в логах).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTownRename(@NotNull RenameTownEvent event) { // ★ RenameTownEvent
        Town town = event.getTown();
        if (town == null) return;

        UUID townUuid = town.getUUID();
        String newName = town.getName(); // ★ новое имя — у города
        if (newName == null || newName.isBlank()) return;

        // 1) БД + TownCache — синхронно через существующий сервис.
        townService.rename(townUuid, newName)
                .exceptionally(ex -> {
                    plugin.getLogger().warning("[Town] rename(" + townUuid
                            + " → " + newName + ") failed: " + ex.getMessage());
                    return null;
                });

        // 2) WonderService — свой кэш town_name по wonder_id.
        if (plugin.wonderService() != null) {
            plugin.wonderService().onTownRenamed(townUuid, newName);
        }

        // 3) (опционально) закрыть открытые GUI со старым title.
        // if (plugin.inventorySessions() != null) {
        //     plugin.inventorySessions().invalidateTownAsync(townUuid);
        // }
        // if (plugin.stockSessions() != null) {
        //     plugin.stockSessions().invalidateTownAsync(townUuid);
        // }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidentJoin(@NotNull TownAddResidentEvent event) {
        Town town = event.getTown();
        if (town == null) return;
        UUID townUuid = town.getUUID();

        townService.getOrCreate(townUuid, town.getName())
                .exceptionally(ex -> {
                    plugin.getLogger().warning("[Town] getOrCreate("
                            + town.getName() + ") упал: " + ex.getMessage());
                    return null;
                });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidentLeave(@NotNull TownRemoveResidentEvent event) {
        UUID townUuid = event.getTown().getUUID();
        townService.cache().invalidate(townUuid);
    }
}