package ru.example.cloudcore.gui.holder;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * Общий контракт для InventoryMenuHolder и StockMenuHolder.
 * Позволяет PageSession/PageSessionRegistry работать с обоими.
 *
 * M-4 — общие константы страничного layout'а (SLOT_BACK / SLOT_FORWARD /
 *       INVENTORY_SIZE) подняты сюда, чтобы AbstractPaginatedMenu и
 *       InventoryDao.depositBack (M-7) ссылались на один источник истины.
 */
public interface PageHolder extends InventoryHolder {

    /** M-4 — слот «Назад» (нижний левый угол, col 0). */
    int SLOT_BACK      = 45;
    /** M-4 — слот «Вперёд» (нижний правый угол, col 8). */
    int SLOT_FORWARD   = 53;
    /** M-4 — размер верхнего инвентаря (6×9). */
    int INVENTORY_SIZE = 54;

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