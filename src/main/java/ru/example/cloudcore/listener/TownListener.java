package ru.example.cloudcore.listener;

import com.palmergames.bukkit.towny.event.DeleteTownEvent;
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

import ru.example.cloudcore.CloudCorePlugin;
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

        // ★ WND — освобождаем чудеса города (кэш + FK в БД сработает каскадно).
        if (plugin.wonderService() != null) {
            plugin.wonderService().onTownDeleted(townUuid);
        }
        // ★ Institutes: очистка кэша institutes-данных (в БД FK CASCADE сам удалит)
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