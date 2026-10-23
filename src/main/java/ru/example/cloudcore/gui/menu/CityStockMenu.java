package ru.example.cloudcore.gui.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.database.model.BuildingData;
import ru.example.cloudcore.gui.holder.StockMenuHolder;
import ru.example.cloudcore.gui.session.PageSession;
import ru.example.cloudcore.gui.session.PageSessionRegistry;
import ru.example.cloudcore.service.StockService;
import ru.example.cloudcore.CloudCorePlugin;

import java.util.List;
import java.util.Map;

import ru.example.cloudcore.CloudCorePlugin;
/**
 * M-4 — вся общая логика в AbstractPaginatedMenu; здесь только специфика.
 */
public final class CityStockMenu extends AbstractPaginatedMenu {

    public CityStockMenu(@NotNull CloudCorePlugin plugin,
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