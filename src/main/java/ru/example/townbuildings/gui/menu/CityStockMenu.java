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
import ru.example.townbuildings.database.model.BuildingData;
import ru.example.townbuildings.gui.holder.StockMenuHolder;
import ru.example.townbuildings.gui.session.PageSession;
import ru.example.townbuildings.gui.session.PageSessionBusyException;
import ru.example.townbuildings.gui.session.PageSessionRegistry;
import ru.example.townbuildings.gui.util.GuiUtils;
import ru.example.townbuildings.service.StockService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CityStockMenu {

    /** ★ C2 — задержка перед retry при grace-периоде (тики). */
    private static final long RETRY_DELAY_TICKS = 40L;

    private final TownBuildingsPlugin plugin;
    private final PageSessionRegistry sessions;

    private final ItemStack navBackActive;
    private final ItemStack navBackInactive;
    private final ItemStack navForwardActive;
    private final ItemStack navForwardInactive;

    public CityStockMenu(@NotNull TownBuildingsPlugin plugin,
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

        // ★ C2 — final-копии для лямбды retry.
        final int finalPage = page;
        final int finalTotalPages = totalPages;

        sessions.getOrCreate(townUuid, finalPage, finalTotalPages, townName,
                        (session, items) -> populate(session, items))
                .whenComplete((session, ex) -> {
                    if (ex != null || session == null) {
                        // ★ C2 — см. CityInventoryMenu: grace-период не должен
                        // выглядеть как фатальная ошибка. Проверяем и ex, и
                        // ex.getCause() (на случай обёртки CompletionException).
                        Throwable cause = (ex != null) ? ex.getCause() : null;
                        if (ex instanceof PageSessionBusyException
                                || cause instanceof PageSessionBusyException) {
                            if (player.isOnline()) {
                                Bukkit.getScheduler().runTaskLater(plugin,
                                        () -> {
                                            if (player.isOnline()) {
                                                open(player, townUuid, townName,
                                                        finalPage, finalTotalPages);
                                            }
                                        }, RETRY_DELAY_TICKS);
                            }
                            return;
                        }
                        plugin.getLogger().severe("[Stock] open: "
                                + (ex != null ? ex.getMessage() : "null session"));
                        if (player.isOnline()) {
                            player.sendMessage(plugin.getMessagesConfig().get("general.internal-error"));
                        }
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        // P8 (review) — проверка города синхронно с openInventory.
                        var town = plugin.towny().getTownOf(player);
                        if (town == null || !town.getUUID().equals(townUuid)) {
                            player.sendMessage(
                                    plugin.getMessagesConfig().get("general.internal-error"));
                            return;
                        }
                        renderNavigation((StockMenuHolder) session.holder(), session.inventory());
                        sessions.addViewer(session, player.getUniqueId());
                        player.openInventory(session.inventory());
                    });
                });
    }

    private void populate(@NotNull PageSession session, @NotNull Map<Integer, byte[]> items) {
        StockMenuHolder holder = (StockMenuHolder) session.holder();
        Inventory inv = session.inventory();
        holder.setInitial(items);
        for (var e : items.entrySet()) {
            try {
                ItemStack is = ItemStack.deserializeBytes(e.getValue());
                if (is != null && !is.getType().isAir() && holder.isItemSlot(e.getKey())) {
                    inv.setItem(e.getKey(), is);
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("[Stock] Битый слот " + e.getKey()
                        + " города " + holder.townUuid() + ": " + t.getMessage());
            }
        }
    }

    private int resolveTotalPages(@NotNull List<BuildingData> buildings) {
        int level = buildings.stream()
                .filter(b -> b.buildingId().equals(StockService.WAREHOUSE_STOCK_BUILDING_ID))
                .mapToInt(BuildingData::level)
                .findFirst().orElse(0);
        return 1 + Math.max(0, Math.min(3, level));
    }

    public void renderNavigation(@NotNull StockMenuHolder holder, @NotNull Inventory inv) {
        var mc = plugin.getMessagesConfig();
        Map<String, String> ph = Map.of(
                "page", Integer.toString(holder.page()),
                "pages", Integer.toString(holder.totalPages())
        );

        boolean canBack = holder.page() > 1;
        boolean canForward = holder.page() < holder.totalPages();

        ItemStack back = (canBack ? navBackActive : navBackInactive).clone();
        ItemStack forward = (canForward ? navForwardActive : navForwardInactive).clone();

        GuiUtils.appendLore(back, Component.empty(), mc.get("gui.nav.back-lore", ph));
        GuiUtils.appendLore(forward, Component.empty(), mc.get("gui.nav.forward-lore", ph));

        inv.setItem(StockMenuHolder.SLOT_BACK, back);
        inv.setItem(StockMenuHolder.SLOT_FORWARD, forward);
    }
}