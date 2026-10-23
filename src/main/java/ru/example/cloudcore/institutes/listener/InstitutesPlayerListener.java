package ru.example.cloudcore.institutes.listener;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.CloudCorePlugin;

/**
 * Синхронизирует health-бонус и playtime институтов с жизненным циклом игрока.
 *
 * Оптимизация: onMove реагирует только при смене чанка (16×16 блоков)
 * или мира — уменьшает вызовы в ~16 раз.
 *
 * flush playtime на quit — не синхронный (см. InstitutesPlaytimeService.requestFlush);
 * периодическая задача сама сольёт раз в минуту.
 */
public class InstitutesPlayerListener implements Listener {

    private final CloudCorePlugin plugin;

    public InstitutesPlayerListener(@NotNull CloudCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && plugin.instituteBonus() != null) {
                plugin.instituteBonus().refresh(p);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && plugin.instituteBonus() != null) {
                plugin.instituteBonus().refresh(p);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        if (plugin.instituteBonus() != null) {
            plugin.instituteBonus().removeHealthBonus(e.getPlayer());
        }
        // Триггерим async-flush. Периодическая задача тоже сработает,
        // но так мы не потеряем данные при мгновенном onDisable.
        if (plugin.institutesPlaytime() != null) {
            plugin.institutesPlaytime().requestFlush();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getWorld() != to.getWorld()) {
            refreshSafely(e.getPlayer());
            return;
        }
        int fx = from.getBlockX() >> 4;
        int fz = from.getBlockZ() >> 4;
        int tx = to.getBlockX() >> 4;
        int tz = to.getBlockZ() >> 4;
        if (fx == tx && fz == tz) return;

        refreshSafely(e.getPlayer());
    }

    private void refreshSafely(@NotNull Player p) {
        if (plugin.instituteBonus() != null) {
            plugin.instituteBonus().refresh(p);
        }
    }
}