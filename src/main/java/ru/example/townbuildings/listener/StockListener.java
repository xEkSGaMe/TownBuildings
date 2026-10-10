package ru.example.townbuildings.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
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
 * P12 — явная обработка ClickType в onClick: LEFT/SHIFT_LEFT → весь стак,
 * RIGHT/SHIFT_RIGHT → 1 шт, всё остальное (NUMBER_KEY, DROP, CONTROL_DROP,
 * DOUBLE_CLICK, WINDOW_BORDER_*) — игнорируем.
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

        // Shift+клик из низа → попытка положить в stock. Запрещено:
        // stock только на выдачу.
        if (raw >= topSize) {
            // P8 (review) — раньше отменялся только shift-клик; обычный клик
            // по НИЖНЕМУ инвентарю при зажатом на курсоре предмете позволял
            // «выронить» его (collectToCursor + клик = drop) и подобрать
            // обратно, минуя весь учёт склада. Отменяем любой клик с
            // предметом на курсоре и left/right-click по нижним слотам.
            if (event.isShiftClick()) { event.setCancelled(true); return; }
            ItemStack onCursor = event.getCursor();
            if (onCursor != null && !onCursor.getType().isAir()) {
                event.setCancelled(true);
            } else if (event.getClick() == ClickType.LEFT
                    || event.getClick() == ClickType.RIGHT) {
                event.setCancelled(true);
            }
            return;
        }

        if (raw < 0) {
            // P8 (review) — клик «в пустоту» с предметом на курсоре = drop
            // в мир, минуя учёт склада. Отменяем.
            ItemStack onCursor = event.getCursor();
            if (onCursor != null && !onCursor.getType().isAir()) {
                event.setCancelled(true);
            }
            return;
        }

        // Nav-кнопки.
        if (raw == StockMenuHolder.SLOT_BACK) {
            event.setCancelled(true);
            sessions.removeViewer(session, player.getUniqueId());
            menu.switchPage(player, session, -1);
            return;
        }
        if (raw == StockMenuHolder.SLOT_FORWARD) {
            event.setCancelled(true);
            sessions.removeViewer(session, player.getUniqueId());
            menu.switchPage(player, session, +1);
            return;
        }

        event.setCancelled(true);
        if (!holder.isItemSlot(raw)) return;

        // P12 — явная обработка только «нормальных» кликов. Раньше
        // NUMBER_KEY (нажатие 1..9), DROP (Q), CONTROL_DROP и DOUBLE_CLICK
        // попадали в ветку «не wholeStack» и молча выдавали 1 шт — игрок
        // не ждал такого от клавиши Q или перекладывания цифрой. Теперь
        // такие клики просто игнорируются.
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
        if (!(event.getInventory().getHolder() instanceof StockMenuHolder)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof StockMenuHolder holder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        PageSession session = sessions.get(holder.townUuid(), holder.page());
        if (session == null) return;
        sessions.removeViewer(session, player.getUniqueId());
    }
}