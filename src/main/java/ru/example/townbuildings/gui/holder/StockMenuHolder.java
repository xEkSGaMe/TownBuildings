package ru.example.townbuildings.gui.holder;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * M-4 — SLOT_BACK / SLOT_FORWARD / INVENTORY_SIZE унаследованы от PageHolder.
 *
 * L1.4 — удалена ITEM_SLOTS (52): не читалась. Реальная логика использует
 *        isItemSlot(slot), который проверяет SLOT_BACK/SLOT_FORWARD явно.
 */
public final class StockMenuHolder implements PageHolder {

    private final UUID townUuid;
    private final int page;
    private final int totalPages;
    private final String townName;

    private Inventory inventory;
    private final Map<Integer, byte[]> original = new HashMap<>();
    private final Map<Integer, ItemStack> current = new HashMap<>();

    public StockMenuHolder(@NotNull UUID townUuid, int page, int totalPages,
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

    public void setInitial(@NotNull Map<Integer, byte[]> raw) {
        original.clear();
        current.clear();
        for (Map.Entry<Integer, byte[]> e : raw.entrySet()) {
            int slot = e.getKey();
            byte[] bytes = e.getValue();
            if (!isItemSlot(slot) || bytes == null) continue;
            try {
                ItemStack is = ItemStack.deserializeBytes(bytes);
                if (is == null || is.getType().isAir()) continue;
                original.put(slot, bytes.clone());
                current.put(slot, is);
            } catch (Throwable ignored) {
                // skip corrupted slot
            }
        }
    }

    @Override
    public void capture() {
        current.clear();
        if (inventory == null) return;
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (!isItemSlot(i)) continue;
            ItemStack is = inventory.getItem(i);
            if (is != null && !is.getType().isAir()) {
                current.put(i, is.clone());
            }
        }
    }

    @Override
    public @NotNull Map<Integer, byte[]> diff() {
        Map<Integer, byte[]> out = new HashMap<>();
        Set<Integer> all = new HashSet<>();
        all.addAll(original.keySet());
        all.addAll(current.keySet());

        for (int i : all) {
            if (!isItemSlot(i)) continue;
            byte[] a = original.get(i);
            ItemStack b = current.get(i);

            if (a == null && b == null) continue;

            if (a != null && b != null) {
                byte[] bb = b.serializeAsBytes();
                if (Arrays.equals(a, bb)) continue;
                out.put(i, bb);
            } else if (b == null) {
                out.put(i, null);
            } else {
                out.put(i, b.serializeAsBytes());
            }
        }
        return out;
    }

    @Override
    public void commit() {
        original.clear();
        for (Map.Entry<Integer, ItemStack> e : current.entrySet()) {
            original.put(e.getKey(), e.getValue().serializeAsBytes());
        }
    }

    @Override
    public void clear() {
        original.clear();
        current.clear();
    }
}