package ru.example.townbuildings.gui.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.database.model.BuildingData;
import ru.example.townbuildings.gui.holder.InventoryMenuHolder;
import ru.example.townbuildings.gui.session.PageSession;
import ru.example.townbuildings.gui.session.PageSessionRegistry;

import java.util.List;
import java.util.Map;

/**
 * M-4 — вся общая логика в AbstractPaginatedMenu; здесь только специфика:
 * populate() и resolveTotalPages().
 */
public final class CityInventoryMenu extends AbstractPaginatedMenu {

    public static final String WAREHOUSE_BUILDING_ID = "warehouse_inv";

    public CityInventoryMenu(@NotNull TownBuildingsPlugin plugin,
                             @NotNull PageSessionRegistry sessions) {
        super(plugin, sessions);
    }

    @Override
    protected void populate(@NotNull PageSession session, @NotNull Map<Integer, byte[]> items) {
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

    @Override
    protected int resolveTotalPages(@NotNull List<BuildingData> buildings) {
        int level = buildings.stream()
                .filter(b -> b.buildingId().equals(WAREHOUSE_BUILDING_ID))
                .mapToInt(BuildingData::level)
                .findFirst().orElse(0);
        return 1 + Math.max(0, Math.min(3, level));
    }

    @Override
    protected void logError(@NotNull String msg) {
        plugin.getLogger().severe("[Inv] " + msg);
    }
}