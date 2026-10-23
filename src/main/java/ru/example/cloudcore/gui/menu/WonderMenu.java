package ru.example.cloudcore.gui.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.config.WonderConfig;
import ru.example.cloudcore.config.WonderRarityStyle;
import ru.example.cloudcore.gui.Menu;
import ru.example.cloudcore.gui.util.ItemBuilder;
import ru.example.cloudcore.service.WonderBuildResult;
import ru.example.cloudcore.CloudCorePlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import ru.example.cloudcore.CloudCorePlugin;
/**
 * Меню «Чудеса Света» — 54 слота (6 рядов).
 * Кнопки «назад» — в углах нижнего ряда (45 и 53).
 *
 * Lore-строки из wonders.yml поддерживают плейсхолдер {icon-color}:
 * при рендере он заменяется на wc.color() — так значение бонуса
 * подсвечивается в цвет иконки, а описание остаётся <white>.
 */
public final class WonderMenu extends Menu {

    private static final int SIZE            = 54;
    private static final int SLOT_BACK_LEFT  = 45;
    private static final int SLOT_BACK_RIGHT = 53;

    private final UUID townUuid;
    private final String townName;
    private final Menu parent;
    private final Map<Integer, String> slotToWonder = new HashMap<>();

    public WonderMenu(@NotNull CloudCorePlugin plugin, @NotNull Player player,
                      @NotNull UUID townUuid, @NotNull String townName,
                      @Nullable Menu parent) {
        super(plugin, player);
        this.townUuid = townUuid;
        this.townName = townName;
        this.parent = parent;
    }

    @Override protected int size() { return SIZE; }

    @Override
    protected @NotNull Component title(@NotNull Player player) {
        return plugin.getMessagesConfig().get("wonders.menu-title",
                Map.of("town", townName));
    }

    @Override
    protected void render(@NotNull Player player) {
        var mc = plugin.getMessagesConfig();

        inventory.setItem(SLOT_BACK_LEFT, new ItemBuilder(Material.ARROW)
                .name(mc.get("gui.back-button-left"))
                .addLore(mc.get("gui.back-button-lore"))
                .build());
        inventory.setItem(SLOT_BACK_RIGHT, new ItemBuilder(Material.ARROW)
                .name(mc.get("gui.back-button-right"))
                .addLore(mc.get("gui.back-button-lore"))
                .build());

        slotToWonder.clear();

        int used  = plugin.wonderService().getUsedSlots(townUuid);
        int limit = plugin.wonderService().getSlotLimit(townUuid);

        for (WonderConfig wc : plugin.wonderRegistry().all()) {
            int slot = wc.guiSlot();
            if (slot < 0 || slot >= SIZE
                    || slot == SLOT_BACK_LEFT || slot == SLOT_BACK_RIGHT) continue;
            slotToWonder.put(slot, wc.id());
            inventory.setItem(slot, buildIcon(wc, used, limit));
        }
    }

    private @NotNull ItemStack buildIcon(@NotNull WonderConfig wc, int usedSlots, int slotLimit) {
        var mc = plugin.getMessagesConfig();
        WonderRarityStyle style = plugin.wonderRegistry().rarityStyle(wc.rarity());

        Map<String, String> ph = new HashMap<>();
        ph.put("icon-color",  wc.color());
        ph.put("icon-symbol", wc.symbol());
        ph.put("grad-from",   wc.gradFrom());
        ph.put("grad-to",     wc.gradTo());
        ph.put("name",        wc.displayName());

        Component header = mc.getUnescaped("wonders.header", ph);

        List<Component> lore = new ArrayList<>();
        lore.add(mc.getUnescaped("wonders.lore.rarity-label",
                Map.of("rarity", style.color() + style.displayName())));

        String owner = plugin.wonderService().getBuiltBy(wc.id()).orElse(null);
        boolean ownTown = owner != null && owner.equalsIgnoreCase(townName);
        if (owner != null) {
            lore.add(mc.getUnescaped("wonders.lore.built-in",
                    Map.of("town", owner)));
        }

        lore.add(Component.empty());

        Map<String, String> colorPh = Map.of("icon-color", wc.color());
        lore.add(mc.getUnescaped("wonders.lore.section-conditions", colorPh));
        lore.add(Component.empty());

        if (wc.costCoins() > 0) {
            lore.add(mc.getUnescaped("wonders.lore.line-coins", Map.of(
                    "icon-color", wc.color(),
                    "coins",      Long.toString(wc.costCoins()))));
        }
        if (!wc.costItems().isEmpty()) {
            lore.add(mc.getUnescaped("wonders.lore.line-items-header", colorPh));
            for (var e : wc.costItems().entrySet()) {
                lore.add(mc.getUnescaped("wonders.lore.line-item", Map.of(
                        "icon-color", wc.color(),
                        "amount",     Integer.toString(e.getValue()),
                        "item",       formatItemName(e.getKey()))));
            }
        }

        if (!wc.lore().isEmpty()) {
            lore.add(Component.empty());
            lore.add(mc.getUnescaped("wonders.lore.section-effects", colorPh));
            lore.add(Component.empty());
            for (String line : wc.lore()) {
                // ★ {icon-color} → wc.color(); текст и MiniMessage-теги
                //   (<!italic><white>) берутся из wonders.yml.
                String resolved = line.replace("{icon-color}", wc.color());
                lore.add(mc.mini().deserialize(resolved));
            }
        }

        lore.add(Component.empty());

        if (ownTown) {
            lore.add(mc.get("wonders.lore.already-built-own"));
        } else if (owner != null) {
            lore.add(mc.getUnescaped("wonders.lore.already-built-other",
                    Map.of("town", owner)));
        } else if (usedSlots >= slotLimit) {
            lore.add(mc.getUnescaped("wonders.lore.no-slots",
                    Map.of("used", Integer.toString(usedSlots),
                            "total", Integer.toString(slotLimit))));
        } else {
            lore.add(mc.get("wonders.lore.click-to-build"));
        }

        return new ItemBuilder(wc.icon())
                .name(header)
                .lore(lore)
                .glow(owner != null)
                .build();
    }

