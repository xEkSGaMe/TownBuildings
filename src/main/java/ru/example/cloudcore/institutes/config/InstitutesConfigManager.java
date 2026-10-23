package ru.example.cloudcore.institutes.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.CloudCorePlugin;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Конфиг институтов (institutes.yml).
 *
 * Загружается отдельно от основного config.yml. Все значения имеют
 * безопасные дефолты — плагин работает даже при отсутствии секции.
 */
public final class InstitutesConfigManager {

    private final CloudCorePlugin plugin;
    private final File file;
    private volatile YamlConfiguration yaml = new YamlConfiguration();

    public InstitutesConfigManager(@NotNull CloudCorePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "institutes.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("institutes.yml", false);
        }
        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    public @NotNull YamlConfiguration yaml() { return yaml; }

    // -------- permissions --------
    public String permissionNode()   { return yaml.getString("permissions.node", "institutes.open"); }
    public String permissionBypass() { return yaml.getString("permissions.bypass", "institutes.admin"); }

    // -------- общие --------
    public int changeCostLevels()     { return Math.max(0, yaml.getInt("settings.change-cost-levels", 45)); }
    public int autosaveMinutes()      { return Math.max(1, yaml.getInt("settings.autosave-minutes", 5)); }
    public int healthBonusHearts()    { return yaml.getInt("settings.health-bonus-hearts", 2); }
    public int glowDurationSeconds()  { return Math.max(1, yaml.getInt("settings.glow-duration-seconds", 8)); }
    public int glowIntervalSeconds()  { return Math.max(1, yaml.getInt("settings.glow-interval-seconds", 6)); }

    // -------- формы --------
    public int monarchyCoinsPerHour()     { return yaml.getInt("settings.monarchy-coins-per-hour", 3); }
    public int monarchyCitizenTax()       { return yaml.getInt("settings.monarchy-citizen-tax", 1); }
    public int monarchyChunkBonus()       { return Math.max(0, yaml.getInt("settings.monarchy-chunk-bonus", 50)); }
    public int dictatorshipCoinsPerHour() { return yaml.getInt("settings.dictatorship-coins-per-hour", 2); }
    public int oligarchyCoinsPerHour()    { return yaml.getInt("settings.oligarchy-coins-per-hour", 2); }
    public int formCheckIntervalSeconds() { return Math.max(5, yaml.getInt("settings.form-check-interval-seconds", 60)); }
    public int theocracyTownIncomePerHour() { return yaml.getInt("settings.theocracy-town-income-per-hour", 3); }
    public int republicHearts()           { return yaml.getInt("settings.republic-hearts", 1); }
    public int dictatorshipHearts()       { return yaml.getInt("settings.dictatorship-hearts", -1); }

    // -------- таймеры религий --------
    public int fastTickSeconds()      { return Math.max(1, yaml.getInt("settings.fast-tick-seconds", 5)); }
    public int hinduTickSeconds()     { return Math.max(5, yaml.getInt("settings.hindu-tick-seconds", 60)); }
    public int buddhismTickSeconds()  { return Math.max(5, yaml.getInt("settings.buddhism-tick-seconds", 60)); }
    public int confucianTickSeconds() { return Math.max(1, yaml.getInt("settings.confucian-tick-seconds", 5)); }

    // -------- элита --------
    public String eliteGroupWarden()    { return yaml.getString("settings.elite-group-warden", "warden").toLowerCase(); }
    public String eliteGroupGladiator() { return yaml.getString("settings.elite-group-gladiator", "gladiator").toLowerCase(); }
    public String eliteGroupKnight()    { return yaml.getString("settings.elite-group-knight", "knight").toLowerCase(); }

    // -------- боевые --------
    public double orthodoxDamage()            { return yaml.getDouble("settings.orthodox-damage", 1.03); }
    public double orthodoxDamageElite()       { return yaml.getDouble("settings.orthodox-damage-elite", 1.04); }
    public double confucianDamageReduction()  { return yaml.getDouble("settings.confucian-damage-reduction", 0.90); }
    public int confucianResistanceLevel()     { return yaml.getInt("settings.confucian-resistance-level", 0); }
    public int confucianResistanceRequiredLevel() {
        return yaml.getInt("settings.confucian-resistance-required-level", 30);
    }

    // -------- общие геттеры --------
    public @Nullable ConfigurationSection section(String path) {
        return yaml.getConfigurationSection(path);
    }

    public @NotNull Material material(String path, @NotNull Material def) {
        String raw = yaml.getString(path);
        if (raw == null || raw.isBlank()) return def;
        Material m = Material.matchMaterial(raw.toUpperCase());
        return m != null ? m : def;
    }

    public @NotNull List<String> lore(String path) {
        List<String> l = yaml.getStringList(path);
        return l != null ? l : Collections.emptyList();
    }

    public @NotNull String display(String path) {
        return yaml.getString(path, path);
    }

    public @NotNull String message(String key) {
        return yaml.getString("messages." + key, "");
    }

    public @NotNull String menuTitle(String key) {
        return yaml.getString("menu-titles." + key, key);
    }
}