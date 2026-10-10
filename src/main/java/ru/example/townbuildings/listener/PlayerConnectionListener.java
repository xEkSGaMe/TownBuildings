package ru.example.townbuildings.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;

/** Синхронизирует сессии игроков с {@link ru.example.townbuildings.service.OnlineTimeService}. */
public final class PlayerConnectionListener implements Listener {

    private final TownBuildingsPlugin plugin;

    public PlayerConnectionListener(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        plugin.onlineTimeService().onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        plugin.onlineTimeService().onQuit(event.getPlayer());
    }
}