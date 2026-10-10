package ru.example.townbuildings.listener;

import com.palmergames.bukkit.towny.event.DeleteTownEvent;
import com.palmergames.bukkit.towny.event.TownAddResidentEvent;
import com.palmergames.bukkit.towny.event.TownRemoveResidentEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.service.TownService;

import java.util.Optional;
import java.util.UUID;

/**
 * Синхронизирует БД и кэш с жизненным циклом города в Towny.
 *
 * Переименование города НЕ слушаем — событие Towny менялось между версиями,
 * поэтому сверка имён вынесена в периодическую задачу TownSyncTask.
 */
public final class TownListener implements Listener {

    private final TownBuildingsPlugin plugin;
    private final TownService townService;

    public TownListener(@NotNull TownBuildingsPlugin plugin, @NotNull TownService townService) {
        this.plugin = plugin;
        this.townService = townService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTownDelete(@NotNull DeleteTownEvent event) {
        // P15 — удаление по UUID города из события, а не по имени:
        // findByName при дубле/переименовании мог удалить данные чужого
        // города с таким же именем. getTownUUID() есть в Towny 0.103+.
        UUID townUuid = event.getTownUUID();
        String name = event.getTownName();
        if (townUuid == null) return;

        // 6.5 — снять пермишены мэра (в main), потом удалять.
        // P15b — мэр берётся прямо из события (getMayorUUID), без лишнего
        // async-запроса; если UUID нет — fallback на поиск в БД.
        java.util.Optional<UUID> mayorOpt = Optional.ofNullable(event.getMayorUUID());
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

        // Удалить запись и связанные данные одной транзакцией.
        townService.delete(townUuid)
                .exceptionally(ex -> {
                    plugin.getLogger().severe("[Town] Не удалось удалить город "
                            + name + " из БД: " + ex.getMessage());
                    return null;
                });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidentJoin(@NotNull TownAddResidentEvent event) {
        UUID townUuid = event.getTown().getUUID();
        townService.cache().invalidate(townUuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidentLeave(@NotNull TownRemoveResidentEvent event) {
        UUID townUuid = event.getTown().getUUID();
        townService.cache().invalidate(townUuid);
    }
}