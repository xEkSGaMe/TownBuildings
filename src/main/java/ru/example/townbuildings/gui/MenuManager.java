package ru.example.townbuildings.gui;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реестр открытых меню. Позволяет:
 *  - быстро найти меню игрока в MenuListener;
 *  - попросить меню refresh после изменения данных.
 */
public final class MenuManager {

    private final TownBuildingsPlugin plugin;
    private final Map<UUID, Menu> openMenus = new ConcurrentHashMap<>();

    public MenuManager(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
    }

    public void register(@NotNull Player player, @NotNull Menu menu) {
        openMenus.put(player.getUniqueId(), menu);
    }

    public void unregister(@NotNull Player player) {
        openMenus.remove(player.getUniqueId());
    }

    /**
     * 1.6 — снимает запись только если она всё ещё указывает на это меню.
     * Защита от гонки close → open в одном тике.
     */
    public void unregisterIfCurrent(@NotNull Player player, @NotNull Menu menu) {
        openMenus.computeIfPresent(player.getUniqueId(),
                (uuid, current) -> current == menu ? null : current);
    }

    public void closeAll() {
        for (Map.Entry<UUID, Menu> e : openMenus.entrySet()) {
            Player p = plugin.getServer().getPlayer(e.getKey());
            if (p != null && p.isOnline()) p.closeInventory();
        }
        openMenus.clear();
    }
}