package ru.example.cloudcore.gui.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.database.model.BuildingData;
import ru.example.cloudcore.gui.holder.PageHolder;
import ru.example.cloudcore.gui.session.PageSession;
import ru.example.cloudcore.gui.session.PageSessionBusyException;
import ru.example.cloudcore.gui.session.PageSessionRegistry;
import ru.example.cloudcore.gui.util.GuiUtils;
import ru.example.cloudcore.CloudCorePlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import ru.example.cloudcore.CloudCorePlugin;
/**
 * M-4 — общая логика страничных меню.
 * M10 — nav-иконки строятся в renderNavigation каждый раз, чтобы
 *       /tadmin reload подхватывал новый messages.yml без переоткрытия.
 */
public abstract class AbstractPaginatedMenu {

    protected static final long RETRY_DELAY_TICKS = 40L;

    protected final CloudCorePlugin plugin;
    protected final PageSessionRegistry sessions;

    protected AbstractPaginatedMenu(@NotNull CloudCorePlugin plugin,
                                    @NotNull PageSessionRegistry sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
        // ★ M10 — иконки больше не строятся в конструкторе.
    }

    protected static ItemStack makeNav(Material mat, Component name) {
        ItemStack is = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) { meta.displayName(name); is.setItemMeta(meta); }
        return is;
    }

    protected abstract void populate(@NotNull PageSession session,
                                     @NotNull Map<Integer, byte[]> items);

    protected abstract int resolveTotalPages(@NotNull List<BuildingData> buildings);

    protected abstract void logError(@NotNull String msg);

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

        final int finalPage = page;
        final int finalTotalPages = totalPages;

        sessions.getOrCreate(townUuid, finalPage, finalTotalPages, townName,
                        (session, items) -> populate(session, items))
                .whenComplete((session, ex) -> {
                    if (ex != null || session == null) {
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
                        logError("open: " + (ex != null ? ex.getMessage() : "null session"));
                        if (player.isOnline()) {
                            player.sendMessage(plugin.getMessagesConfig()
                                    .get("general.internal-error"));
                        }
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        var town = plugin.towny().getTownOf(player);
                        if (town == null || !town.getUUID().equals(townUuid)) {
                            player.sendMessage(
                                    plugin.getMessagesConfig().get("general.internal-error"));
                            return;
                        }
                        renderNavigation(session.holder(), session.inventory());
                        sessions.addViewer(session, player.getUniqueId());
                        player.openInventory(session.inventory());
                    });
                });
    }

    /**
     * ★ M10 — пересобираем иконки каждый render: после /tadmin reload
     *         messages.yml уже новый, а старая иконка «Назад» — ещё старая.
     */
    protected void renderNavigation(@NotNull PageHolder holder, @NotNull Inventory inv) {
        var mc = plugin.getMessagesConfig();
        Map<String, String> ph = Map.of(
                "page", Integer.toString(holder.page()),
                "pages", Integer.toString(holder.totalPages())
        );

        boolean canBack = holder.page() > 1;
        boolean canForward = holder.page() < holder.totalPages();

        ItemStack back = makeNav(Material.ARROW,
                mc.get(canBack ? "gui.nav.back-active" : "gui.nav.back-inactive"));
        ItemStack forward = makeNav(Material.ARROW,
                mc.get(canForward ? "gui.nav.forward-active" : "gui.nav.forward-inactive"));

        GuiUtils.appendLore(back, Component.empty(), mc.get("gui.nav.back-lore", ph));
        GuiUtils.appendLore(forward, Component.empty(), mc.get("gui.nav.forward-lore", ph));

        inv.setItem(PageHolder.SLOT_BACK, back);
        inv.setItem(PageHolder.SLOT_FORWARD, forward);
    }
}