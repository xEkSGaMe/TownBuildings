package ru.example.townbuildings.gui.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.database.model.BuildingData;
import ru.example.townbuildings.gui.holder.StockMenuHolder;
import ru.example.townbuildings.gui.session.PageSession;
import ru.example.townbuildings.gui.session.PageSessionRegistry;
import ru.example.townbuildings.service.StockService;

import java.util.List;
import java.util.Map;

/**
 * M-4 — вся общая логика в AbstractPaginatedMenu; здесь только специфика.
 */
public final class CityStockMenu extends AbstractPaginatedMenu {

    public CityStockMenu(@NotNull TownBuildingsPlugin plugin,
                         @NotNull PageSessionRegistry sessions) {
        super(plugin, sessions);
    }

    @Override
    protected void populate(@NotNull PageSession session, @NotNull Map<Integer, byte[]> items) {
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

    @Override
    protected int resolveTotalPages(@NotNull List<BuildingData> buildings) {
        int level = buildings.stream()
                .filter(b -> b.buildingId().equals(StockService.WAREHOUSE_STOCK_BUILDING_ID))
                .mapToInt(BuildingData::level)
                .findFirst().orElse(0);
        return 1 + Math.max(0, Math.min(3, level));
    }

    @Override
    protected void logError(@NotNull String msg) {
        plugin.getLogger().severe("[Stock] " + msg);
    }
}