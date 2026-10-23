package ru.example.cloudcore.institutes.gui;

import com.palmergames.bukkit.towny.object.Town;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.CloudCorePlugin;
import ru.example.cloudcore.gui.util.ItemBuilder;
import ru.example.cloudcore.institutes.data.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GUI выбора религии / формы / идеологии.
 *
 * 27 слотов (3 ряда), варианты расставлены симметрично по центру.
 * При клике вызывается InstituteMenuListener.
 *
 * populate() читает institutesService.getCached(); при первом открытии
 * (данных ещё нет в кэше) — всё показано как «не выбрано».
 *
 * ★ 5b — лор-строки (lore-current, lore-change-*) читаются из
 *        messages.yml (institutes.*), а сборка предмета идёт через ItemBuilder.
 */
public class InstituteMenu implements InventoryHolder {

    private static final int[] S_2 = {12, 14};
    private static final int[] S_3 = {11, 13, 15};
    private static final int[] S_4 = {11, 12, 14, 15};
    private static final int[] S_5 = {10, 12, 13, 14, 16};
    private static final int[] S_6 = {10, 11, 12, 14, 15, 16};
    private static final int[] S_7 = {10, 11, 12, 13, 14, 15, 16};
    private static final int[] S_8 = {10, 11, 12, 13, 14, 15, 16, 17};

    private final CloudCorePlugin plugin;
    private final MenuType type;
    private final Inventory inventory;
    private final Map<Integer, Object> slotMap = new HashMap<>();

    public InstituteMenu(@NotNull CloudCorePlugin plugin, @NotNull MenuType type) {
        this.plugin = plugin;
        this.type = type;
        this.inventory = Bukkit.createInventory(this, 27,
                plugin.institutesMessages().title(type.configKey()));
    }

    public @NotNull MenuType type() { return type; }

    public @Nullable Object valueAt(int slot) { return slotMap.get(slot); }

    public void open(@NotNull Player p) {
        populate(p);
        p.openInventory(inventory);
    }

    private void populate(@NotNull Player player) {
        inventory.clear();
        slotMap.clear();

        Town town = plugin.towny() != null ? plugin.towny().getTownOf(player) : null;
        TownInstituteData data = null;
        if (town != null && plugin.institutesService() != null) {
            data = plugin.institutesService().getCached(town.getUUID());
        }
        Object current = data != null ? data.get(type) : null;

        String sectionName = switch (type) {
            case RELIGION -> "religions";
            case FORM     -> "forms";
            case IDEOLOGY -> "ideologies";
        };
        ConfigurationSection section = plugin.institutesConfig().section(sectionName);
        if (section == null) return;

        boolean freeChange = type == MenuType.RELIGION
                && data != null
                && data.form() == GovernmentForm.THEOCRACY;

        List<String> keys = new ArrayList<>(section.getKeys(false));
        int[] slots = symmetricSlots(keys.size());

        for (int i = 0; i < keys.size() && i < slots.length; i++) {
            String key = keys.get(i);
            int slot = slots[i];
            String base = sectionName + "." + key;

            Material mat = plugin.institutesConfig().material(base + ".material", Material.PAPER);
            String display = plugin.institutesConfig().display(base + ".display");
            List<String> rawLore = new ArrayList<>(plugin.institutesConfig().lore(base + ".lore"));

            Object enumValue = switch (type) {
                case RELIGION -> Religion.byKey(key);
                case FORM     -> GovernmentForm.byKey(key);
                case IDEOLOGY -> Ideology.byKey(key);
            };
            if (enumValue == null) continue;

            boolean selected = enumValue.equals(current);

            rawLore.add("");
            if (selected) {
                rawLore.add(textRaw("lore-current"));
            } else if (current != null) {
                if (freeChange) {
                    rawLore.add(textRaw("lore-change-free"));
                } else {
                    int cost = plugin.institutesConfig().changeCostLevels();
                    rawLore.add(textRaw("lore-change-cost")
                            .replace("{cost}", String.valueOf(cost)));
                }
            }

            inventory.setItem(slot, buildItem(mat, display, rawLore, selected));
            slotMap.put(slot, enumValue);
        }
    }

    /** ★ 5b — сырой &-текст из messages.yml (institutes.*). */
    private @NotNull String textRaw(@NotNull String key) {
        return plugin.getMessagesConfig().getInstitutesRaw(key);
    }

    private int[] symmetricSlots(int count) {
        return switch (count) {
            case 2 -> S_2;
            case 3 -> S_3;
            case 4 -> S_4;
            case 5 -> S_5;
            case 6 -> S_6;
            case 7 -> S_7;
            default -> S_8;
        };
    }

    private @NotNull ItemStack buildItem(@NotNull Material mat,
                                         @NotNull String display,
                                         @NotNull List<String> rawLore,
                                         boolean glow) {
        List<Component> loreComponents = new ArrayList<>(rawLore.size());
        for (String s : rawLore) loreComponents.add(plugin.institutesMessages().colored(s));

        return new ItemBuilder(mat)
                .name(plugin.institutesMessages().colored(display))
                .lore(loreComponents)
                .hideAllFlags()
                .glow(glow)
                .build();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}