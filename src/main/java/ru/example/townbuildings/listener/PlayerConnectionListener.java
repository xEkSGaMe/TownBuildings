package ru.example.townbuildings.listener;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;

import java.util.UUID;

/**
 * Синхронизирует сессии игроков с OnlineTimeService и прогревает
 * buildings-кэш города при входе (C3).
 *
 * M-2 — onQuit дополнительно снимает открытые Menu из MenuManager: при
 *       кике/kill-9 InventoryCloseEvent может не прийти, и openMenus
 *       копил бы offline-игроков до onDisable.
 */
public final class PlayerConnectionListener implements Listener {

    /** ★ C3 — задержка прогрева: 20 тиков = 1 сек. */
    private static final long WARMUP_DELAY_TICKS = 20L;

    private final TownBuildingsPlugin plugin;

    public PlayerConnectionListener(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        plugin.onlineTimeService().onJoin(event.getPlayer());

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!event.getPlayer().isOnline()) return;
            var town = plugin.towny().getTownOf(event.getPlayer());
            if (town == null) return;

            final UUID townUuid = town.getUUID();
            final String townNameStr = town.getName();

            // C-1 — сначала гарантируем строку tb_towns (getOrCreate),
            // затем прогреваем buildings. Игрок может зайти в город,
            // созданный до старта плагина без add-resident события в
            // этом рантайме, — тогда SELECT buildingDao.findAllByTown
            // отработает, а вот последующие INSERT'ы упрутся в FK.
            // C3 — прогреваем buildings: LibraryListener / TrainingCampListener
            // / HeadquartersStackListener не наткнутся на холодный кэш
            // через 30 минут TTL.
            plugin.townService().getOrCreate(townUuid, townNameStr)
                    .thenCompose(td -> plugin.townService().getBuildings(townUuid))
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("[Cache] warmup ("
                                + event.getPlayer().getName() + "): " + ex.getMessage());
                        return null;
                    });
        }, WARMUP_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        plugin.onlineTimeService().onQuit(event.getPlayer());
        // ★ M-2 — открытые Menu держат ссылку на Player; при кике/kill-9
        // InventoryCloseEvent может не прийти. Явно снимаем запись,
        // иначе openMenus копит offline-игроков до onDisable.
        if (plugin.menuManager() != null) {
            plugin.menuManager().unregister(event.getPlayer());
        }
    }
}