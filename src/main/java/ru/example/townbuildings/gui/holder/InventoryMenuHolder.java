package ru.example.townbuildings.gui.holder;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * M-4 — SLOT_BACK / SLOT_FORWARD / INVENTORY_SIZE унаследованы от PageHolder.
 * Ссылки вида InventoryMenuHolder.SLOT_BACK продолжают компилироваться и
 * работать — Java резолвит константу через implements.
 *
 * L1.4 — удалена ITEM_SLOTS (52): не читалась. Реальная логика использует
 *        isItemSlot(slot), который проверяет SLOT_BACK/SLOT_FORWARD явно.
 */
public final class InventoryMenuHolder implements PageHolder {

    private final UUID townUuid;
    private final int page;
    private final int totalPages;
    private final String townName;

    private Inventory inventory;
    private final ItemStack[] original = new ItemStack[INVENTORY_SIZE];
    private final ItemStack[] current  = new ItemStack[INVENTORY_SIZE];

    public InventoryMenuHolder(@NotNull UUID townUuid, int page, int totalPages,
                               @NotNull String townName) {
        this.townUuid = townUuid;
        this.page = page;
        this.totalPages = totalPages;
        this.townName = townName;
    }

    @Override public @NotNull UUID townUuid()   { return townUuid; }
    @Override public int page()                 { return page; }
    @Override public int totalPages()           { return totalPages; }
    @Override public @NotNull String townName() { return townName; }

    @Override public @NotNull Inventory getInventory() { return inventory; }
    @Override public void setInventory(@NotNull Inventory inv) { this.inventory = inv; }

    @Override
    public boolean isItemSlot(int slot) {
        return slot >= 0 && slot < INVENTORY_SIZE
                && slot != SLOT_BACK && slot != SLOT_FORWARD;
    }

    public void setInitial(@NotNull ItemStack[] snapshot) {
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack is = (i < snapshot.length) ? snapshot[i] : null;
            original[i] = copy(is);
            current[i]  = copy(is);
        }
    }

    @Override
    public void capture() {
        if (inventory == null) return;
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (!isItemSlot(i)) continue;
            current[i] = copy(inventory.getItem(i));
        }
    }

    @Override
    public @NotNull Map<Integer, byte[]> diff() {
        Map<Integer, byte[]> out = new HashMap<>();
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (!isItemSlot(i)) continue;
            if (same(original[i], current[i])) continue;
            ItemStack b = current[i];
            out.put(i, b == null ? null : b.serializeAsBytes());
        }
        return out;
    }

    @Override
    public void commit() {
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            original[i] = copy(current[i]);
        }
    }

    @Override
    public void clear() {
        Arrays.fill(original, null);
        Arrays.fill(current, null);
    }

    private static @Nullable ItemStack copy(@Nullable ItemStack is) {
        if (is == null || is.getType().isAir()) return null;
        return is.clone();
    }

    private static boolean same(@Nullable ItemStack a, @Nullable ItemStack b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}