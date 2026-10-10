package ru.example.townbuildings.gui.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.config.BuildingBranch;
import ru.example.townbuildings.gui.Menu;
import ru.example.townbuildings.gui.util.ItemBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Главное меню /tbuildings builds — 5 веток. */
public final class MainBuildingsMenu extends Menu {

    private final UUID townUuid;
    private final String townName;

    public MainBuildingsMenu(@NotNull TownBuildingsPlugin plugin, @NotNull Player player,
                             @NotNull UUID townUuid, @NotNull String townName) {
        super(plugin, player);
        this.townUuid = townUuid;
        this.townName = townName;
    }

    @Override
    protected int size() { return 45; }

    @Override
    protected @NotNull Component title(@NotNull Player player) {
        return plugin.getMessagesConfig().get("gui.main-title",
                Map.of("town", townName));
    }

    @Override
    protected void render(@NotNull Player player) {
        var mc = plugin.getMessagesConfig();

        ItemStack filler = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                .name(Component.empty()).build();
        for (int i = 0; i < size(); i++) inventory.setItem(i, filler);

        BuildingBranch[] branches = BuildingBranch.values();
        int[] slots = layoutBranchSlots(branches.length);

        for (int i = 0; i < branches.length; i++) {
            BuildingBranch branch = branches[i];
            String metaKey = "gui.branch-meta." + branch.name();

            String iconSymbol = mc.getRaw(metaKey + ".icon-symbol");
            if (iconSymbol.isEmpty()) iconSymbol = "❖";

            Map<String, String> ph = Map.of(
                    "branch",      branch.displayName(),
                    // P19 — cachedRaw: сырые конфиг-строки кэшируются,
                    // исключая повторный обход yaml на каждый refresh меню.
                    "short-desc",  mc.cachedRaw(metaKey + ".short-desc"),
                    "color",       mc.cachedRaw(metaKey + ".color"),
                    "grad-from",   mc.cachedRaw(metaKey + ".grad-from"),
                    "grad-to",     mc.cachedRaw(metaKey + ".grad-to"),
                    "icon-symbol", iconSymbol
            );

            // P19 — ph целиком из конфигов (название ветки, цвета, символ):
            // статичен, можно кэшировать по значению.
            Component header = mc.cached("gui.branch-icon.main", ph, true);

            List<Component> lore = new ArrayList<>();
            lore.add(mc.cached("gui.branch-icon.short-description", ph, true));
            lore.add(Component.empty());
            lore.add(mc.get("gui.branch-icon.hint-inv"));
            lore.add(mc.get("gui.branch-icon.hint-coins"));
            if (branch == BuildingBranch.PRODUCTION) {
                lore.add(mc.get("gui.branch-icon.hint-stock-1"));
                lore.add(mc.get("gui.branch-icon.hint-stock-2"));
            }

            inventory.setItem(slots[i], new ItemBuilder(iconFor(branch))
                    .name(header)
                    .lore(lore)
                    .build());
        }
    }

    /**
     * Слоты иконок веток (порядок = BuildingBranch.values()):
     *   ECONOMY=11, MILITARY=13, TECH=15, MANAGEMENT=29, PRODUCTION=31.
     */
    private static int[] layoutBranchSlots(int n) {
        int[] all = {11, 13, 15, 29, 31};
        if (n <= 0) return new int[0];
        if (n > all.length) n = all.length;
        int[] out = new int[n];
        System.arraycopy(all, 0, out, 0, n);
        return out;
    }

    @Override
    public void onClick(@NotNull Player player, int slot, @NotNull InventoryClickEvent event) {
        event.setCancelled(true);
        if (isLocked()) return;

        BuildingBranch[] branches = BuildingBranch.values();
        int[] slots = layoutBranchSlots(branches.length);

        BuildingBranch clicked = null;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                clicked = branches[i];
                break;
            }
        }
        if (clicked == null) return;

        new BranchMenu(plugin, player, townUuid, townName, clicked, this).openAndLoad();
    }

    private @NotNull Material iconFor(@NotNull BuildingBranch branch) {
        return switch (branch) {
            case ECONOMY    -> Material.GOLD_INGOT;
            case MILITARY   -> Material.DIAMOND_AXE;
            case TECH       -> Material.LECTERN;
            case MANAGEMENT -> Material.WRITABLE_BOOK;
            case PRODUCTION -> Material.SMITHING_TABLE;
        };
    }
}