package ru.example.townbuildings.gui.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.config.BuildingBranch;
import ru.example.townbuildings.config.BuildingDefinitionRegistry;
import ru.example.townbuildings.gui.Menu;
import ru.example.townbuildings.gui.util.ItemBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Главное меню /tbuildings builds.
 *
 * WND — иконка world_wonders теперь открывает WonderMenu (если
 *       features.wonders = true).
 */
public final class MainBuildingsMenu extends Menu {

    private static final int[] BRANCH_SLOTS = {11, 13, 15, 30, 32};

    private static final String[] SPECIAL_KEYS = {
            "warehouse_inv", "warehouse_stock",
            "city_level", "world_wonders", "sacred_places"
    };
    private static final int[] SPECIAL_SLOTS = {47, 48, 49, 50, 51};

    private final UUID townUuid;
    private final String townName;

    public MainBuildingsMenu(@NotNull TownBuildingsPlugin plugin, @NotNull Player player,
                             @NotNull UUID townUuid, @NotNull String townName) {
        super(plugin, player);
        this.townUuid = townUuid;
        this.townName = townName;
    }

    @Override
    protected int size() { return 54; }

    @Override
    protected @NotNull Component title(@NotNull Player player) {
        return plugin.getMessagesConfig().get("gui.main-title",
                Map.of("town", townName));
    }

    @Override
    protected void render(@NotNull Player player) {
        var mc = plugin.getMessagesConfig();

        BuildingBranch[] branches = BuildingBranch.values();
        int n = Math.min(branches.length, BRANCH_SLOTS.length);

        for (int i = 0; i < n; i++) {
            BuildingBranch branch = branches[i];
            String metaKey = "gui.branch-meta." + branch.name();

            String iconSymbol = mc.getRaw(metaKey + ".icon-symbol");
            if (iconSymbol.isEmpty()) iconSymbol = "❖";

            Map<String, String> ph = Map.of(
                    "branch",      branch.displayName(),
                    "short-desc",  mc.getRaw(metaKey + ".short-desc"),
                    "color",       mc.getRaw(metaKey + ".color"),
                    "grad-from",   mc.getRaw(metaKey + ".grad-from"),
                    "grad-to",     mc.getRaw(metaKey + ".grad-to"),
                    "icon-symbol", iconSymbol
            );

            Component header = mc.getUnescaped("gui.branch-icon.main", ph);

            List<Component> lore = new ArrayList<>();
            lore.add(mc.getUnescaped("gui.branch-icon.short-description", ph));
            lore.add(Component.empty());
            lore.add(mc.get("gui.branch-icon.hint-inv"));
            lore.add(mc.get("gui.branch-icon.hint-coins"));
            if (branch == BuildingBranch.PRODUCTION) {
                lore.add(mc.get("gui.branch-icon.hint-stock-1"));
                lore.add(mc.get("gui.branch-icon.hint-stock-2"));
            }

            inventory.setItem(BRANCH_SLOTS[i], new ItemBuilder(iconFor(branch))
                    .name(header)
                    .lore(lore)
                    .build());
        }

        for (int i = 0; i < SPECIAL_KEYS.length && i < SPECIAL_SLOTS.length; i++) {
            renderSpecialIcon(SPECIAL_KEYS[i], SPECIAL_SLOTS[i]);
        }
    }

