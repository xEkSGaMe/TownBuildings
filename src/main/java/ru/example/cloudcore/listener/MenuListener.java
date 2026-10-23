package ru.example.cloudcore.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.gui.Menu;
import ru.example.cloudcore.gui.MenuManager;

import ru.example.cloudcore.CloudCorePlugin;
/**
 * Маршрутизация кликов по нашим меню (holder == Menu).
 * InventoryMenuHolder и StockMenuHolder обрабатываются своими слушателями.
 *
 * E12 — onDrag/onClose используют event.getView().getTopInventory(),
 *       как onClick: корректно на Folia и при перехватах openInventory.
 */
public final class MenuListener implements Listener {

    private final CloudCorePlugin plugin;
    private final MenuManager menuManager;

    public MenuListener(@NotNull CloudCorePlugin plugin, @NotNull MenuManager menuManager) {
        this.plugin = plugin;
        this.menuManager = menuManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(@NotNull InventoryClickEvent event) {
        // P23 — top-inventory по view, а не getInventory(): корректно на Folia
        // и при перехватах открытия сторонними плагинами.
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (!menu.isViewer(player)) {
            event.setCancelled(true);
            return;
        }

        try {
            // P23 — предметы в меню только декоративные: любые переносы
            // (shift-клик, number-key, collect-to-cursor, drop-клики)
            // отменяются. Раньше отмена была только внутри отдельных
            // обработчиков слотов — клик мимо «кнопки» мог вынести стек
            // из меню в инвентарь игрока.
            final int rawSlot = event.getRawSlot();
            final boolean topClick = rawSlot >= 0 && rawSlot < event.getView().getTopInventory().getSize();
            final var action = event.getAction();
            final boolean transferAttempt = !topClick
                    || action == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY
                    || action == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR
                    || action == org.bukkit.event.inventory.InventoryAction.HOTBAR_MOVE_AND_READD
                    || action == org.bukkit.event.inventory.InventoryAction.HOTBAR_SWAP;
            if (transferAttempt) {
                event.setCancelled(true);
            }
            menu.onClick(player, rawSlot, event);
        } catch (Throwable t) {
            plugin.getLogger().severe("[Menu] Ошибка onClick: " + t.getMessage());
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(@NotNull InventoryDragEvent event) {
        // ★ E12 — top-inventory через view, как в onClick.
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu)) return;
        event.setCancelled(true);
    }

    // 1.6 — снимаем регистрацию только если менеджер всё ещё знает про это меню
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(@NotNull InventoryCloseEvent event) {
        // ★ E12 — top-inventory через view, как в onClick.
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        menuManager.unregisterIfCurrent(player, menu);
    }
}