package ru.example.cloudcore.gui.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.CloudCorePlugin;
import ru.example.cloudcore.config.HolySiteConfig;
import ru.example.cloudcore.gui.Menu;
import ru.example.cloudcore.gui.util.ItemBuilder;
import ru.example.cloudcore.institutes.data.Religion;
import ru.example.cloudcore.service.HolyBuildResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Меню «Священные места».
 *
 * Повторяет оформление WonderMenu: редкость, [Условия], [Эффекты],
 * [История]. Отличия — вместо цены показывается требуемая религия и
 * наличие Таинственного Свитка.
 */
public final class HolySiteMenu extends Menu {

    private static final int SIZE            = 54;
    private static final int SLOT_BACK_LEFT  = 45;
    private static final int SLOT_BACK_RIGHT = 53;

    private final UUID townUuid;
    private final String townName;
    private final Menu parent;
    private final Map<Integer, String> slotToSite = new HashMap<>();

    public HolySiteMenu(@NotNull CloudCorePlugin plugin, @NotNull Player player,
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
        return plugin.getMessagesConfig().get("holy-sites.menu-title",
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

        slotToSite.clear();
        if (plugin.holySiteService() == null) return;
        for (HolySiteConfig site : plugin.holySiteRegistry().all()) {
            int slot = site.guiSlot();
            if (slot < 0 || slot >= SIZE
                    || slot == SLOT_BACK_LEFT || slot == SLOT_BACK_RIGHT) continue;
            slotToSite.put(slot, site.id());
            inventory.setItem(slot, buildIcon(site));
        }
    }

    private @NotNull ItemStack buildIcon(@NotNull HolySiteConfig site) {
        var mc = plugin.getMessagesConfig();

        Map<String, String> ph = new HashMap<>();
        ph.put("icon-color",  site.color());
        ph.put("icon-symbol", site.symbol());
        ph.put("grad-from",   site.gradFrom());
        ph.put("grad-to",     site.gradTo());
        ph.put("name",        site.displayName());

        Component header = mc.getUnescaped("holy-sites.header", ph);

        List<Component> lore = new ArrayList<>();

        String owner = plugin.holySiteService().getBuiltBy(site.id()).orElse(null);
        boolean ownTown = plugin.holySiteService().getBuiltTownUuid(site.id())
                .map(u -> u.equals(townUuid))
                .orElse(false);
        if (owner != null) {
            lore.add(mc.getUnescaped("holy-sites.lore.built-in",
                    Map.of("town", owner)));
        }
        if (ownTown && !plugin.holySiteService().isActive(townUuid)) {
            lore.add(mc.get("holy-sites.lore.inactive"));
        }

        lore.add(Component.empty());

        Map<String, String> colorPh = Map.of("icon-color", site.color());
        lore.add(mc.getUnescaped("holy-sites.lore.section-conditions", colorPh));
        lore.add(Component.empty());

        // ---- Условия: религия ----
        String requiredReligion = religionDisplay(site.religion());
        lore.add(mc.getUnescaped("holy-sites.lore.line-religion", Map.of(
                "icon-color", site.color(),
                "religion",   requiredReligion)));

        // ---- Условия: свиток ----
        String scrollName = mc.getRaw(
                plugin.holySiteService().scrolls().nameKey(site.scrollType()));
        lore.add(mc.getUnescaped("holy-sites.lore.line-scroll", Map.of(
                "icon-color", site.color(),
                "scroll",     scrollName,
                "site",       site.displayName())));

        // ---- Эффекты ----
        if (!site.lore().isEmpty()) {
            lore.add(Component.empty());
            lore.add(mc.getUnescaped("holy-sites.lore.section-effects", colorPh));
            lore.add(Component.empty());
            for (String line : site.lore()) {
                lore.add(mc.mini().deserialize(applySitePlaceholders(line, site)));
            }
        }

        // ---- История ----
        if (!site.history().isEmpty()) {
            lore.add(Component.empty());
            lore.add(mc.getUnescaped("holy-sites.lore.section-history", colorPh));
            lore.add(Component.empty());
            for (String line : site.history()) {
                lore.add(mc.mini().deserialize(applySitePlaceholders(line, site)));
            }
        }

        lore.add(Component.empty());

        if (ownTown) {
            lore.add(mc.get("holy-sites.lore.already-built-own"));
        } else if (owner != null) {
            lore.add(mc.getUnescaped("holy-sites.lore.already-built-other",
                    Map.of("town", owner)));
        } else if (plugin.holySiteService().hasSite(townUuid)) {
            lore.add(mc.get("holy-sites.lore.site-limit"));
        } else {
            lore.add(mc.get("holy-sites.lore.click-to-build"));
        }

        return new ItemBuilder(site.icon())
                .name(header)
                .lore(lore)
                .glow(owner != null)
                .build();
    }

    private @NotNull String religionDisplay(@NotNull Religion religion) {
        String base = "religions." + religion.key();
        String display = plugin.institutesConfig().display(base + ".display");
        if (display == null || display.isBlank()) display = religion.name();

        String symbol = plugin.institutesConfig().symbol(base + ".symbol");
        String color = plugin.institutesConfig().color(base + ".color");
        String gradFrom = plugin.institutesConfig().gradFrom(base + ".grad-from");
        String gradTo = plugin.institutesConfig().gradTo(base + ".grad-to");

        String iconColor = toMiniColorTag(color);

        // Формат как в /t religion / меню институтов: [символ] + градиентное имя.
        return "<!italic><gray>[</gray>" + iconColor + symbol
                + "<gray>]</gray> <gradient:" + gradFrom + ":" + gradTo + "><bold>"
                + display + "</bold></gradient>";
    }

    private static @NotNull String applySitePlaceholders(@NotNull String line,
                                                         @NotNull HolySiteConfig site) {
        return line.replace("{icon-color}", site.color())
                   .replace("{grad-from}", site.gradFrom())
                   .replace("{grad-to}", site.gradTo());
    }

    private static @NotNull String toMiniColorTag(@Nullable String color) {
        if (color == null || color.isBlank()) return "<#ffffff>";
        String c = color.trim();
        if (c.startsWith("<") && c.endsWith(">")) return c;
        if (c.startsWith("#")) return "<" + c + ">";
        if (c.matches("(?i)[0-9a-f]{6}")) return "<#" + c + ">";
        return c;
    }

    @Override
    public void onClick(@NotNull Player player, int slot, @NotNull InventoryClickEvent event) {
        event.setCancelled(true);

        if (!plugin.getConfigManager().features().holySites()
                || plugin.holySiteService() == null) {
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

        String siteId = slotToSite.get(slot);
        if (siteId == null) return;

        if (!plugin.menuManager().tryLockPlayer(player)) return;

        try {
            plugin.holySiteService().tryBuild(townUuid, siteId, player, result -> {
                plugin.menuManager().unlockPlayer(player);
                handleResult(player, result);
            });
        } catch (Throwable t) {
            plugin.menuManager().unlockPlayer(player);
            plugin.getLogger().severe("[HolySiteMenu] tryBuild: " + t);
            player.sendMessage(plugin.getMessagesConfig().get("general.internal-error"));
        }
    }

    private void handleResult(@NotNull Player player, @NotNull HolyBuildResult result) {
        var mc = plugin.getMessagesConfig();
        switch (result.status()) {
            case SUCCESS -> {
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1F, 1F);
                String siteName = result.site() != null ? result.site().displayName() : "?";
                Map<String, String> ph = Map.of("site", siteName);
                player.sendMessage(mc.get("holy-sites.upgrade.success", ph));
                plugin.soundService().showTitle(
                        player,
                        mc.get("holy-sites.upgrade.success-title", ph),
                        mc.get("holy-sites.upgrade.success-subtitle", ph));
                refreshIfOpen();
            }
            case ALREADY_OWNED -> {
                player.sendActionBar(mc.get("holy-sites.lore.site-limit"));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7F, 1.5F);
            }
            case ALREADY_TAKEN -> {
                player.sendActionBar(mc.get("holy-sites.upgrade.already-taken",
                        Map.of("town", result.ownerTownName() != null
                                ? result.ownerTownName() : "?")));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7F, 1.5F);
            }
            case NO_SCROLL -> {
                player.sendActionBar(mc.get("holy-sites.upgrade.no-scroll"));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7F, 1.2F);
            }
            case WRONG_RELIGION -> {
                player.sendActionBar(mc.get("holy-sites.upgrade.wrong-religion"));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7F, 1.2F);
            }
            case BUSY -> player.sendActionBar(mc.get("buildings.upgrade.busy"));
            case TIMEOUT -> player.sendActionBar(mc.get("buildings.upgrade.timeout"));
            case FEATURE_DISABLED -> player.sendActionBar(mc.get("general.internal-error"));
            default -> player.sendMessage(mc.get("general.internal-error"));
        }
    }

    private void refreshIfOpen() {
        if (!player.isOnline()) return;
        var top = player.getOpenInventory().getTopInventory();
        if (top.getHolder() != this) return;
        refresh();
    }
}
