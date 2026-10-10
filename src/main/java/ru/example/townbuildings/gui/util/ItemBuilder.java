package ru.example.townbuildings.gui.util;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Утилита для сборки ItemStack с Component-именем и лором. */
public final class ItemBuilder {

    private final ItemStack item;

    public ItemBuilder(@NotNull Material material) {
        this.item = new ItemStack(material);
    }

    public ItemBuilder(@NotNull ItemStack base) {
        this.item = base.clone();
    }

    public @NotNull ItemBuilder name(@NotNull Component name) {
        ItemMeta meta = meta();
        meta.displayName(name);
        item.setItemMeta(meta);
        return this;
    }

    public @NotNull ItemBuilder lore(@NotNull List<Component> lore) {
        ItemMeta meta = meta();
        meta.lore(lore);
        item.setItemMeta(meta);
        return this;
    }

    public @NotNull ItemBuilder addLore(@NotNull Component line) {
        ItemMeta meta = meta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(line);
        meta.lore(lore);
        item.setItemMeta(meta);
        return this;
    }

    public @NotNull ItemBuilder amount(int amount) {
        item.setAmount(Math.max(1, Math.min(64, amount)));
        return this;
    }

    public @NotNull ItemBuilder glow(boolean glow) {
        ItemMeta meta = meta();
        meta.setEnchantmentGlintOverride(glow);
        item.setItemMeta(meta);
        return this;
    }

    public @NotNull ItemStack build() {
        return item;
    }

    private ItemMeta meta() {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) throw new IllegalStateException("ItemMeta == null for " + item.getType());
        return meta;
    }
}