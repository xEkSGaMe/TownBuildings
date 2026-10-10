package ru.example.townbuildings.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class ConfigMigrator {

    private final JavaPlugin plugin;

    public ConfigMigrator(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void migrate(@NotNull String fileName) {
        File onDisk = new File(plugin.getDataFolder(), fileName);
        if (!onDisk.exists()) return;

        try (InputStream in = plugin.getResource(fileName)) {
            if (in == null) return;
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            YamlConfiguration current = YamlConfiguration.loadConfiguration(onDisk);

            int legacyMoved = 0;
            if ("messages.yml".equals(fileName)) {
                legacyMoved = migrateMessagesLegacy(current);
            }

            int added = mergeMissing(defaults, current);

            if (added == 0 && legacyMoved == 0) return;

            File backup = new File(plugin.getDataFolder(),
                    fileName + ".backup-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
            java.nio.file.Files.copy(onDisk.toPath(), backup.toPath());

            current.save(onDisk);
            plugin.getLogger().info("[Config] " + fileName
                    + ": добавлено ключей — " + added
                    + (legacyMoved > 0 ? ", перенесено legacy — " + legacyMoved : "")
                    + ", backup → " + backup.getName());
        } catch (IOException e) {
            plugin.getLogger().warning("[Config] Ошибка миграции " + fileName + ": " + e.getMessage());
        }
    }

    /**
     * Переносит legacy-ключи, которые из-за отступа оказались внутри
     * gui.building-symbols, в gui.building-icon.
     * Значения пользователя сохраняются. Идемпотентно.
     */
    private int migrateMessagesLegacy(@NotNull YamlConfiguration current) {
        String[] legacyKeys = {
                "line-level-base", "line-level", "line-level-cont",
                "value-coins", "value-citizen-coins", "value-stock",
                "value-chunks", "value-tax", "value-exp", "value-harvest", "value-mint"
        };
        int moved = 0;
        for (String k : legacyKeys) {
            String legacyPath = "gui.building-symbols." + k;
            if (!current.contains(legacyPath)) continue;
            String newPath = "gui.building-icon." + k;
            // Значение пользователя имеет приоритет над дефолтным из jar.
            current.set(newPath, current.get(legacyPath));
            current.set(legacyPath, null);
            moved++;
        }
        return moved;
    }

    private int mergeMissing(@NotNull YamlConfiguration defaults, @NotNull YamlConfiguration current) {
        int added = 0;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue;
            if (!current.contains(key)) {
                current.set(key, defaults.get(key));
                added++;
            }
        }
        return added;
    }
}