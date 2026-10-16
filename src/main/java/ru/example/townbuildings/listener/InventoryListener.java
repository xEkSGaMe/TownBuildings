package ru.example.townbuildings.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * H-4 — на граничных страницах кнопки nav визуально неактивны
 * (navBackInactive/navForwardInactive), но клик всё равно приходит в
 * listener по raw slot 45/53. Раньше ветка отменяла событие и снимала
 * viewer ДО switchPage; switchPage возвращался no-op'ом (target вне
 * диапазона), и сессия оставалась без зрителей. Через sessionTtlMs
 * sweep() её удалял — следующий клик по верхнему инвентарю ловил
 * session == null → GUI «залипал».
 *
 * Теперь границы проверяются ДО removeViewer: клик по неактивной
 * кнопке не меняет состояние сессии.
 *
 * M-6 — transferOneStackUp: при нехватке места в верхнем инвентаре
 *       переносим СКОЛЬКО ВЛЕЗАЕТ, остаток остаётся у игрока. Раньше
 *       при remaining > emptyCapacity не двигали НИЧЕГО (шифт-клик 64 шт
 *       при 30 свободных слотах не срабатывал вообще).
 */
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
        Inventory topView = event.getView().getTopInventory();
        if (!(topView.getHolder() instanceof InventoryMenuHolder holder)) return;
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

        Inventory top = topView;
        int slot = event.getRawSlot();
        int topSize = top.getSize();

        // --- Нижний инвентарь игрока (slot >= topSize) ---
        if (slot >= topSize) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
                transferOneStackUp(player, top, holder, session, event);
            }
            return;
        }

        // --- Клик вне обоих инвентарей (rawSlot < 0) ---
        if (slot < 0) {
            event.setCancelled(true);
            return;
        }

        // --- Nav-кнопки ---
        // H-4 — на граничных страницах «Назад»/«Вперёд» неактивны.
        // Проверяем границу ДО removeViewer, иначе сессия остаётся без
        // зрителей и через TTL её сносит sweep() — GUI «залипает».
        if (slot == InventoryMenuHolder.SLOT_BACK) {
            event.setCancelled(true);
            if (holder.page() <= 1) return;
            sessions.removeViewer(session, player.getUniqueId());
            menu.switchPage(player, session, -1);
            return;
        }
        if (slot == InventoryMenuHolder.SLOT_FORWARD) {
            event.setCancelled(true);
            if (holder.page() >= holder.totalPages()) return;
            sessions.removeViewer(session, player.getUniqueId());
            menu.switchPage(player, session, +1);
            return;
        }

        // --- Item-слоты верхнего инвентаря ---
        if (holder.isItemSlot(slot)) {
            sessions.markDirty(session);
            return;
        }

        // Декоративные слоты: блокируем переносы и клики с непустым курсором.
        ItemStack onCursor = event.getCursor();
        boolean cursorBusy = onCursor != null && !onCursor.getType().isAir();
        var action = event.getAction();
        boolean transferAttempt = action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == InventoryAction.HOTBAR_SWAP
                || action == InventoryAction.HOTBAR_MOVE_AND_READD;
        if (cursorBusy || transferAttempt) {
            event.setCancelled(true);
        }
    }

    /**
     * M-6 — перенос «сколько влезает».
     *
     * Алгоритм:
     *  1. mergePlan: для каждого верхнего item-слота с таким же типом
     *     считаем, сколько можно доложить до maxStack. remaining уменьшается.
     *  2. Считаем capacity пустых item-слотов.
     *  3. remaining = min(remaining, capacity) — clamp переносимого.
     *  4. Применяем mergePlan, затем раскладываем остаток по пустым слотам.
     *  5. Уменьшаем исходный стек на перенесённое (не обнуляем безусловно).
     */
    private void transferOneStackUp(@NotNull Player player,
                                    @NotNull Inventory top,
                                    @NotNull InventoryMenuHolder holder,
                                    @NotNull PageSession session,
                                    @NotNull InventoryClickEvent event) {
        ItemStack stack = event.getCurrentItem();
        if (stack == null || stack.getType().isAir()) return;

        final int topSize = top.getSize();
        final int maxStack = stack.getMaxStackSize();

        // 1. merge с существующими стеками того же типа.
        Map<Integer, ItemStack> planMerge = new LinkedHashMap<>();
        int remaining = stack.getAmount();

        for (int i = 0; i < topSize && remaining > 0; i++) {
            if (!holder.isItemSlot(i)) continue;
            ItemStack at = top.getItem(i);
            if (at == null || at.getType().isAir()) continue;
            if (!at.isSimilar(stack)) continue;
            int free = maxStack - at.getAmount();
            if (free <= 0) continue;

            int add = Math.min(free, remaining);
            ItemStack updated = at.clone();
            updated.setAmount(at.getAmount() + add);
            planMerge.put(i, updated);
            remaining -= add;
        }

        // 2. capacity пустых item-слотов (исключая те, что уже в planMerge).
        int capacity = 0;
        for (int i = 0; i < topSize; i++) {
            if (!holder.isItemSlot(i)) continue;
            if (planMerge.containsKey(i)) continue;
            ItemStack at = top.getItem(i);
            if (at == null || at.getType().isAir()) capacity += maxStack;
        }

        // 3. clamp переносимого до capacity.
        if (remaining > capacity) remaining = capacity;

        // Ничего не переносим — выходим (ни merge, ни раскладки).
        if (remaining <= 0 && planMerge.isEmpty()) return;

        // 4. Применяем merge.
        for (Map.Entry<Integer, ItemStack> e : planMerge.entrySet()) {
            top.setItem(e.getKey(), e.getValue());
        }

        // 5. Раскладываем оставшееся по пустым слотам.
        int toPlace = remaining;
        for (int i = 0; i < topSize && toPlace > 0; i++) {
            if (!holder.isItemSlot(i)) continue;
            if (planMerge.containsKey(i)) continue;
            ItemStack at = top.getItem(i);
            if (at != null && !at.getType().isAir()) continue;

            int add = Math.min(maxStack, toPlace);
            ItemStack chunk = stack.clone();
            chunk.setAmount(add);
            top.setItem(i, chunk);
            toPlace -= add;
        }

        // 6. Уменьшаем исходный стек на ФАКТИЧЕСКИ перенесённое.
        // moved = originalAmount - remaining (remaining уже clamped).
        int moved = stack.getAmount() - remaining;
        if (moved <= 0) return;

        if (remaining <= 0) {
            event.setCurrentItem(null);
        } else {
            ItemStack shorter = stack.clone();
            shorter.setAmount(remaining);
            event.setCurrentItem(shorter);
        }
        sessions.markDirty(session);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(@NotNull InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof InventoryMenuHolder holder)) return;
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