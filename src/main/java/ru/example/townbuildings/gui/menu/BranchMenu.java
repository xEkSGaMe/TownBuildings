package ru.example.townbuildings.gui.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.config.BuildingBranch;
import ru.example.townbuildings.config.BuildingDefinition;
import ru.example.townbuildings.config.BuildingLevel;
import ru.example.townbuildings.database.model.BuildingData;
import ru.example.townbuildings.gui.Menu;
import ru.example.townbuildings.gui.util.ItemBuilder;
import ru.example.townbuildings.service.ResourceCheck;
import ru.example.townbuildings.service.UpgradeResult;
import ru.example.townbuildings.util.RomanNumbers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Меню одной ветки.
 *
 * M-5 — checks: HashMap вместо ConcurrentHashMap. Все чтения/записи
 *       идут из main-thread (render/onClick/computeChecksOnMain);
 *       CHM давал лишний overhead и ложную видимость cross-thread-безопасности.
 * L-12 — refresh вызывается только если игрок всё ещё смотрит ЭТО меню.
 *       Раньше async-цепочка апгрейда звала refresh() даже после закрытия/
 *       перехода в другую ветку — лишняя работа + actionBar'ы уходили
 *       «в пустоту» (в другой GUI/чат).
 */
public final class BranchMenu extends Menu {

    private static final int SLOT_BACK_LEFT  = 36;
    private static final int SLOT_BACK_RIGHT = 44;

    private static final Map<String, Integer> ECONOMY_SLOTS = Map.of(
            "mint",        11,
            "city_bank",   13,
            "treasury",    15,
            "gold_mine",   29,
            "tax_office",  31,
            "trade_house", 33
    );

    private static final Map<BuildingBranch, int[]> BRANCH_SLOTS = Map.of(
            BuildingBranch.MILITARY,   new int[]{10, 12, 14, 16, 22, 28, 30, 32, 34},
            BuildingBranch.TECH,       new int[]{11, 13, 15, 31},
            BuildingBranch.MANAGEMENT, new int[]{11, 13, 15, 31},
            BuildingBranch.PRODUCTION, new int[]{11, 13, 15, 29, 31, 33}
    );

    private final UUID townUuid;
    private final String townName;
    private final BuildingBranch branch;
    private final Menu parent;

    private final Map<Integer, String> slotToBuilding = new HashMap<>();
    private volatile Map<String, Integer> levels = Map.of();

    // ★ M-5 — все обращения идут из main; CHM не нужен.
    private final Map<String, ResourceCheck> checks = new HashMap<>();

    public BranchMenu(@NotNull TownBuildingsPlugin plugin, @NotNull Player player,
                      @NotNull UUID townUuid, @NotNull String townName,
                      @NotNull BuildingBranch branch, @Nullable Menu parent) {
        super(plugin, player);
        this.townUuid = townUuid;
        this.townName = townName;
        this.branch = branch;
        this.parent = parent;
    }

    @Override
    protected int size() {
        return branch == BuildingBranch.MILITARY ? 54 : 45;
    }

    @Override
    protected @NotNull Component title(@NotNull Player player) {
        return plugin.getMessagesConfig().get("gui.branch-title",
                Map.of("branch", branch.displayName(), "town", townName));
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

        slotToBuilding.clear();
        List<BuildingDefinition> defs = plugin.buildingRegistry().ofBranch(branch);

        for (int idx = 0; idx < defs.size(); idx++) {
            BuildingDefinition def = defs.get(idx);
            Integer slot = resolveSlot(def, idx);
            if (slot == null) continue;
            slotToBuilding.put(slot, def.id());

            int currentLevel = levels.getOrDefault(def.id(), 0);
            ResourceCheck check = checks.get(def.id());
            inventory.setItem(slot, buildIcon(def, currentLevel, check));
        }
    }

    private @Nullable Integer resolveSlot(@NotNull BuildingDefinition def, int idx) {
        if (branch == BuildingBranch.ECONOMY) {
            return ECONOMY_SLOTS.get(def.id());
        }
        int[] slots = BRANCH_SLOTS.get(branch);
        if (slots == null || idx < 0 || idx >= slots.length) return null;
        return slots[idx];
    }

