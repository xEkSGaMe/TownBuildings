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
import ru.example.townbuildings.gui.holder.PageHolder;
import ru.example.townbuildings.gui.session.PageSession;
import ru.example.townbuildings.gui.session.PageSessionBusyException;
import ru.example.townbuildings.gui.session.PageSessionRegistry;
import ru.example.townbuildings.gui.util.GuiUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M-4 — общая логика страничных меню (/t inventory и /t stock).
 *
 * Раньше CityInventoryMenu и CityStockMenu содержали по 150 строк почти
 * идентичного кода (openFirstPage / switchPage / open / makeNav /
 * renderNavigation) с единственным различием в populate() и
 * resolveTotalPages(). Копипаста → любая правка в одной ветке легко
 * расходилась с другой.
 *
 * C2 — grace-период после invalidateTown обрабатывается один раз здесь:
 *      retry через 40 тиков (2 сек) при PageSessionBusyException, вместо
 *      «Внутренней ошибки». Проверяем и ex, и ex.getCause() (при прямом
 *      completeExceptionally ex приходит необёрнутым).
 */
public abstract class AbstractPaginatedMenu {

    /** C2 — задержка retry при grace-периоде (тики). 40 тиков = 2 сек. */
    protected static final long RETRY_DELAY_TICKS = 40L;

    protected final TownBuildingsPlugin plugin;
    protected final PageSessionRegistry sessions;

    private final ItemStack navBackActive;
    private final ItemStack navBackInactive;
    private final ItemStack navForwardActive;
    private final ItemStack navForwardInactive;

    protected AbstractPaginatedMenu(@NotNull TownBuildingsPlugin plugin,
                                    @NotNull PageSessionRegistry sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
        var mc = plugin.getMessagesConfig();
        this.navBackActive      = makeNav(Material.ARROW, mc.get("gui.nav.back-active"));
        this.navBackInactive    = makeNav(Material.ARROW, mc.get("gui.nav.back-inactive"));
        this.navForwardActive   = makeNav(Material.ARROW, mc.get("gui.nav.forward-active"));
        this.navForwardInactive = makeNav(Material.ARROW, mc.get("gui.nav.forward-inactive"));
    }

    protected static ItemStack makeNav(Material mat, Component name) {
        ItemStack is = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) { meta.displayName(name); is.setItemMeta(meta); }
        return is;
    }

    // ============================================================
    //   Абстрактное — реализуется в наследниках
    // ============================================================

    /** Заполнить инвентарь страницы. */
    protected abstract void populate(@NotNull PageSession session,
                                     @NotNull Map<Integer, byte[]> items);

    /** Сколько всего страниц у города (зависит от уровня warehouse-здания). */
    protected abstract int resolveTotalPages(@NotNull List<BuildingData> buildings);

    /** Логировать ошибку в контексте конкретного меню. */
    protected abstract void logError(@NotNull String msg);

    // ============================================================
    //   Публичное API
    // ============================================================

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

    // ============================================================
    //   Внутреннее
    // ============================================================

    private void open(@NotNull Player player, @NotNull UUID townUuid,
                      @NotNull String townName, int page, int totalPages) {
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        // C2 — final-копии для лямбды retry (page/totalPages выше
        // переприсваиваются и потому не effectively-final).
        final int finalPage = page;
        final int finalTotalPages = totalPages;

        sessions.getOrCreate(townUuid, finalPage, finalTotalPages, townName,
                        (session, items) -> populate(session, items))
                .whenComplete((session, ex) -> {
                    if (ex != null || session == null) {
                        // C2 — grace-период: тихо повторяем через 2 сек.
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
                        // P8 (review) — проверка города синхронно с openInventory.
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

    protected void renderNavigation(@NotNull PageHolder holder, @NotNull Inventory inv) {
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

        inv.setItem(PageHolder.SLOT_BACK, back);
        inv.setItem(PageHolder.SLOT_FORWARD, forward);
    }
}