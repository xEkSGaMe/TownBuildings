package ru.example.townbuildings.listener;

import com.palmergames.bukkit.towny.event.TownAddResidentEvent;
import com.palmergames.bukkit.towny.event.TownRemoveResidentEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;

/**
 * Отслеживает вход/выход из города и вход на сервер.
 *
 * При добавлении резидента  — выдать пермишены университета.
 * При удалении резидента    — снять.
 * При входе на сервер       — проверить и выдать (на случай рестарта).
 *
 * Все Towny-обращения — main thread (события Towny и Bukkit приходят в main).
 */
public final class UniversityListener implements Listener {

    private final TownBuildingsPlugin plugin;

    public UniversityListener(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidentJoinTown(@NotNull TownAddResidentEvent event) {
        Player player = event.getResident().getPlayer();
        if (player == null || !player.isOnline()) return;
        plugin.universityService().grant(player, event.getTown());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidentLeaveTown(@NotNull TownRemoveResidentEvent event) {
        Player player = event.getResident().getPlayer();
        if (player == null || !player.isOnline()) return;
        plugin.universityService().revoke(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        var town = plugin.towny().getTownOf(player);
        if (town == null) return;
        final java.util.UUID townUuid = town.getUUID();
        // Задержка 20 тиков: Towny успевает восстановить резидентство после рестарта.
        // P17 (review) — перед выдаче перепроверяем: игрок всё ещё онлайн и
        // состоит в ТОМ ЖЕ городе. Иначе за 20 тиков его могли исключить /
        // он мог сменить город — пермишен чужого университета выдавать нельзя.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            var current = plugin.towny().getTownOf(player);
            if (current == null || !current.getUUID().equals(townUuid)) return;
            plugin.universityService().grant(player, current);
        }, 20L);
    }
}