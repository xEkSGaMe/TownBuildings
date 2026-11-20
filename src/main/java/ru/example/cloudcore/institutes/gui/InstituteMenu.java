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
 * 54 слота (6 рядов), кнопки «назад» в 45/53, варианты расставлены
 * симметрично по центру. Оформление повторяет Здания / Чудеса Света /
 * Священные Места, но с порядком секций:
 *   [История] → [Бонусы] → [Штрафы] → статус выбора.
 *
 * populate() читает institutesService.getCached(); при первом открытии
 * (данных ещё нет в кэше) — всё показано как «доступно для выбора».
 *
 * ★ 5b — лор-строки читаются из messages.yml (institutes.menu.*),
 *        сборка предмета идёт через ItemBuilder.
 */
public class InstituteMenu implements InventoryHolder {

    private static final int SIZE            = 54;
    private static final int SLOT_BACK_LEFT  = 45;
    private static final int SLOT_BACK_RIGHT = 53;

    private final CloudCorePlugin plugin;
    private final MenuType type;
    private final Inventory inventory;
    private final Map<Integer, Object> slotMap = new HashMap<>();

    public InstituteMenu(@NotNull CloudCorePlugin plugin, @NotNull MenuType type) {
        this.plugin = plugin;
        this.type = type;
        this.inventory = Bukkit.createInventory(this, SIZE,
                plugin.institutesMessages().title(type.configKey()));
    }

    public @NotNull MenuType type() { return type; }

    public @Nullable Object valueAt(int slot) { return slotMap.get(slot); }

    public boolean isBackSlot(int slot) {
        return slot == SLOT_BACK_LEFT || slot == SLOT_BACK_RIGHT;
    }

    public void open(@NotNull Player p) {
        populate(p);
        p.openInventory(inventory);
    }

    private void populate(@NotNull Player player) {
        inventory.clear();
        slotMap.clear();

        var mc = plugin.getMessagesConfig();

        inventory.setItem(SLOT_BACK_LEFT, new ItemBuilder(Material.ARROW)
                .name(mc.get("gui.back-button-left"))
                .addLore(mc.get("gui.back-button-lore"))
                .build());
        inventory.setItem(SLOT_BACK_RIGHT, new ItemBuilder(Material.ARROW)
                .name(mc.get("gui.back-button-right"))
                .addLore(mc.get("gui.back-button-lore"))
                .build());

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
        int[] slots = slotsFor(keys.size());

        for (int i = 0; i < keys.size() && i < slots.length; i++) {
            String key = keys.get(i);
            int slot = slots[i];
            String base = sectionName + "." + key;

            Object enumValue = switch (type) {
                case RELIGION -> Religion.byKey(key);
                case FORM     -> GovernmentForm.byKey(key);
                case IDEOLOGY -> Ideology.byKey(key);
            };
            if (enumValue == null) continue;

            boolean selected = enumValue.equals(current);

            Material mat = plugin.institutesConfig().material(base + ".material", Material.PAPER);
            String display = plugin.institutesConfig().display(base + ".display");
            String symbol = plugin.institutesConfig().symbol(base + ".symbol");
            String color = plugin.institutesConfig().color(base + ".color");
            String gradFrom = plugin.institutesConfig().gradFrom(base + ".grad-from");
            String gradTo = plugin.institutesConfig().gradTo(base + ".grad-to");

            // MiniMessage-тег цвета (<#rrggbb>), как в Wonders/Holy Sites:
            // шаблоны вставляют {icon-color} перед символом/маркерами.
            String iconColor = toMiniColorTag(color);

            Component header = mc.getUnescaped("institutes.menu.header", Map.of(
                    "icon-color",  iconColor,
                    "icon-symbol", symbol,
                    "grad-from",   gradFrom,
                    "grad-to",     gradTo,
                    "name",        display));

            List<Component> lore = new ArrayList<>();

            List<String> history = plugin.institutesConfig().lore(base + ".history");
            List<String> bonuses = plugin.institutesConfig().lore(base + ".bonuses");
            List<String> penalties = plugin.institutesConfig().lore(base + ".penalties");

            boolean hasStructured = !history.isEmpty() || !bonuses.isEmpty() || !penalties.isEmpty();
            if (hasStructured) {
                addSection(lore, "institutes.menu.section-history",
                        history, "institutes.menu.line-history", iconColor);

                addSection(lore, "institutes.menu.section-bonuses",
                        bonuses, "institutes.menu.line-bonus", iconColor);

                if (!penalties.isEmpty()) {
                    addSection(lore, "institutes.menu.section-penalties",
                            penalties, "institutes.menu.line-penalty", iconColor);
                }
            } else {
                // Фоллбэк для старого формата institutes.yml (единое поле "lore"):
                // выводим строки как есть, без искусственных [История/Бонусы/Штрафы].
                List<String> legacy = plugin.institutesConfig().lore(base + ".lore");
                if (!legacy.isEmpty()) {
                    lore.add(Component.empty());
                    for (String line : legacy) {
                        lore.add(plugin.institutesMessages().colored(line == null ? "" : line));
                    }
                }
            }

            lore.add(Component.empty());

            if (selected) {
                lore.add(mc.get("institutes.menu.status-selected"));
            } else if (current == null) {
                lore.add(mc.get("institutes.menu.status-choose"));
            } else if (freeChange) {
                lore.add(mc.get("institutes.menu.status-change-free"));
            } else {
                lore.add(mc.getUnescaped("institutes.menu.status-change-cost", Map.of(
                        "cost", String.valueOf(plugin.institutesConfig().changeCostLevels()))));
            }

            inventory.setItem(slot, new ItemBuilder(mat)
                    .name(header)
                    .lore(lore)
                    .hideAllFlags()
                    .glow(selected)
                    .build());
            slotMap.put(slot, enumValue);
        }
    }

