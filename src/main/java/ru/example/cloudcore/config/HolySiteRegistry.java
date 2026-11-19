package ru.example.cloudcore.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.institutes.data.Religion;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Реестр Священных мест (holy-sites.yml).
 *
 * Помимо самих мест хранит секрет подписи свитков. Секрет создаётся один раз
 * при первом запуске и сохраняется в holy-sites.yml — благодаря этому
 * выданные свитки остаются валидными после перезапуска сервера.
 */
public final class HolySiteRegistry {

    private final JavaPlugin plugin;
    private final File file;

    private volatile String scrollSecret = "";
    private volatile Map<String, HolySiteConfig> byId = Map.of();
    private volatile List<HolySiteConfig> all = List.of();

    public HolySiteRegistry(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "holy-sites.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("holy-sites.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        this.scrollSecret = ensureSecret(cfg);

        Map<String, HolySiteConfig> byId = new LinkedHashMap<>();
        List<HolySiteConfig> all = new ArrayList<>();
        ConfigurationSection sSec = cfg.getConfigurationSection("sites");
        if (sSec != null) {
            for (String id : sSec.getKeys(false)) {
                ConfigurationSection sec = sSec.getConfigurationSection(id);
                if (sec == null) continue;
                try {
                    HolySiteConfig hc = parse(id, sec);
                    byId.put(id, hc);
                    all.add(hc);
                } catch (Exception e) {
                    plugin.getLogger().severe("[HolySites] Ошибка парсинга '" + id + "': "
                            + e.getMessage());
                }
            }
        }

        this.byId = Collections.unmodifiableMap(byId);
        this.all = List.copyOf(all);
        plugin.getLogger().info("[HolySites] Загружено мест: " + all.size());
    }

    private @NotNull String ensureSecret(@NotNull YamlConfiguration cfg) {
        String existing = cfg.getString("scroll-secret");
        if (existing != null && existing.length() >= 16) return existing;

        String generated = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        cfg.set("scroll-secret", generated);
        try {
            cfg.save(file);
            plugin.getLogger().info("[HolySites] Сгенерирован секрет подписи свитков.");
        } catch (IOException e) {
            plugin.getLogger().severe("[HolySites] Не удалось сохранить scroll-secret: "
                    + e.getMessage() + " — старые свитки станут невалидны после рестарта.");
        }
        return generated;
    }

    private @NotNull HolySiteConfig parse(@NotNull String id, @NotNull ConfigurationSection sec) {
        String displayName = Objects.requireNonNull(sec.getString("display-name"),
                "display-name отсутствует у " + id);

        Material icon = BuildingDefinitionRegistry.resolveMaterial(
                Objects.requireNonNull(sec.getString("icon"), "icon у " + id));
        if (icon == null) throw new IllegalArgumentException("Unknown icon у " + id);

        String symbol   = sec.getString("symbol", "❈");
        String color    = sec.getString("color", "<gray>");
        String gradFrom = sec.getString("grad-from", "#888888");
        String gradTo   = sec.getString("grad-to", "#444444");

        int guiSlot = sec.getInt("gui-slot", -1);
        if (guiSlot < 0 || guiSlot >= 54 || guiSlot == 45 || guiSlot == 53) {
            throw new IllegalArgumentException("gui-slot вне допустимого диапазона у " + id);
        }

        Religion religion = Religion.byKey(sec.getString("religion"));
        if (religion == null) {
            throw new IllegalArgumentException("Неизвестная religion у " + id);
        }

        String scrollRaw = sec.getString("scroll");
        HolyScrollType scrollType = HolyScrollType.byKey(scrollRaw);
        if (scrollType == null) {
            if (scrollRaw != null && !scrollRaw.isBlank()) {
                plugin.getLogger().warning("[HolySites] Неизвестный scroll '" + scrollRaw
                        + "' у " + id + " — используется MYSTERY.");
            }
            scrollType = HolyScrollType.MYSTERY;
        }

        BuildingRewards rewards = parseRewards(sec.getConfigurationSection("rewards"));
        List<TimedItem> timedItems = parseTimedItems(sec.getMapList("timed-items"));
        HolySiteEffects effects = parseEffects(sec.getConfigurationSection("bonuses"));
        List<String> lore = sec.getStringList("lore");
        List<String> history = sec.getStringList("history");

        return new HolySiteConfig(id, displayName, icon, symbol, color,
                gradFrom, gradTo, guiSlot, religion, scrollType, rewards, timedItems,
                effects, List.copyOf(lore), List.copyOf(history));
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
            int periodMinutes = toInt(m.get("period-minutes"), -1);
            if (periodMinutes <= 0) {
                periodMinutes = toInt(m.get("period-hours"), 24) * 60;
            }
            if (amount > 0 && periodMinutes > 0) {
                out.add(new TimedItem(mat, amount, periodMinutes));
            }
        }
        return List.copyOf(out);
    }

    private static @NotNull BuildingRewards parseRewards(ConfigurationSection r) {
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
        double health     = Math.max(0.0, r.getDouble("health-bonus-hearts", 0.0));

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
                twPoints, twFlags, twKill, slotsBonus, claimDisc, production, health);
    }

    private static @NotNull HolySiteEffects parseEffects(ConfigurationSection b) {
        if (b == null) return HolySiteEffects.EMPTY;
        return new HolySiteEffects(
                b.getBoolean("double-raw-meat", false),
                b.getDouble("damage-vs-religious-percent", 0.0),
                b.getDouble("potion-duration-multiplier", 1.0),
                b.getDouble("low-health-damage-percent", 0.0),
                b.getDouble("low-health-threshold-percent", 40.0),
                b.getInt("gladiator-regen-seconds", 0),
                b.getInt("gladiator-regen-amplifier", 0),
                b.getDouble("monster-damage-multiplier", 1.0),
                b.getDouble("dodge-chance-percent", 0.0)
        ).withDefaults();
    }

    private static int toInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString()); } catch (NumberFormatException e) { return def; }
    }

    public @NotNull String scrollSecret() { return scrollSecret; }

    public @NotNull Optional<HolySiteConfig> byId(@NotNull String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public @NotNull List<HolySiteConfig> all() { return all; }
}
