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

// P1: импорты для плана merge'ов (LinkedHashMap — детерминированный порядок).
import java.util.LinkedHashMap;
import java.util.Map;

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
        // P8 (review) — определяем «наш» верхний инвентарь через view, а не
        // через event.getInventory(): последний в некоторых кейтах (Folia /
        // кастомные плагины-«перехваты» openInventory) возвращает нижний
        // инвентарь игрока. Тогда holder чужого GUI не матчится вообще и
        // защита ниже не срабатывает.
        Inventory topView = event.getView().getTopInventory();
        if (!(topView.getHolder() instanceof InventoryMenuHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

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
            // P8 (review) — клик «в пустоту» с предметом на курсоре = drop
            // предмета в мир из GUI-сессии, минуя diff/учёт. Отменяем.
            ItemStack onCursor = event.getCursor();
            if (event.isShiftClick() || (onCursor != null && !onCursor.getType().isAir())) {
                event.setCancelled(true);
            }
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
            return;
        }

        // P23 (review) — клики по декоративным слотам верха (заполнитель
        // между item-слотами и стрелками): раньше событие не отменялось,
        // и игрок с предметом на курсоре мог left/right-кликом «уронить»
        // его в мир мимо diff/учёта склада. Отменяем любой клик по
        // не-item слоту, если на курсоре что-то есть, а также shift/Q-
        // дроп и transfer-экшены (COLLECT_TO_CURSOR сюда не попадает —
        // он приходит с rawSlot из нижнего инвентаря, обработан выше).
        ItemStack onCursor = event.getCursor();
        boolean cursorBusy = onCursor != null && !onCursor.getType().isAir();
        var action = event.getAction();
        boolean transferAttempt = action == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == org.bukkit.event.inventory.InventoryAction.HOTBAR_SWAP
                || action == org.bukkit.event.inventory.InventoryAction.HOTBAR_MOVE_AND_READD;
        if (cursorBusy || transferAttempt) {
            event.setCancelled(true);
        }
    }

    /**
     * Ручной перенос стака из инвентаря игрока в верхний инвентарь,
     * минуя nav-слоты 45/53. Vanilla Bukkit сам не умеет — он льёт
     * в первый свободный слот, включая nav-стрелки.
     *
     * P1 — переписано по «Варианту A»: сначала строим полный план
     * (merge в существующие стаки + раскладка по пустым), применяем
     * ТОЛЬКО если весь стак помещается целиком. Иначе — не делаем
     * ничего. Событие уже отменено вызывающим кодом, стак остаётся
     * у игрока (ванильное поведение при переполненном сундуке).
     *
     * Раньше применяли мутации «по ходу»: если после merge место в
     * пустых слотах кончалось — возвращались, оставив в верхнем
     * инвентаре уже залитый объём, а стак у игрока не уменьшался →
     * бесконечный дюп через shift-клик.
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

        // Фаза 1 (план): сколько влезет в существующие стаки того же типа.
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

        // Фаза 2 (план): считаем свободную вместимость пустых item-слотов.
        int emptyCapacity = 0;
        for (int i = 0; i < topSize; i++) {
            if (!holder.isItemSlot(i)) continue;
            if (planMerge.containsKey(i)) continue;
            ItemStack at = top.getItem(i);
            if (at == null || at.getType().isAir()) emptyCapacity += maxStack;
        }

        if (remaining > emptyCapacity) {
            // Не весь стак влезает — ничего не применяем (P1).
            // Стак остаётся у игрока, как при ванильном «нет места».
            return;
        }

        // Фаза 3 (применение): всё влезло — атомарно применяем план.
        for (Map.Entry<Integer, ItemStack> e : planMerge.entrySet()) {
            top.setItem(e.getKey(), e.getValue());
        }

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

        event.setCurrentItem(null);
        sessions.markDirty(session);
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