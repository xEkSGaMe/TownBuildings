package ru.example.cloudcore.gui;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.CloudCorePlugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import ru.example.cloudcore.CloudCorePlugin;
/**
 * Реестр открытых меню.
 *
 * M9 — per-player async-lock: tryLockPlayer/unlockPlayer. Заменяет
 *      per-instance Menu.lock()/unlock() — два открытых BranchMenu
 *      у одного игрока (разные ветки) теперь не могут запустить
 *      параллельно два апгрейда.
 */
public final class MenuManager {

    private final CloudCorePlugin plugin;
    private final Map<UUID, Menu> openMenus = new ConcurrentHashMap<>();
    private final Set<UUID> busyPlayers = ConcurrentHashMap.newKeySet();

    public MenuManager(@NotNull CloudCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void register(@NotNull Player player, @NotNull Menu menu) {
        openMenus.put(player.getUniqueId(), menu);
    }

    public void unregister(@NotNull Player player) {
        openMenus.remove(player.getUniqueId());
    }

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

    // -------- ★ M9 — per-player lock --------

    /** @return true, если лок успешно взят (не был занят). */
    public boolean tryLockPlayer(@NotNull Player player) {
        return busyPlayers.add(player.getUniqueId());
    }

    public void unlockPlayer(@NotNull Player player) {
        busyPlayers.remove(player.getUniqueId());
    }

    public boolean isPlayerBusy(@NotNull Player player) {
        return busyPlayers.contains(player.getUniqueId());
    }
}