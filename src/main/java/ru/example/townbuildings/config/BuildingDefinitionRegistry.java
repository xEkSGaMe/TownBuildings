package ru.example.townbuildings.config;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.logging.Level;

public final class BuildingDefinitionRegistry {

    private final JavaPlugin plugin;
    private final File file;

    private record Snapshot(
            @NotNull Map<String, BuildingDefinition> byId,
            @NotNull Map<BuildingBranch, List<BuildingDefinition>> byBranchDefs
    ) {}

    private volatile Snapshot snapshot =
            new Snapshot(Map.of(), Map.of());

    public BuildingDefinitionRegistry(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "buildings.yml");
    }

    public void load() {
        Map<String, BuildingDefinition> newById = new LinkedHashMap<>();
        Map<BuildingBranch, List<String>> newByBranch = new EnumMap<>(BuildingBranch.class);
        for (BuildingBranch b : BuildingBranch.values()) newByBranch.put(b, new ArrayList<>());

        if (!file.exists()) {
            plugin.saveResource("buildings.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        Set<String> keys = yaml.getKeys(false);
        for (String id : keys) {
            ConfigurationSection sec = yaml.getConfigurationSection(id);
            if (sec == null) {
                plugin.getLogger().warning("[Buildings] Пропуск '" + id + "': не секция.");
                continue;
            }
            try {
                BuildingDefinition def = parse(id, sec);
                newById.put(id, def);
                newByBranch.get(def.branch()).add(id);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.SEVERE,
                        "[Buildings] Ошибка парсинга здания '" + id + "': " + ex.getMessage(), ex);
            }
        }

        Map<BuildingBranch, List<BuildingDefinition>> frozenDefs = new EnumMap<>(BuildingBranch.class);
        for (BuildingBranch b : BuildingBranch.values()) {
            List<String> ids = newByBranch.getOrDefault(b, List.of());
            List<BuildingDefinition> defs = new ArrayList<>(ids.size());
            for (String id : ids) {
                BuildingDefinition def = newById.get(id);
                if (def != null) defs.add(def);
            }
            frozenDefs.put(b, List.copyOf(defs));
        }

        Map<String, BuildingDefinition> frozenById =
                Collections.unmodifiableMap(new LinkedHashMap<>(newById));
        Map<BuildingBranch, List<BuildingDefinition>> frozenByBranch =
                Collections.unmodifiableMap(frozenDefs);

        this.snapshot = new Snapshot(frozenById, frozenByBranch);

        plugin.getLogger().info("[Buildings] Загружено " + frozenById.size() + " зданий.");
    }

    private @NotNull BuildingDefinition parse(@NotNull String id, @NotNull ConfigurationSection sec) {
        String displayName = Objects.requireNonNull(sec.getString("display-name"),
                "display-name отсутствует у " + id);

        String branchName = Objects.requireNonNull(sec.getString("branch"),
                "branch отсутствует у " + id).toUpperCase(Locale.ROOT);
        BuildingBranch branch;
        try {
            branch = BuildingBranch.valueOf(branchName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Неизвестная ветка '" + branchName + "' у " + id);
        }

        String iconName = Objects.requireNonNull(sec.getString("icon"),
                "icon отсутствует у " + id);
        Material icon = resolveMaterial(iconName);
        if (icon == null) {
            throw new IllegalArgumentException("Неизвестный Material '" + iconName + "' у " + id);
        }

        int maxLevel = sec.getInt("max-level", 0);
        if (maxLevel <= 0) {
            throw new IllegalArgumentException("max-level должен быть > 0 у " + id);
        }

        boolean inDevelopment = sec.getBoolean("in-development", false);

        ConfigurationSection levelsSec = sec.getConfigurationSection("levels");
        if (levelsSec == null) {
            throw new IllegalArgumentException("Секция levels отсутствует у " + id);
        }

        Map<Integer, BuildingLevel> levels = new LinkedHashMap<>();
        for (String lvlKey : levelsSec.getKeys(false)) {
            int lvl;
            try {
                lvl = Integer.parseInt(lvlKey);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Уровень '" + lvlKey + "' не число у " + id);
            }
            if (lvl < 1 || lvl > maxLevel) {
                throw new IllegalArgumentException("Уровень " + lvl + " вне диапазона 1.." + maxLevel + " у " + id);
            }
            ConfigurationSection ls = levelsSec.getConfigurationSection(lvlKey);
            if (ls == null) continue;

            List<String> bonusLore = ls.getStringList("bonus-lore");
            long costCoins = ls.getLong("cost-coins", 0L);

            Map<Material, Integer> costItems = new LinkedHashMap<>();
            ConfigurationSection itemsSec = ls.getConfigurationSection("cost-items");
            if (itemsSec != null) {
                for (String matName : itemsSec.getKeys(false)) {
                    Material mat = resolveMaterial(matName);
                    if (mat == null) {
                        throw new IllegalArgumentException("Неизвестный Material '" + matName
                                + "' в стоимости уровня " + lvl + " у " + id);
                    }
                    int amount = itemsSec.getInt(matName, 0);
                    if (amount <= 0) {
                        throw new IllegalArgumentException("Количество для '" + matName
                                + "' должно быть > 0 (уровень " + lvl + " у " + id + ")");
                    }
                    costItems.put(mat, amount);
                }
            }

            if (costCoins < 0) {
                throw new IllegalArgumentException("cost-coins < 0 (уровень " + lvl + " у " + id + ")");
            }

            BuildingRewards rewards = parseRewards(ls, id, lvl);
            List<EffectSpec> effects = parseEffects(ls, id, lvl);

            levels.put(lvl, new BuildingLevel(lvl, List.copyOf(bonusLore), costCoins,
                    Collections.unmodifiableMap(new LinkedHashMap<>(costItems)),
                    rewards, effects));
        }

        int effectiveMax = maxLevel;
        for (int lvl = 1; lvl <= maxLevel; lvl++) {
            if (!levels.containsKey(lvl)) {
                effectiveMax = lvl - 1;
                plugin.getLogger().warning("[Buildings] У здания '" + id
                        + "' пропущен уровень " + lvl + " — max-level обрезан до "
                        + effectiveMax);
                break;
            }
        }
        if (effectiveMax < 1) {
            throw new IllegalArgumentException("У здания '" + id
                    + "' нет непрерывной последовательности уровней (max-level="
                    + maxLevel + ")");
        }

        return new BuildingDefinition(id, displayName, branch, icon, effectiveMax,
                inDevelopment, Collections.unmodifiableMap(new LinkedHashMap<>(levels)));
    }

    private @NotNull BuildingRewards parseRewards(@NotNull ConfigurationSection ls,
                                                  @NotNull String id, int lvl) {
        ConfigurationSection r = ls.getConfigurationSection("rewards");
        if (r == null) return BuildingRewards.EMPTY;

        long townCoins = r.getLong("town-coins-per-hour", 0L);
        long citizenCoins = r.getLong("citizen-coins-per-hour", 0L);
        int townChunks = r.getInt("town-chunks-bonus", 0);
        double tax = r.getDouble("tax-reduction-percent", 0.0);
        double exp = r.getDouble("exp-bonus-percent", 0.0);
        double harvest = r.getDouble("harvest-bonus-percent", 0.0);
        double mintIncome = r.getDouble("mint-income-bonus-percent", 0.0);

        long townyWarStartingPoints = r.getLong("townywar-starting-points", 0L);
        int  townyWarMaxFlagsBonus  = r.getInt("townywar-max-flags-bonus", 0);
        double townyWarKillPointsMultiplier =
                r.getDouble("townywar-kill-points-multiplier", 1.0);

        int    wonderSlotsBonus     = r.getInt("wonder-slots-bonus", 0);
        double claimDiscountPercent = r.getDouble("claim-discount-percent", 0.0);

        Map<Material, Integer> stockItems = new LinkedHashMap<>();
        ConfigurationSection si = r.getConfigurationSection("stock-items-per-hour");
        if (si != null) {
            for (String matName : si.getKeys(false)) {
                Material mat = resolveMaterial(matName);
                if (mat == null) {
                    throw new IllegalArgumentException("Неизвестный Material '" + matName
                            + "' в rewards уровня " + lvl + " у " + id);
                }
                int amount = si.getInt(matName, 0);
                if (amount > 0) stockItems.put(mat, amount);
            }
        }

        return new BuildingRewards(townCoins, citizenCoins,
                Collections.unmodifiableMap(new LinkedHashMap<>(stockItems)),
                townChunks, tax, exp, harvest, mintIncome,
                townyWarStartingPoints, townyWarMaxFlagsBonus, townyWarKillPointsMultiplier,
                wonderSlotsBonus, claimDiscountPercent);
    }

    private static @NotNull List<EffectSpec> parseEffects(@NotNull ConfigurationSection ls,
                                                          @NotNull String id, int lvl) {
        List<Map<?, ?>> raw = ls.getMapList("effects");
        if (raw.isEmpty()) return List.of();

        List<EffectSpec> out = new ArrayList<>(raw.size());
        for (Map<?, ?> m : raw) {
            Object typeObj = m.get("type");
            if (typeObj == null) {
                throw new IllegalArgumentException("effect.type отсутствует (уровень "
                        + lvl + " у " + id + ")");
            }
            PotionEffectType type = resolvePotionEffect(typeObj.toString());
            if (type == null) {
                throw new IllegalArgumentException("Неизвестный PotionEffectType '" + typeObj
                        + "' (уровень " + lvl + " у " + id + ")");
            }
            int amplifier = toInt(m.get("amplifier"), 0);

            Object modeObj = m.get("mode");
            String modeStr = (modeObj == null ? "PERMANENT" : modeObj.toString())
                    .toUpperCase(Locale.ROOT);

            EffectSpec.Mode mode;
            try {
                mode = EffectSpec.Mode.valueOf(modeStr);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Неверный mode '" + modeStr
                        + "' (уровень " + lvl + " у " + id + ")");
            }

            if (mode == EffectSpec.Mode.PERMANENT) {
                out.add(EffectSpec.permanent(type, amplifier));
            } else {
                int interval = toInt(m.get("interval-seconds"), 60);
                int duration = toInt(m.get("duration-seconds"), 10);
                if (interval <= 0 || duration <= 0) {
                    throw new IllegalArgumentException("interval/duration должны быть > 0 (уровень "
                            + lvl + " у " + id + ")");
                }
                out.add(EffectSpec.periodic(type, amplifier, interval, duration));
            }
        }
        return List.copyOf(out);
    }

    private static int toInt(@Nullable Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString()); } catch (NumberFormatException e) { return def; }
    }

    public static @Nullable PotionEffectType resolvePotionEffect(@NotNull String raw) {
        String name = raw.trim();
        if (name.isEmpty()) return null;

        try {
            PotionEffectType t = PotionEffectType.getByName(name.toUpperCase(Locale.ROOT));
            if (t != null) return t;
        } catch (Throwable ignored) {}

        String key = name.toLowerCase(Locale.ROOT);
        if (!key.contains(":")) key = "minecraft:" + key;
        try {
            NamespacedKey nk = NamespacedKey.fromString(key);
            if (nk != null) {
                PotionEffectType viaRegistry = Registry.EFFECT.get(nk);
                if (viaRegistry != null) return viaRegistry;
            }
        } catch (Throwable ignored) {}

        return null;
    }

    public static Material resolveMaterial(@NotNull String raw) {
        String name = raw.trim();
        if (name.isEmpty()) return null;

        try {
            return Material.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {}

        Material m = Material.matchMaterial(name);
        if (m != null) return m;

        String key = name.toLowerCase(Locale.ROOT);
        if (!key.contains(":")) key = "minecraft:" + key;
        try {
            NamespacedKey nk = NamespacedKey.fromString(key);
            if (nk != null) {
                Material viaRegistry = Registry.MATERIAL.get(nk);
                if (viaRegistry != null) return viaRegistry;
            }
        } catch (Throwable ignored) {}

        return null;
    }

    public @NotNull Optional<BuildingDefinition> byId(@NotNull String id) {
        return Optional.ofNullable(snapshot.byId().get(id));
    }

    public @NotNull List<BuildingDefinition> ofBranch(@NotNull BuildingBranch branch) {
        return snapshot.byBranchDefs().getOrDefault(branch, List.of());
    }

    public @NotNull Collection<BuildingDefinition> all() {
        return Collections.unmodifiableCollection(snapshot.byId().values());
    }

    public int size() {
        return snapshot.byId().size();
    }
}