    private void renderSpecialIcon(@NotNull String key, int slot) {
        var mc = plugin.getMessagesConfig();
        String base = "gui.special-icons." + key;

        String name = mc.getRaw(base + ".name");
        if (name.isEmpty()) return;

        Material material = BuildingDefinitionRegistry.resolveMaterial(
                mc.getRaw(base + ".material"));
        if (material == null) return;

        String symbol    = mc.getRaw(base + ".symbol");
        String color     = mc.getRaw(base + ".color");
        String gradFrom  = mc.getRaw(base + ".grad-from");
        String gradTo    = mc.getRaw(base + ".grad-to");
        String shortDesc = mc.getRaw(base + ".short-desc");
        boolean inDev    = "true".equalsIgnoreCase(mc.getRaw(base + ".in-development"));

        Map<String, String> ph = Map.of(
                "icon-color",  color,
                "icon-symbol", symbol,
                "grad-from",   gradFrom,
                "grad-to",     gradTo,
                "name",        name
        );

        Component header = mc.getUnescaped("gui.special-icon.main", ph);

        List<Component> lore = new ArrayList<>();
        lore.add(mc.getUnescaped("gui.special-icon.short-description", Map.of(
                "icon-color", color,
                "short-desc", shortDesc
        )));
        lore.add(Component.empty());

        // ★ WND — подсказки для «Чудес света».
        if ("world_wonders".equals(key)) {
            lore.add(Component.empty());
            lore.add(mc.get("gui.branch-icon.hint-inv"));
            lore.add(mc.get("gui.branch-icon.hint-coins"));
            lore.add(mc.get("gui.branch-icon.hint-stock-1"));
            lore.add(mc.get("gui.branch-icon.hint-stock-2"));
            lore.add(Component.empty());
        }

        if (inDev) {
            lore.add(mc.get("gui.special-icon.in-development-warning"));
            lore.add(mc.get("gui.special-icon.in-development-hint"));
        } else {
            lore.add(mc.get("gui.special-icon.hint-click"));
        }

        inventory.setItem(slot, new ItemBuilder(material)
                .name(header)
                .lore(lore)
                .build());
    }

    @Override
    public void onClick(@NotNull Player player, int slot, @NotNull InventoryClickEvent event) {
        event.setCancelled(true);

        BuildingBranch[] branches = BuildingBranch.values();
        int n = Math.min(branches.length, BRANCH_SLOTS.length);

        for (int i = 0; i < n; i++) {
            if (BRANCH_SLOTS[i] == slot) {
                new BranchMenu(plugin, player, townUuid, townName, branches[i], this).openAndLoad();
                return;
            }
        }

        for (int i = 0; i < SPECIAL_SLOTS.length && i < SPECIAL_KEYS.length; i++) {
            if (SPECIAL_SLOTS[i] == slot) {
                handleSpecialClick(player, SPECIAL_KEYS[i]);
                return;
            }
        }
    }

    private void handleSpecialClick(@NotNull Player player, @NotNull String key) {
        var mc = plugin.getMessagesConfig();
        String base = "gui.special-icons." + key;
        boolean inDev = "true".equalsIgnoreCase(mc.getRaw(base + ".in-development"));

        if (inDev) {
            player.sendActionBar(mc.get("gui.special-icon.in-development-warning"));
            return;
        }

        switch (key) {
            case "warehouse_inv" -> {
                if (!plugin.getConfigManager().features().inventory()) {
                    player.sendActionBar(mc.get("general.internal-error"));
                    return;
                }
                plugin.cityInventoryMenu().openFirstPage(player, townUuid, townName);
            }
            case "warehouse_stock" -> {
                if (!plugin.getConfigManager().features().stock()) {
                    player.sendActionBar(mc.get("general.internal-error"));
                    return;
                }
                plugin.cityStockMenu().openFirstPage(player, townUuid, townName);
            }
            case "world_wonders" -> {
                if (!plugin.getConfigManager().features().wonders()) {
                    player.sendActionBar(mc.get("general.internal-error"));
                    return;
                }
                new WonderMenu(plugin, player, townUuid, townName, this).open();
            }
            default -> player.sendActionBar(mc.get("general.internal-error"));
        }
    }

    private @NotNull Material iconFor(@NotNull BuildingBranch branch) {
        return switch (branch) {
            case ECONOMY    -> Material.GOLD_BLOCK;
            case MILITARY   -> Material.NETHERITE_CHESTPLATE;
            case TECH       -> Material.LECTERN;
            case MANAGEMENT -> Material.WRITABLE_BOOK;
            case PRODUCTION -> Material.SMITHING_TABLE;
        };
    }
}