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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Базовое меню.
 *
 * Каждое меню наследуется и реализует:
 *  - size() — размер инвентаря
 *  - title(player) — заголовок
 *  - render(player) — перерисовка содержимого
 *  - onClick(player, slot, event) — обработка клика
 *
 * 5.3 — удалён неиспользуемый isOwnerAndViewer.
 * Этап 5 — удалены мёртвые refreshViewers() и executeAsync():
 *          в проекте не было ни одного вызова; multi-view для
 *          городских меню реализован через PageSessionRegistry.
 */
public abstract class Menu implements InventoryHolder {

    protected final TownBuildingsPlugin plugin;
    protected final Player player;
    protected Inventory inventory;

    /** Игроки, которые сейчас открыли это меню (для refresh). */
    // P23 (review) — ConcurrentHashMap.newKeySet вместо HashSet: viewers.add
    // вызывается из open(), который в этом проекте запускается и из async-
    // колбэков БД (см. CityInventoryMenu/CityStockMenu). Обычный HashSet при
    // конкурентном add не синхронизирован → риск потери записи/битого состояния.
    private final Set<java.util.UUID> viewers = ConcurrentHashMap.newKeySet();

    /** Быстрая защита от «двойного клика» при async-операциях. */
    private volatile boolean locked = false;

    protected Menu(@NotNull TownBuildingsPlugin plugin, @NotNull Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    // -------- контракт --------

    /** Размер инвентаря (кратен 9). */
    protected abstract int size();

    /** Заголовок меню. */
    protected abstract @NotNull Component title(@NotNull Player player);

    /** Заполнить инвентарь. */
    protected abstract void render(@NotNull Player player);

    /** Обработка клика. */
    public abstract void onClick(@NotNull Player player, int slot, @NotNull InventoryClickEvent event);

    // -------- открытие/перерисовка --------

    public final void open() {
        this.inventory = Bukkit.createInventory(this, size(), title(player));
        render(player);
        viewers.add(player.getUniqueId());
        player.openInventory(inventory);
    }

    /** Перерисовать в текущем открытом инвентаре. */
    public final void refresh() {
        if (inventory == null) return;
        inventory.clear();
        render(player);
    }

    // -------- блокировка на время async --------

    public final boolean isLocked() { return locked; }
    public final void lock()        { this.locked = true; }
    public final void unlock()      { this.locked = false; }

    // -------- holder --------

    @Override
    public final @NotNull Inventory getInventory() {
        return inventory;
    }

    // -------- helpers --------

    public final void close() {
        viewers.clear();
        if (player.isOnline()) player.closeInventory();
    }

    public final boolean isViewer(@NotNull Player p) {
        return viewers.contains(p.getUniqueId());
    }
}