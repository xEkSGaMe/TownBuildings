package ru.example.townbuildings.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.gui.holder.InventoryMenuHolder;
import ru.example.townbuildings.gui.menu.CityInventoryMenu;
import ru.example.townbuildings.gui.session.PageSession;
import ru.example.townbuildings.gui.session.PageSessionRegistry;

public final class InventoryListener implements Listener {

    private final TownBuildingsPlugin plugin;
    private final CityInventoryMenu menu;
    private final PageSessionRegistry sessions;

    public InventoryListener(@NotNull TownBuildingsPlugin plugin,
                             @NotNull CityInventoryMenu menu,
                             @NotNull PageSessionRegistry sessions) {
        this.plugin = plugin;
        this.menu = menu;
        this.sessions = sessions;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(@NotNull InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof InventoryMenuHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        PageSession session = sessions.get(holder.townUuid(), holder.page());
        if (session == null) { event.setCancelled(true); return; }

        var town = plugin.towny().getTownOf(player);
        if (town == null || !town.getUUID().equals(holder.townUuid())) {
            event.setCancelled(true);
            return;
        }

        Inventory top = event.getView().getTopInventory();
        int slot = event.getRawSlot();
        int topSize = top.getSize();

        // --- Нижний инвентарь игрока (slot >= topSize) ---
        if (slot >= topSize) {
            // Shift+клик из низа: vanilla попытается положить предмет
            // в первый попавшийся слот верхнего, включая nav 45/53.
            // Делаем вручную, кладём только в item-слоты.
            if (event.isShiftClick()) {
                event.setCancelled(true);
                transferOneStackUp(player, top, holder, session, event);
            }
            return;
        }

        // --- Клик вне обоих инвентарей (rawSlot < 0) ---
        if (slot < 0) {
            if (event.isShiftClick()) event.setCancelled(true);
            return;
        }

        // --- Nav-кнопки ---
        if (slot == InventoryMenuHolder.SLOT_BACK) {
            event.setCancelled(true);
            sessions.removeViewer(session, player.getUniqueId());
            menu.switchPage(player, session, -1);
            return;
        }
        if (slot == InventoryMenuHolder.SLOT_FORWARD) {
            event.setCancelled(true);
            sessions.removeViewer(session, player.getUniqueId());
            menu.switchPage(player, session, +1);
            return;
        }

        // --- Item-слоты верхнего инвентаря ---
        if (holder.isItemSlot(slot)) {
            // Shift+клик из верха: vanilla перекладывает в нижний.
            // Это безопасно — nav-слоты не участвуют. Разрешаем.
            sessions.markDirty(session);
        }
    }

    /**
     * Ручной перенос стака из инвентаря игрока в верхний инвентарь,
     * минуя nav-слоты 45/53. Vanilla Bukkit сам не умеет — он льёт
     * в первый свободный слот, включая nav-стрелки.
     *
     * 5.2 — после мутации стака явно вызываем top.setItem(i, updated);
     *       не полагаемся на in-place семантику Inventory#getItem, которая
     *       не гарантирована API.
     */
    private void transferOneStackUp(@NotNull Player player,
                                    @NotNull Inventory top,
                                    @NotNull InventoryMenuHolder holder,
                                    @NotNull PageSession session,
                                    @NotNull InventoryClickEvent event) {
        ItemStack stack = event.getCurrentItem();
        if (stack == null || stack.getType().isAir()) return;

        ItemStack toPlace = stack.clone();
        int topSize = top.getSize();

        // 1. Доложить в существующий стак.
        for (int i = 0; i < topSize; i++) {
            if (!holder.isItemSlot(i)) continue;
            ItemStack at = top.getItem(i);
            if (at == null || at.getType().isAir()) continue;
            if (!at.isSimilar(toPlace)) continue;
            int max = at.getMaxStackSize();
            int free = max - at.getAmount();
            if (free <= 0) continue;
            int add = Math.min(free, toPlace.getAmount());

            ItemStack updated = at.clone();
            updated.setAmount(at.getAmount() + add);
            // 5.2 — явный setItem; не полагаемся на in-place мутацию.
            top.setItem(i, updated);

            toPlace.setAmount(toPlace.getAmount() - add);
            if (toPlace.getAmount() <= 0) {
                event.setCurrentItem(null);
                sessions.markDirty(session);
                return;
            }
        }

        // 2. Занять пустой item-слот.
        for (int i = 0; i < topSize; i++) {
            if (!holder.isItemSlot(i)) continue;
            ItemStack at = top.getItem(i);
            if (at != null && !at.getType().isAir()) continue;
            top.setItem(i, toPlace);
            event.setCurrentItem(null);
            sessions.markDirty(session);
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(@NotNull InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof InventoryMenuHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        PageSession session = sessions.get(holder.townUuid(), holder.page());
        if (session == null) { event.setCancelled(true); return; }

        var town = plugin.towny().getTownOf(player);
        if (town == null || !town.getUUID().equals(holder.townUuid())) {
            event.setCancelled(true);
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        boolean touchesTopItemSlot = false;

        for (int slot : event.getRawSlots()) {
            if (slot < 0) { event.setCancelled(true); return; }
            if (slot >= topSize) continue;
            if (!holder.isItemSlot(slot)) {
                event.setCancelled(true);
                return;
            }
            touchesTopItemSlot = true;
        }

        if (touchesTopItemSlot) sessions.markDirty(session);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof InventoryMenuHolder holder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        PageSession session = sessions.get(holder.townUuid(), holder.page());
        if (session == null) return;
        sessions.removeViewer(session, player.getUniqueId());
    }
}