    /** Добавляет секцию [заголовок] + строки, если строки не пусты. */
    private void addSection(@NotNull List<Component> lore,
                            @NotNull String sectionKey,
                            @NotNull List<String> lines,
                            @NotNull String lineKey,
                            @NotNull String iconColor) {
        if (lines.isEmpty()) return;
        var mc = plugin.getMessagesConfig();

        // Пустая строка перед каждой секцией (в т.ч. первой — отделяет от названия).
        lore.add(Component.empty());
        lore.add(mc.getUnescaped(sectionKey, Map.of("icon-color", iconColor)));
        lore.add(Component.empty());
        for (String line : lines) {
            if (line == null) line = "";
            lore.add(mc.getUnescaped(lineKey, Map.of(
                    "icon-color", iconColor,
                    "line",       line)));
        }
    }

    // ------------------------------------------------------------------
    //  Раскладка слотов
    // ------------------------------------------------------------------

    /**
     * Возвращает слоты для {@code count} вариантов.
     *
     * До 7 элементов — один центрированный ряд во 2-м ряду.
     * Больше — несколько центрированных рядов (например, 8 → 4 + 4).
     */
    private int[] slotsFor(int count) {
        if (count <= 0) return new int[0];

        int rows = (count + 6) / 7;          // ceil(count / 7)
        rows = Math.min(rows, 4);

        int[] rowIndexes = switch (rows) {
            case 1  -> new int[]{2};
            case 2  -> new int[]{2, 3};
            case 3  -> new int[]{1, 2, 3};
            default -> new int[]{1, 2, 3, 4};
        };

        int perRow = count / rows;
        int extra  = count % rows;

        List<Integer> out = new ArrayList<>(count);
        for (int r = 0; r < rows && out.size() < count; r++) {
            int n = Math.min(7, perRow + (r < extra ? 1 : 0));
            if (n <= 0) continue;
            for (int slot : centeredRow(rowIndexes[r] * 9, n)) {
                if (out.size() >= count) break;
                out.add(slot);
            }
        }

        // Страховка на случай экстремально большого конфига.
        for (int slot = 9; slot < SLOT_BACK_LEFT && out.size() < count; slot++) {
            if (!out.contains(slot)) out.add(slot);
        }

        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Симметрично центрирует n предметов в одном ряду из 9 слотов. */
    private int[] centeredRow(int base, int n) {
        return switch (n) {
            case 1 -> new int[]{base + 4};
            case 2 -> new int[]{base + 3, base + 5};
            case 3 -> new int[]{base + 3, base + 4, base + 5};
            case 4 -> new int[]{base + 2, base + 3, base + 5, base + 6};
            case 5 -> new int[]{base + 2, base + 3, base + 4, base + 5, base + 6};
            case 6 -> new int[]{base + 1, base + 2, base + 3, base + 5, base + 6, base + 7};
            case 7 -> new int[]{base + 1, base + 2, base + 3, base + 4, base + 5, base + 6, base + 7};
            default -> {
                int[] all = new int[9];
                for (int i = 0; i < 9; i++) all[i] = base + i;
                yield all;
            }
        };
    }

    /** Приводит цвет к MiniMessage-тегу вида "<#rrggbb>" (как в wonders/holy-sites). */
    private static @NotNull String toMiniColorTag(@Nullable String color) {
        if (color == null || color.isBlank()) return "<#ffffff>";
        String c = color.trim();
        if (c.startsWith("<") && c.endsWith(">")) return c;
        if (c.startsWith("#")) return "<" + c + ">";
        if (c.matches("(?i)[0-9a-f]{6}")) return "<#" + c + ">";
        return c;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
