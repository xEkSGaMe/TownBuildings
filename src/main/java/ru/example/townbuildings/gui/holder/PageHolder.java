package ru.example.townbuildings.gui.holder;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * Общий контракт для InventoryMenuHolder и StockMenuHolder.
 * Позволяет PageSession/PageSessionRegistry работать с обоими.
 */
public interface PageHolder extends InventoryHolder {

    @NotNull UUID townUuid();
    int page();
    int totalPages();
    @NotNull String townName();

    boolean isItemSlot(int slot);

    /** Считать текущее состояние инвентаря в current[]. */
    void capture();

    /** Diff original vs current. null-значение = удалить слот. */
    @NotNull Map<Integer, byte[]> diff();

    /** Зафиксировать current как original (после успешного save). */
    void commit();

    /** Очистить буферы. */
    void clear();

    void setInventory(@NotNull Inventory inv);
}