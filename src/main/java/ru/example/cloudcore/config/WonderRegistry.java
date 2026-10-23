package ru.example.cloudcore.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;

public final class WonderRegistry {

    private final JavaPlugin plugin;
    private final File file;

    private volatile int baseSlots = 1;
    private volatile Map<WonderRarity, WonderRarityStyle> rarityStyles = Map.of();
    private volatile Map<String, WonderConfig> byId = Map.of();
    private volatile List<WonderConfig> all = List.of();

    public WonderRegistry(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "wonders.yml");
    }

    public void load() {
        if (!file.exists()) plugin.saveResource("wonders.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        this.baseSlots = Math.max(0, cfg.getInt("base-slots", 1));

        Map<WonderRarity, WonderRarityStyle> styles = new EnumMap<>(WonderRarity.class);
        ConfigurationSection rarSec = cfg.getConfigurationSection("rarities");
        if (rarSec != null) {
            for (String key : rarSec.getKeys(false)) {
                try {
                    WonderRarity r = WonderRarity.valueOf(key.toUpperCase(Locale.ROOT));
                    ConfigurationSection rs = rarSec.getConfigurationSection(key);
                    if (rs == null) continue;
                    styles.put(r, new WonderRarityStyle(
                            rs.getString("display-name", key),
                            rs.getString("color", "<gray>"),
                            rs.getString("grad-from", "#888888"),
                            rs.getString("grad-to", "#444444")));
                } catch (IllegalArgumentException ignored) {}
            }
        }

        Map<String, WonderConfig> byId = new LinkedHashMap<>();
        List<WonderConfig> all = new ArrayList<>();
        ConfigurationSection wSec = cfg.getConfigurationSection("wonders");
        if (wSec != null) {
            for (String id : wSec.getKeys(false)) {
                ConfigurationSection sec = wSec.getConfigurationSection(id);
                if (sec == null) continue;
                try {
                    WonderConfig wc = parse(id, sec);
                    byId.put(id, wc);
                    all.add(wc);
                } catch (Exception e) {
                    plugin.getLogger().severe("[Wonders] Ошибка парсинга '" + id + "': " + e.getMessage());
                }
            }
        }

        this.rarityStyles = Map.copyOf(styles);
        this.byId = Collections.unmodifiableMap(byId);
        this.all = List.copyOf(all);
        plugin.getLogger().info("[Wonders] Загружено чудес: " + all.size()
                + ", base-slots=" + baseSlots);
    }

    private @NotNull WonderConfig parse(@NotNull String id, @NotNull ConfigurationSection sec) {
        String displayName = Objects.requireNonNull(sec.getString("display-name"),
                "display-name отсутствует у " + id);
        WonderRarity rarity = WonderRarity.valueOf(
                Objects.requireNonNull(sec.getString("rarity"), "rarity у " + id)
                        .toUpperCase(Locale.ROOT));

        Material icon = BuildingDefinitionRegistry.resolveMaterial(
                Objects.requireNonNull(sec.getString("icon"), "icon у " + id));
        if (icon == null) throw new IllegalArgumentException("Unknown icon у " + id);

        String symbol   = sec.getString("symbol", "❖");
        String color    = sec.getString("color", "<gray>");
        String gradFrom = sec.getString("grad-from", "#888888");
        String gradTo   = sec.getString("grad-to", "#444444");

        int guiSlot = sec.getInt("gui-slot", -1);
        if (guiSlot < 0 || guiSlot >= 54 || guiSlot == 45 || guiSlot == 53) {
            throw new IllegalArgumentException("gui-slot вне допустимого диапазона у " + id);
        }

        long costCoins = Math.max(0L, sec.getLong("cost-coins", 0L));
        Map<Material, Integer> costItems = new LinkedHashMap<>();
        ConfigurationSection ci = sec.getConfigurationSection("cost-items");
        if (ci != null) {
            for (String mn : ci.getKeys(false)) {
                Material m = BuildingDefinitionRegistry.resolveMaterial(mn);
                if (m == null) throw new IllegalArgumentException("Unknown item '" + mn + "' у " + id);
                int a = ci.getInt(mn, 0);
                if (a > 0) costItems.put(m, a);
            }
        }

        BuildingRewards rewards = parseRewards(sec.getConfigurationSection("rewards"));
        List<EffectSpec> effects = parseEffects(sec.getMapList("effects"));
        List<TimedItem> timedItems = parseTimedItems(sec.getMapList("timed-items"));
        List<String> lore = sec.getStringList("lore");
        int wonderSlotsBonus = Math.max(0, sec.getInt("wonder-slots-bonus", 0));

        return new WonderConfig(id, displayName, rarity, icon, symbol, color, gradFrom, gradTo,
                guiSlot, costCoins, Collections.unmodifiableMap(costItems),
                rewards, effects, timedItems, List.copyOf(lore), wonderSlotsBonus);
    }

    private static @NotNull List<TimedItem> parseTimedItems(@NotNull List<Map<?, ?>> raw) {
        if (raw.isEmpty()) return List.of();
        List<TimedItem> out = new ArrayList<>(raw.size());
        for (Map<?, ?> m : raw) {
            Object itemObj = m.get("item");
            if (itemObj == null) continue;
            Material mat = BuildingDefinitionRegistry.resolveMaterial(itemObj.toString());
            if (mat == null) continue;
            int amount = toInt(m.get("amount"), 1);
            int period = toInt(m.get("period-hours"), 24);
            if (amount > 0 && period > 0) {
                out.add(new TimedItem(mat, amount, period));
            }
        }
        return List.copyOf(out);
    }

    private static BuildingRewards parseRewards(ConfigurationSection r) {
        if (r == null) return BuildingRewards.EMPTY;

        long townCoins    = r.getLong("town-coins-per-hour", 0L);
        long citizenCoins = r.getLong("citizen-coins-per-hour", 0L);
        int chunks        = r.getInt("town-chunks-bonus", 0);
        double tax        = r.getDouble("tax-reduction-percent", 0.0);
        double exp        = r.getDouble("exp-bonus-percent", 0.0);
        double harvest    = r.getDouble("harvest-bonus-percent", 0.0);
        double mint       = r.getDouble("mint-income-bonus-percent", 0.0);
        long twPoints     = r.getLong("townywar-starting-points", 0L);
        int  twFlags      = r.getInt("townywar-max-flags-bonus", 0);
        double twKill     = r.getDouble("townywar-kill-points-multiplier", 1.0);
        int slotsBonus    = r.getInt("wonder-slots-bonus", 0);
        double claimDisc  = r.getDouble("claim-discount-percent", 0.0);
        double production = r.getDouble("production-bonus-percent", 0.0);

        // ★ 5a — health-бонус чудес (опционально, на будущее)
        double health     = r.getDouble("health-bonus-hearts", 0.0);
        if (health < 0) health = 0;

        Map<Material, Integer> stock = new LinkedHashMap<>();
        ConfigurationSection si = r.getConfigurationSection("stock-items-per-hour");
        if (si != null) {
            for (String mn : si.getKeys(false)) {
                Material m = BuildingDefinitionRegistry.resolveMaterial(mn);
                if (m == null) continue;
                int a = si.getInt(mn, 0);
                if (a > 0) stock.put(m, a);
            }
        }

        return new BuildingRewards(townCoins, citizenCoins,
                Collections.unmodifiableMap(stock), chunks, tax, exp, harvest, mint,
                twPoints, twFlags, twKill, slotsBonus, claimDisc, production,
                health);   // ★ 5a
    }

    private static List<EffectSpec> parseEffects(List<Map<?, ?>> raw) {
        if (raw.isEmpty()) return List.of();
        List<EffectSpec> out = new ArrayList<>(raw.size());
        for (Map<?, ?> m : raw) {
            Object typeObj = m.get("type");
            if (typeObj == null) continue;
            PotionEffectType type = BuildingDefinitionRegistry.resolvePotionEffect(typeObj.toString());
            if (type == null) continue;
            int amplifier = toInt(m.get("amplifier"), 0);
            String modeStr = (m.get("mode") == null ? "PERMANENT" : m.get("mode").toString())
                    .toUpperCase(Locale.ROOT);
            EffectSpec.Mode mode;
            try { mode = EffectSpec.Mode.valueOf(modeStr); }
            catch (IllegalArgumentException e) { mode = EffectSpec.Mode.PERMANENT; }

            if (mode == EffectSpec.Mode.PERMANENT) {
                out.add(EffectSpec.permanent(type, amplifier));
            } else {
                int interval = toInt(m.get("interval-seconds"), 60);
                int duration = toInt(m.get("duration-seconds"), 10);
                if (interval > 0 && duration > 0) {
                    out.add(EffectSpec.periodic(type, amplifier, interval, duration));
                }
            }
        }
        return List.copyOf(out);
    }

    private static int toInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString()); } catch (NumberFormatException e) { return def; }
    }

    public int baseSlots() { return baseSlots; }
    public @NotNull Map<WonderRarity, WonderRarityStyle> rarityStyles() { return rarityStyles; }
    public @NotNull Optional<WonderConfig> byId(@NotNull String id) {
        return Optional.ofNullable(byId.get(id));
    }
    public @NotNull Collection<WonderConfig> all() {
        return Collections.unmodifiableCollection(all);
    }
    public @NotNull WonderRarityStyle rarityStyle(@NotNull WonderRarity r) {
        return rarityStyles.getOrDefault(r,
                new WonderRarityStyle(r.name(), "<gray>", "#888888", "#444444"));
    }
}