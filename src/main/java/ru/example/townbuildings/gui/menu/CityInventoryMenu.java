package ru.example.townbuildings.gui.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.util.PageCapacity;
import ru.example.townbuildings.database.model.BuildingData;
import ru.example.townbuildings.gui.holder.InventoryMenuHolder;
import ru.example.townbuildings.gui.session.PageSession;
import ru.example.townbuildings.gui.session.PageSessionRegistry;
import ru.example.townbuildings.gui.util.GuiUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CityInventoryMenu {

    public static final String WAREHOUSE_BUILDING_ID = "warehouse_inv";

    private final TownBuildingsPlugin plugin;
    private final PageSessionRegistry sessions;

    private final ItemStack navBackActive;
    private final ItemStack navBackInactive;
    private final ItemStack navForwardActive;
    private final ItemStack navForwardInactive;

    public CityInventoryMenu(@NotNull TownBuildingsPlugin plugin,
                             @NotNull PageSessionRegistry sessions) {
        this.plugin = plugin;
        this.sessions = sessions;

        var mc = plugin.getMessagesConfig();
        this.navBackActive      = makeNav(Material.ARROW, mc.get("gui.nav.back-active"));
        this.navBackInactive    = makeNav(Material.ARROW, mc.get("gui.nav.back-inactive"));
        this.navForwardActive   = makeNav(Material.ARROW, mc.get("gui.nav.forward-active"));
        this.navForwardInactive = makeNav(Material.ARROW, mc.get("gui.nav.forward-inactive"));
    }

    private static ItemStack makeNav(Material mat, Component name) {
        ItemStack is = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) { meta.displayName(name); is.setItemMeta(meta); }
        return is;
    }

    public void openFirstPage(@NotNull Player player, @NotNull UUID townUuid,
                              @NotNull String townName) {
        plugin.townService().getBuildings(townUuid).whenComplete((buildings, ex) -> {
            if (ex != null || buildings == null) {
                player.sendMessage(plugin.getMessagesConfig().get("general.internal-error"));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                int totalPages = resolveTotalPages(buildings);
                open(player, townUuid, townName, 1, totalPages);
            });
        });
    }

    public void switchPage(@NotNull Player player, @NotNull PageSession current, int delta) {
        var h = current.holder();
        int target = h.page() + delta;
        if (target < 1 || target > h.totalPages()) return;
        open(player, h.townUuid(), h.townName(), target, h.totalPages());
    }

    private void open(@NotNull Player player, @NotNull UUID townUuid,
                      @NotNull String townName, int page, int totalPages) {
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        sessions.getOrCreate(townUuid, page, totalPages, townName,
                        (session, items) -> populate(session, items))
                .whenComplete((session, ex) -> {
                    if (ex != null || session == null) {
                        plugin.getLogger().severe("[Inv] open: "
                                + (ex != null ? ex.getMessage() : "null session"));
                        if (player.isOnline()) {
                            player.sendMessage(plugin.getMessagesConfig().get("general.internal-error"));
                        }
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        renderNavigation((InventoryMenuHolder) session.holder(), session.inventory());
                        sessions.addViewer(session, player.getUniqueId());
                        player.openInventory(session.inventory());
                    });
                });
    }

    private void populate(@NotNull PageSession session, @NotNull Map<Integer, byte[]> items) {
        InventoryMenuHolder holder = (InventoryMenuHolder) session.holder();
        Inventory inv = session.inventory();

        ItemStack[] snapshot = new ItemStack[InventoryMenuHolder.INVENTORY_SIZE];
        for (var e : items.entrySet()) {
            if (!holder.isItemSlot(e.getKey())) continue;
            try {
                ItemStack is = ItemStack.deserializeBytes(e.getValue());
                if (is != null && !is.getType().isAir()) snapshot[e.getKey()] = is;
            } catch (Throwable t) {
                plugin.getLogger().warning("[Inv] Битый слот " + e.getKey()
                        + " города " + holder.townUuid() + ": " + t.getMessage());
            }
        }
        holder.setInitial(snapshot);
        for (int i = 0; i < snapshot.length; i++) {
            if (snapshot[i] != null) inv.setItem(i, snapshot[i]);
        }
    }

    private int resolveTotalPages(@NotNull List<BuildingData> buildings) {
        // Гигиена — единая формула в PageCapacity (было дублирование с CityStockMenu)
        return PageCapacity.pagesForLevel(
                PageCapacity.warehouseLevel(buildings, WAREHOUSE_BUILDING_ID));
    }

    public void renderNavigation(@NotNull InventoryMenuHolder holder, @NotNull Inventory inv) {
        var mc = plugin.getMessagesConfig();
        Map<String, String> ph = Map.of(
                "page", Integer.toString(holder.page()),
                "pages", Integer.toString(holder.totalPages())
        );

        boolean canBack = holder.page() > 1;
        boolean canForward = holder.page() < holder.totalPages();

        ItemStack back = (canBack ? navBackActive : navBackInactive).clone();
        ItemStack forward = (canForward ? navForwardActive : navForwardInactive).clone();

        // ★ Добавляем номер страницы в лор.
        GuiUtils.appendLore(back, Component.empty(), mc.get("gui.nav.back-lore", ph));
        GuiUtils.appendLore(forward, Component.empty(), mc.get("gui.nav.forward-lore", ph));

        inv.setItem(InventoryMenuHolder.SLOT_BACK, back);
        inv.setItem(InventoryMenuHolder.SLOT_FORWARD, forward);
    }
}