    public void openAndLoad() {
        plugin.menuManager().register(player, this);
        open();
        loadAndRender();
    }

    public void loadAndRender() {
        plugin.townService().getBuildings(townUuid).whenComplete((list, ex) -> {
            if (ex != null || list == null) {
                plugin.getLogger().warning("[BranchMenu] getBuildings: " + ex);
                Bukkit.getScheduler().runTask(plugin, this::refreshIfOpen);
                return;
            }
            Map<String, Integer> lv = new HashMap<>();
            for (BuildingData b : list) lv.put(b.buildingId(), b.level());
            this.levels = Map.copyOf(lv);
            computeChecksThenRefresh();
        });
    }

    private void computeChecksThenRefresh() {
        Bukkit.getScheduler().runTask(plugin, this::computeChecksOnMain);
    }

    private void computeChecksOnMain() {
        List<BuildingDefinition> defs = plugin.buildingRegistry().ofBranch(branch);
        if (defs.isEmpty()) { refreshIfOpen(); return; }

        var town = plugin.towny().getTownByUuid(townUuid);
        if (town == null) { refreshIfOpen(); return; }
        long townBalance = (long) Math.floor(plugin.economyService().getTownBalance(town));

        plugin.inventoryService().countAll(townUuid).whenComplete((snapshot, ex) -> {
            Map<Material, Integer> stock = (ex == null && snapshot != null) ? snapshot : Map.of();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Map<String, ResourceCheck> computed = new HashMap<>(defs.size());
                for (BuildingDefinition def : defs) {
                    int currentLevel = levels.getOrDefault(def.id(), 0);
                    BuildingLevel next = def.nextLevel(currentLevel).orElse(null);
                    if (next == null) { computed.put(def.id(), ResourceCheck.trivial()); continue; }
                    ResourceCheck.Builder b = ResourceCheck.builder()
                            .coins(next.costCoins(), townBalance);
                    for (Map.Entry<Material, Integer> e : next.costItems().entrySet()) {
                        b.item(e.getKey(), e.getValue(), stock.getOrDefault(e.getKey(), 0));
                    }
                    computed.put(def.id(), b.build());
                }
                checks.clear();
                checks.putAll(computed);
                // ★ L-12 — refresh только если игрок всё ещё смотрит это меню.
                refreshIfOpen();
            });
        });
    }

    private @NotNull ItemStack buildIcon(@NotNull BuildingDefinition def,
                                         int currentLevel,
                                         @Nullable ResourceCheck check) {
        var mc = plugin.getMessagesConfig();
        String branchKey  = "gui.branch-meta." + def.branch().name();
        String iconColor  = mc.getRaw(branchKey + ".color");
        String gradFrom   = mc.getRaw(branchKey + ".grad-from");
        String gradTo     = mc.getRaw(branchKey + ".grad-to");

        String iconSymbol = mc.getRaw("gui.building-symbols." + def.id());
        if (iconSymbol.isEmpty()) iconSymbol = "❖";

        BuildingLevel next = def.nextLevel(currentLevel).orElse(null);
        boolean atMax = (next == null);

        int shownLevel = atMax ? currentLevel : next.level();
        if (shownLevel < 1) shownLevel = 1;

        Map<String, String> iconPh = new HashMap<>();
        iconPh.put("icon-color",  iconColor);
        iconPh.put("icon-symbol", iconSymbol);
        iconPh.put("grad-from",   gradFrom);
        iconPh.put("grad-to",     gradTo);
        iconPh.put("name",        def.displayName());
        iconPh.put("max",         RomanNumbers.toRoman(def.maxLevel()));
        iconPh.put("roman",       RomanNumbers.toRoman(shownLevel));

        Map<String, String> colorPh = Map.of("icon-color", iconColor);

        Component headerComponent = mc.getUnescaped(
                "gui.building-icon.header-with-level", iconPh);

        List<Component> lore = new ArrayList<>();

        if (def.inDevelopment()) {
            lore.add(mc.get("buildings.lore.in-development-warning"));
            lore.add(mc.get("buildings.lore.in-development-hint"));
        }

        if (!def.inDevelopment() && !atMax) {
            lore.add(Component.empty());
            lore.add(mc.getUnescaped("gui.building-icon.section-conditions", colorPh));
            lore.add(Component.empty());

            if (currentLevel <= 0) {
                lore.add(mc.getUnescaped("gui.building-icon.line-building", colorPh));
            } else {
                String prevName = def.displayName() + " "
                        + RomanNumbers.toRoman(currentLevel);
                lore.add(mc.getUnescaped("gui.building-icon.line-building-prev", Map.of(
                        "icon-color", iconColor,
                        "prev",       prevName)));
            }

            if (next.costCoins() > 0) {
                lore.add(mc.getUnescaped("gui.building-icon.line-coins", Map.of(
                        "icon-color", iconColor,
                        "coins",      Long.toString(next.costCoins()))));
            }

            if (!next.costItems().isEmpty()) {
                lore.add(mc.getUnescaped("gui.building-icon.line-items-header", colorPh));
                for (Map.Entry<Material, Integer> e : next.costItems().entrySet()) {
                    lore.add(mc.getUnescaped("gui.building-icon.line-item", Map.of(
                            "icon-color", iconColor,
                            "amount",     Integer.toString(e.getValue()),
                            "item",       formatItemName(e.getKey()))));
                }
            }
        }

        if (!def.inDevelopment()) {
            lore.add(Component.empty());
            lore.add(mc.getUnescaped("gui.building-icon.section-upgrades", colorPh));
            lore.add(Component.empty());

            if (currentLevel > 0) {
                List<String> summary = sumValueLines(def, currentLevel, iconColor);
                if (!summary.isEmpty()) {
                    for (String s : summary) {
                        lore.add(mc.mini().deserialize(s));
                    }
                    lore.add(Component.empty());
                }
            }

            String baselineValue = customValue(def, 0);
            if (baselineValue != null) {
                lore.add(mc.getUnescaped("gui.building-icon.line-level-base",
                        merge(colorPh, "value", baselineValue)));
            }

            for (int lvl = 1; lvl <= def.maxLevel(); lvl++) {
                List<String> lines = new ArrayList<>();
                String custom = customValue(def, lvl);
                if (custom != null) {
                    lines.add(custom);
                } else {
                    lines.addAll(perLevelValueLines(def, lvl, iconColor));
                }
                if (lines.isEmpty()) continue;

                lore.add(mc.getUnescaped("gui.building-icon.line-level",
                        merge(colorPh,
                                "roman", RomanNumbers.toRoman(lvl),
                                "value", lines.get(0))));
                for (int j = 1; j < lines.size(); j++) {
                    lore.add(mc.getUnescaped("gui.building-icon.line-level-cont",
                            merge(colorPh, "value", lines.get(j))));
                }
            }
        }

        if (!def.inDevelopment()) {
            lore.add(Component.empty());
            if (atMax) lore.add(mc.get("buildings.lore.max-level"));
            else       lore.add(mc.get("buildings.lore.click-to-upgrade"));
        }

        return new ItemBuilder(def.icon())
                .name(headerComponent)
                .lore(lore)
                .glow(currentLevel > 0)
                .build();
    }

    // ============================================================
    //   Сводка
    // ============================================================

    private @NotNull List<String> sumValueLines(@NotNull BuildingDefinition def,
                                                int upTo,
                                                @NotNull String iconColor) {
        long coins = 0, citizenCoins = 0;
        int chunks = 0;
        double tax = 0, exp = 0, harvest = 0, mint = 0;
        Map<Material, Integer> stock = new LinkedHashMap<>();

        for (int lvl = 1; lvl <= upTo; lvl++) {
            BuildingLevel bl = def.level(lvl).orElse(null);
            if (bl == null) continue;
            var r = bl.rewards();
            coins += r.townCoinsPerHour();
            citizenCoins += r.citizenCoinsPerHour();
            for (var e : r.stockItemsPerHour().entrySet()) {
                stock.merge(e.getKey(), e.getValue(), Integer::sum);
            }
            if (r.townChunksBonus() > 0)          chunks  = r.townChunksBonus();
            if (r.taxReductionPercent() > 0)      tax     = r.taxReductionPercent();
            if (r.expBonusPercent() > 0)          exp     = r.expBonusPercent();
            if (r.harvestBonusPercent() > 0)      harvest = r.harvestBonusPercent();
            if (r.mintIncomeBonusPercent() > 0)   mint    = r.mintIncomeBonusPercent();
        }

        List<String> out = buildValueLines(def, coins, citizenCoins, stock,
                chunks, tax, exp, harvest, mint, iconColor);
        if (!out.isEmpty()) return out;

        String gain = customGainString(def, upTo);
        if (gain != null) {
            out.add(ensureNotItalic(plugin.getMessagesConfig()
                    .getRaw("gui.building-icon.value-warehouse")
                    .replace("{icon-color}", iconColor)
                    .replace("{value}", gain)));
            return out;
        }
        return List.of();
    }

    private @NotNull List<String> perLevelValueLines(@NotNull BuildingDefinition def,
                                                     int lvl,
                                                     @NotNull String iconColor) {
        BuildingLevel bl = def.level(lvl).orElse(null);
        if (bl == null) return List.of();

        var r = bl.rewards();
        List<String> out = buildValueLines(def,
                r.townCoinsPerHour(), r.citizenCoinsPerHour(),
                new LinkedHashMap<>(r.stockItemsPerHour()),
                r.townChunksBonus(), r.taxReductionPercent(),
                r.expBonusPercent(), r.harvestBonusPercent(),
                r.mintIncomeBonusPercent(), iconColor);

        if (out.isEmpty() && !bl.bonusLore().isEmpty()) {
            for (String line : bl.bonusLore()) {
                out.add(prepareBonusLoreWhite(line));
            }
        }
        return out;
    }

    private @NotNull List<String> buildValueLines(@NotNull BuildingDefinition def,
                                                  long coins, long citizenCoins,
                                                  @NotNull Map<Material, Integer> stock,
                                                  int chunks, double tax, double exp,
                                                  double harvest, double mint,
                                                  @NotNull String iconColor) {
        var mc = plugin.getMessagesConfig();
        List<String> out = new ArrayList<>();

        if (coins > 0) {
            out.add(ensureNotItalic(mc.getRaw("gui.building-icon.value-coins")
                    .replace("{icon-color}", iconColor)
                    .replace("{amount}", Long.toString(coins))));
        }
        if (citizenCoins > 0) {
            out.add(ensureNotItalic(mc.getRaw("gui.building-icon.value-citizen-coins")
                    .replace("{icon-color}", iconColor)
                    .replace("{amount}", Long.toString(citizenCoins))));
        }
        if (!stock.isEmpty()) {
            String itemTpl = mc.getRaw("gui.building-icon.value-stock-item");
            if (itemTpl.isEmpty()) itemTpl = "{icon-color}+{amount} {item}";
            String sep = mc.getRaw("gui.building-icon.value-stock-separator");
            if (sep.isEmpty()) sep = "<white>, ";

            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (var e : stock.entrySet()) {
                if (!first) sb.append(sep);
                first = false;
                sb.append(itemTpl
                        .replace("{icon-color}", iconColor)
                        .replace("{amount}", Integer.toString(e.getValue()))
                        .replace("{item}", formatItemName(e.getKey())));
            }
            out.add(ensureNotItalic(mc.getRaw("gui.building-icon.value-stock")
                    .replace("{items}", sb.toString())));
        }
        if (chunks > 0) {
            out.add(ensureNotItalic(mc.getRaw("gui.building-icon.value-chunks")
                    .replace("{icon-color}", iconColor)
                    .replace("{amount}", Integer.toString(chunks))));
        }
        if (tax > 0) {
            out.add(ensureNotItalic(mc.getRaw("gui.building-icon.value-tax")
                    .replace("{icon-color}", iconColor)
                    .replace("{amount}", fmt1(tax))));
        }
        if (exp > 0) {
            out.add(ensureNotItalic(mc.getRaw("gui.building-icon.value-exp")
                    .replace("{icon-color}", iconColor)
                    .replace("{amount}", fmt1(exp))));
        }
        if (harvest > 0) {
            out.add(ensureNotItalic(mc.getRaw("gui.building-icon.value-harvest")
                    .replace("{icon-color}", iconColor)
                    .replace("{amount}", fmt1(harvest))));
        }
        if (mint > 0) {
            out.add(ensureNotItalic(mc.getRaw("gui.building-icon.value-mint")
                    .replace("{icon-color}", iconColor)
                    .replace("{amount}", fmt1(mint))));
        }
        return out;
    }

    private static @NotNull String prepareBonusLoreWhite(@NotNull String s) {
        String r = s.replace("<gray>", "<white>").replace("&7", "<white>");
        return ensureNotItalic(r);
    }

    private static @NotNull String ensureNotItalic(@NotNull String s) {
        if (s.startsWith("<!italic>") || s.startsWith("<!i>")) return s;
        return "<!italic>" + s;
    }

    private @Nullable String customGainString(@NotNull BuildingDefinition def, int upTo) {
        var mc = plugin.getMessagesConfig();
        String key = "gui.building-progress." + def.id();
        if (mc.getRaw(key + ".baseline").isEmpty()) return null;
        int perLevel = parseIntSafe(mc.getRaw(key + ".per-level"), 0);
        int n = perLevel * upTo;
        if (n <= 0) return null;
        String forms = mc.getRaw(key + ".forms");
        String suffix = mc.getRaw(key + ".suffix");
        String word = forms.isEmpty() ? Integer.toString(n) : n + " " + pluralRu(n, forms);
        if (!suffix.isEmpty()) word = word + " " + suffix;
        return word;
    }

    private @Nullable String customValue(@NotNull BuildingDefinition def, int upTo) {
        var mc = plugin.getMessagesConfig();
        String key = "gui.building-progress." + def.id();
        String baselineRaw = mc.getRaw(key + ".baseline");
        if (baselineRaw.isEmpty()) return null;

        int baseline = parseIntSafe(baselineRaw, 0);
        int perLevel = parseIntSafe(mc.getRaw(key + ".per-level"), 0);
        int n = baseline + perLevel * upTo;

        String forms = mc.getRaw(key + ".forms");
        String suffix = mc.getRaw(key + ".suffix");

        String word = forms.isEmpty() ? Integer.toString(n)
                : n + " " + pluralRu(n, forms);
        if (!suffix.isEmpty()) word = word + " " + suffix;
        return word;
    }

    private static @NotNull String pluralRu(int n, @NotNull String forms) {
        String[] f = forms.split("\\|");
        if (f.length < 3) return f[0];
        int n10 = n % 10, n100 = n % 100;
        if (n10 == 1 && n100 != 11) return f[0];
        if (n10 >= 2 && n10 <= 4 && (n100 < 10 || n100 >= 20)) return f[1];
        return f[2];
    }

    private static int parseIntSafe(@NotNull String s, int def) {
        if (s.isEmpty()) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static @NotNull String fmt1(double v) {
        if (v == Math.rint(v)) return Long.toString((long) v);
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private @NotNull String formatItemName(@NotNull Material mat) {
        String custom = plugin.getMessagesConfig().getRaw("gui.item-names." + mat.name());
        if (!custom.isEmpty()) return custom;
        return mat.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static Map<String, String> merge(@NotNull Map<String, String> base,
                                             @NotNull String... kv) {
        Map<String, String> out = new HashMap<>(base);
        for (int i = 0; i + 1 < kv.length; i += 2) out.put(kv[i], kv[i + 1]);
        return out;
    }

    @Override
    public void onClick(@NotNull Player player, int slot, @NotNull InventoryClickEvent event) {
        event.setCancelled(true);
        if (isLocked()) return;

        // ★ C5 — если фича выключена, ничего не апгрейдим.
        if (!plugin.getConfigManager().features().buildings()) {
            player.sendActionBar(plugin.getMessagesConfig().get("buildings.in-development"));
            return;
        }

        // ★ Обе кнопки «назад» ведут в главное меню.
        if (slot == SLOT_BACK_LEFT || slot == SLOT_BACK_RIGHT) {
            if (parent != null) {
                plugin.menuManager().register(player, parent);
                parent.open();
            } else {
                close();
            }
            return;
        }

        String buildingId = slotToBuilding.get(slot);
        if (buildingId == null) return;

        BuildingDefinition def = plugin.buildingRegistry().byId(buildingId).orElse(null);
        if (def == null) return;

        if (def.inDevelopment()) {
            player.sendActionBar(plugin.getMessagesConfig().get("buildings.in-development"));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7F, 0.8F);
            return;
        }

        lock();
        try {
            plugin.buildingService().tryUpgrade(townUuid, buildingId, player, result -> {
                unlock();
                handleResult(player, def, result);
            });
        } catch (Throwable t) {
            unlock();
            plugin.getLogger().severe("[BranchMenu] tryUpgrade: " + t);
            player.sendMessage(plugin.getMessagesConfig().get("general.internal-error"));
        }
    }

    private void handleResult(@NotNull Player player, @NotNull BuildingDefinition def,
                              @NotNull UpgradeResult result) {
        switch (result.status()) {
            case SUCCESS -> {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1F, 1.2F);
                player.sendMessage(plugin.getMessagesConfig().get("buildings.upgrade.success",
                        Map.of("building", def.displayName(),
                                "level", Integer.toString(result.newLevel()),
                                "town", townName)));
                reload();
            }
            case MAX_LEVEL -> {
                player.sendActionBar(plugin.getMessagesConfig().get("buildings.upgrade.max-level"));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7F, 1.5F);
            }
            case IN_DEVELOPMENT -> {
                player.sendActionBar(plugin.getMessagesConfig().get("buildings.upgrade.in-development"));
            }
            case NOT_ENOUGH_RESOURCES -> {
                player.sendActionBar(plugin.getMessagesConfig().get("buildings.upgrade.not-enough"));
                if (result.check() != null) checks.put(def.id(), result.check());
                refreshIfOpen();
            }
            case BUSY -> {
                player.sendActionBar(plugin.getMessagesConfig().get("buildings.upgrade.busy"));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7F, 1.5F);
            }
            case TIMEOUT -> {
                player.sendActionBar(plugin.getMessagesConfig().get("buildings.upgrade.timeout"));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7F, 1.2F);
            }
            case ECONOMY_ERROR, INVENTORY_ERROR, NO_TOWN, UNKNOWN_BUILDING -> {
                player.sendMessage(plugin.getMessagesConfig().get("general.internal-error"));
            }
        }
    }

    private void reload() {
        plugin.townService().cache().invalidateBuildings(townUuid);
        plugin.townService().getBuildings(townUuid).whenComplete((list, ex) -> {
            if (ex != null || list == null) {
                Bukkit.getScheduler().runTask(plugin, this::refreshIfOpen);
                return;
            }
            Map<String, Integer> lv = new HashMap<>();
            for (BuildingData b : list) lv.put(b.buildingId(), b.level());
            this.levels = Map.copyOf(lv);
            checks.clear();
            computeChecksThenRefresh();
        });
    }

    /**
     * ★ L-12 — refresh только если игрок всё ещё смотрит на ЭТО меню.
     * Раньше async-цепочка апгрейда звала refresh() даже после того, как
     * игрок закрыл меню или перешёл в другую ветку: лишняя работа +
     * actionBar'ы могли уходить «в пустоту» (в другой GUI/чат).
     */
    private void refreshIfOpen() {
        if (!player.isOnline()) return;
        var top = player.getOpenInventory().getTopInventory();
        if (top.getHolder() != this) return;
        refresh();
    }
}