    @Override
    public void onClick(@NotNull Player player, int slot, @NotNull InventoryClickEvent event) {
        event.setCancelled(true);

        if (!plugin.getConfigManager().features().wonders()) {
            player.sendActionBar(plugin.getMessagesConfig().get("general.internal-error"));
            return;
        }

        if (slot == SLOT_BACK_LEFT || slot == SLOT_BACK_RIGHT) {
            if (parent != null) {
                plugin.menuManager().register(player, parent);
                parent.open();
            } else {
                close();
            }
            return;
        }

        String wonderId = slotToWonder.get(slot);
        if (wonderId == null) return;

        if (!plugin.menuManager().tryLockPlayer(player)) return;

        try {
            plugin.wonderService().tryBuild(townUuid, wonderId, player, result -> {
                plugin.menuManager().unlockPlayer(player);
                handleResult(player, result);
            });
        } catch (Throwable t) {
            plugin.menuManager().unlockPlayer(player);
            plugin.getLogger().severe("[WonderMenu] tryBuild: " + t);
            player.sendMessage(plugin.getMessagesConfig().get("general.internal-error"));
        }
    }

    private void handleResult(@NotNull Player player, @NotNull WonderBuildResult result) {
        var mc = plugin.getMessagesConfig();
        switch (result.status()) {
            case SUCCESS -> {
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1F, 1F);
                player.sendMessage(mc.get("wonders.upgrade.success",
                        Map.of("wonder", result.wonder() != null
                                ? result.wonder().displayName() : "?")));
                refreshIfOpen();
            }
            case ALREADY_OWNED -> {
                player.sendActionBar(mc.get("wonders.lore.already-built-own"));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7F, 1.5F);
            }
            case ALREADY_TAKEN -> {
                player.sendActionBar(mc.get("wonders.upgrade.already-taken",
                        Map.of("town", result.ownerTownName() != null
                                ? result.ownerTownName() : "?")));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7F, 1.5F);
            }
            case NO_SLOTS -> {
                player.sendActionBar(mc.get("wonders.upgrade.no-slots",
                        Map.of("used", Integer.toString(result.usedSlots()),
                                "total", Integer.toString(result.slotLimit()))));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7F, 1.2F);
            }
            case NOT_ENOUGH -> {
                player.sendActionBar(mc.get("wonders.upgrade.not-enough-items"));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7F, 1.2F);
            }
            case BUSY -> player.sendActionBar(mc.get("buildings.upgrade.busy"));
            case TIMEOUT -> player.sendActionBar(mc.get("buildings.upgrade.timeout"));
            default -> player.sendMessage(mc.get("general.internal-error"));
        }
    }

    private void refreshIfOpen() {
        if (!player.isOnline()) return;
        var top = player.getOpenInventory().getTopInventory();
        if (top.getHolder() != this) return;
        refresh();
    }

    private @NotNull String formatItemName(@NotNull Material mat) {
        String custom = plugin.getMessagesConfig().getRaw("gui.item-names." + mat.name());
        if (!custom.isEmpty()) return custom;
        return mat.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}