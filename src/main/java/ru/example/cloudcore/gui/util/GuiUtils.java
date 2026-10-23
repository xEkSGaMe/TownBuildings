package ru.example.cloudcore.gui.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.service.ResourceCheck;
import ru.example.cloudcore.util.RomanNumbers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Утилиты для лора и GUI. */
public final class GuiUtils {

    private GuiUtils() {}

    /** Одна строка с галочкой/крестом. */
    public static @NotNull Component checkLine(boolean ok,
                                               @NotNull String label,
                                               @Nullable String extra) {
        Component icon = ok
                ? Component.text("✔ ", NamedTextColor.GREEN)
                : Component.text("✘ ", NamedTextColor.RED);
        Component main = Component.text(label, ok ? NamedTextColor.GRAY : NamedTextColor.RED);
        if (extra == null || extra.isBlank()) {
            return Component.empty().append(icon).append(main);
        }
        return Component.empty()
                .append(icon).append(main)
                .append(Component.text(" " + extra, NamedTextColor.DARK_GRAY));
    }

    /** Лор стоимости уровня — список компонентов, по одной строке на ресурс. */
    public static @NotNull List<Component> costLore(@NotNull ResourceCheck check) {
        List<Component> out = new ArrayList<>();
        if (check.coins().need() > 0) {
            out.add(checkLine(check.coins().ok(),
                    "Монеты: " + check.coins().need(),
                    check.coins().ok() ? null : "(есть " + check.coins().have() + ")"));
        }
        for (var e : check.items().entrySet()) {
            ResourceCheck.ItemCheck ic = e.getValue();
            out.add(checkLine(ic.ok(),
                    formatItem(e.getKey(), ic.need()),
                    ic.ok() ? null : "(есть " + ic.have() + ")"));
        }
        return out;
    }

    /** Плоская строка стоимости без проверки (для превью следующего уровня). */
    public static @NotNull List<Component> costPreview(long coins, @NotNull java.util.Map<Material, Integer> items) {
        List<Component> out = new ArrayList<>();
        if (coins > 0) {
            out.add(Component.text("• " + coins + " монет", NamedTextColor.GOLD));
        }
        for (var e : items.entrySet()) {
            out.add(Component.text("• " + formatItem(e.getKey(), e.getValue()), NamedTextColor.GRAY));
        }
        return out;
    }

    /** Название здания с римским уровнем: "Монетный двор IV". */
    public static @NotNull Component buildingTitle(@NotNull String displayName, int level) {
        if (level <= 0) {
            return Component.text(displayName, NamedTextColor.WHITE);
        }
        return Component.empty()
                .append(Component.text(displayName, NamedTextColor.WHITE))
                .append(Component.text(" " + RomanNumbers.toRoman(level), NamedTextColor.YELLOW));
    }

    /**
     * Добавить строки в лор предмета, сохранив существующий лор.
     * Если лора не было — создаёт новый список.
     */
    public static void appendLore(@NotNull ItemStack item, @NotNull Component... lines) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        List<Component> lore = meta.lore() == null
                ? new ArrayList<>()
                : new ArrayList<>(meta.lore());
        for (Component line : lines) lore.add(line);
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    // -------- helpers --------

    /** "32 дуба" / "64 стекла" — используем человекочитаемое имя материала. */
    public static @NotNull String formatItem(@NotNull Material mat, int amount) {
        String name = mat.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return amount + " " + name;
    }
}