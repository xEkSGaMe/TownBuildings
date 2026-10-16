package ru.example.townbuildings.listener;

import com.palmergames.bukkit.towny.event.DeleteTownEvent;
import com.palmergames.bukkit.towny.event.TownAddResidentEvent;
import com.palmergames.bukkit.towny.event.TownRemoveResidentEvent;
import com.palmergames.bukkit.towny.object.Town;
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
 * Переименование города НЕ слушаем — сверка имён вынесена в TownSyncTask.
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
        UUID townUuid = event.getTownUUID();
        String name = event.getTownName();
        if (townUuid == null) return;

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

        townService.delete(townUuid)
                .exceptionally(ex -> {
                    plugin.getLogger().severe("[Town] Не удалось удалить город "
                            + name + " из БД: " + ex.getMessage());
                    return null;
                });
    }

    /**
     * C-1 — гарантируем строку tb_towns при входе резидента в город.
     *
     * Строка создаётся ТОЛЬКО в CacheWarmup и здесь (плюс backstop в
     * TownSyncTask). Без этого для города, созданного после старта плагина,
     * первый же INSERT в tb_player_online / tb_inventories / tb_stocks
     * упадёт на FK-каскаде (fk_online_town / fk_inv_town / fk_stock_town)
     * и откатит всю транзакцию batch-flush'а — секунды онлайна и записи
     * склада будут теряться каждые 30 секунд.
     *
     * getOrCreate идемпотентен: на существующем городе это cache-hit
     * (или SELECT), на новом — INSERT ... ON DUPLICATE KEY UPDATE.
     */
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
        // Инвалидацию убрали: вход резидента не меняет ни buildings, ни
        // townName, ни cityOnlineSeconds. Кэш уже корректен после
        // getOrCreate; форсить повторный SELECT на каждое событие нет смысла.
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidentLeave(@NotNull TownRemoveResidentEvent event) {
        UUID townUuid = event.getTown().getUUID();
        townService.cache().invalidate(townUuid);
    }
}