package ru.example.townbuildings.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.gui.holder.StockMenuHolder;
import ru.example.townbuildings.gui.menu.CityStockMenu;
import ru.example.townbuildings.gui.session.PageSession;
import ru.example.townbuildings.gui.session.PageSessionRegistry;

import java.util.Map;

/**
 * P12 — явная обработка ClickType в onClick.
 * C1  — при пустом курсоре обычные LEFT/RIGHT по нижнему инвентарю
 *       не отменяются.
 * E12 — onDrag/onClose через event.getView().getTopInventory().
 * H-4 — на граничных страницах клик по nav не снимает viewer: см.
 *       InventoryListener, где та же проблема и то же решение.
 */
public final class StockListener implements Listener {

    private final TownBuildingsPlugin plugin;
    private final CityStockMenu menu;
    private final PageSessionRegistry sessions;

    public StockListener(@NotNull TownBuildingsPlugin plugin,
                         @NotNull CityStockMenu menu,
                         @NotNull PageSessionRegistry sessions) {
        this.plugin = plugin;
        this.menu = menu;
        this.sessions = sessions;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(@NotNull InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof StockMenuHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }

        PageSession session = sessions.get(holder.townUuid(), holder.page());
        if (session == null) { event.setCancelled(true); return; }

        var town = plugin.towny().getTownOf(player);
        if (town == null || !town.getUUID().equals(holder.townUuid())) {
            event.setCancelled(true);
            return;
        }

        Inventory top = event.getView().getTopInventory();
        int raw = event.getRawSlot();
        int topSize = top.getSize();

        // Shift+клик из низа → попытка положить в stock. Запрещено.
        if (raw >= topSize) {
            if (event.isShiftClick()) { event.setCancelled(true); return; }
            ItemStack onCursor = event.getCursor();
            if (onCursor != null && !onCursor.getType().isAir()) {
                event.setCancelled(true);
            }
            return;
        }

        if (raw < 0) {
            event.setCancelled(true);
            return;
        }

        var action = event.getAction();
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == InventoryAction.HOTBAR_SWAP
                || action == InventoryAction.HOTBAR_MOVE_AND_READD) {
            event.setCancelled(true);
            return;
        }

        // --- Nav-кнопки ---
        // H-4 — на граничных страницах не дёргаем removeViewer до проверки,
        // иначе сессия остаётся без зрителей → sweep() её удалит → GUI «залипнет».
        if (raw == StockMenuHolder.SLOT_BACK) {
            event.setCancelled(true);
            if (holder.page() <= 1) return;
            sessions.removeViewer(session, player.getUniqueId());
            menu.switchPage(player, session, -1);
            return;
        }
        if (raw == StockMenuHolder.SLOT_FORWARD) {
            event.setCancelled(true);
            if (holder.page() >= holder.totalPages()) return;
            sessions.removeViewer(session, player.getUniqueId());
            menu.switchPage(player, session, +1);
            return;
        }

        event.setCancelled(true);
        if (!holder.isItemSlot(raw)) return;

        ClickType ct = event.getClick();
        boolean giveWhole = (ct == ClickType.LEFT || ct == ClickType.SHIFT_LEFT);
        boolean giveOne   = (ct == ClickType.RIGHT || ct == ClickType.SHIFT_RIGHT);
        if (!giveWhole && !giveOne) return;

        ItemStack inSlot = top.getItem(raw);
        if (inSlot == null || inSlot.getType().isAir()) return;

        ItemStack toGive = inSlot.clone();
        if (giveOne) toGive.setAmount(1);

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(toGive);
        int leftoverAmount = leftover.values().stream()
                .mapToInt(ItemStack::getAmount).sum();
        int actuallyGiven = toGive.getAmount() - leftoverAmount;

        if (actuallyGiven <= 0) {
            player.sendActionBar(plugin.getMessagesConfig().get("stock.no-space"));
            return;
        }

        ItemStack remaining = inSlot.clone();
        remaining.setAmount(inSlot.getAmount() - actuallyGiven);
        if (remaining.getAmount() <= 0) top.setItem(raw, null);
        else                            top.setItem(raw, remaining);
        sessions.markDirty(session);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(@NotNull InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof StockMenuHolder)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof StockMenuHolder holder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        PageSession session = sessions.get(holder.townUuid(), holder.page());
        if (session == null) return;
        sessions.removeViewer(session, player.getUniqueId());
    }
}