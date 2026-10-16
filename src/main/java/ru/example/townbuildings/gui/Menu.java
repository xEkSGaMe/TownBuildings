package ru.example.townbuildings.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Базовое меню.
 *
 * M8 — close() синхронно снимает запись из MenuManager (unregisterIfCurrent),
 *      не полагаясь только на InventoryCloseEvent.
 * M9 — лок на время async теперь per-player в MenuManager
 *      (tryLockPlayer/unlockPlayer). Menu.lock()/unlock()/isLocked() удалены:
 *      они были per-instance, и два открытых BranchMenu у одного игрока
 *      (разные ветки) не блокировали друг друга.
 */
public abstract class Menu implements InventoryHolder {

    protected final TownBuildingsPlugin plugin;
    protected final Player player;
    protected Inventory inventory;

    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();

    protected Menu(@NotNull TownBuildingsPlugin plugin, @NotNull Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    protected abstract int size();
    protected abstract @NotNull Component title(@NotNull Player player);
    protected abstract void render(@NotNull Player player);
    public abstract void onClick(@NotNull Player player, int slot, @NotNull InventoryClickEvent event);

    public final void open() {
        this.inventory = Bukkit.createInventory(this, size(), title(player));
        render(player);
        viewers.add(player.getUniqueId());
        player.openInventory(inventory);
    }

    public final void refresh() {
        if (inventory == null) return;
        inventory.clear();
        render(player);
    }

    // ★ M8 — синхронный unregister + закрытие.
    public final void close() {
        viewers.clear();
        if (plugin.menuManager() != null) {
            plugin.menuManager().unregisterIfCurrent(player, this);
        }
        if (player.isOnline()) player.closeInventory();
    }

    @Override
    public final @NotNull Inventory getInventory() {
        return inventory;
    }

    public final boolean isViewer(@NotNull Player p) {
        return viewers.contains(p.getUniqueId());
    }
}