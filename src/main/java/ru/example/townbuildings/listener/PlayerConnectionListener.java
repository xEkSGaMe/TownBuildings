package ru.example.townbuildings.listener;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;

/**
 * Синхронизирует сессии игроков с OnlineTimeService и прогревает
 * buildings-кэш города при входе (C3).
 */
public final class PlayerConnectionListener implements Listener {

    /** ★ C3 — задержка прогрева: 20 тиков = 1 сек — Towny восстанавливает
     *  резидентство после рестарта. */
    private static final long WARMUP_DELAY_TICKS = 20L;

    private final TownBuildingsPlugin plugin;

    public PlayerConnectionListener(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        plugin.onlineTimeService().onJoin(event.getPlayer());

        // ★ C3 — прогрев buildings для города игрока. Гарантирует, что
        // LibraryListener / TrainingCampListener / HeadquartersStackListener
        // не наткнутся на холодный кэш через 30 минут TTL.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!event.getPlayer().isOnline()) return;
            var town = plugin.towny().getTownOf(event.getPlayer());
            if (town == null) return;
            plugin.townService().getBuildings(town.getUUID())
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("[Cache] buildings warmup ("
                                + event.getPlayer().getName() + "): " + ex.getMessage());
                        return null;
                    });
        }, WARMUP_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        plugin.onlineTimeService().onQuit(event.getPlayer());
    }
}