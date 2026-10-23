package ru.example.cloudcore.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * M4 — backup верифицируется по размеру; при несовпадении перезапись
 *      отменяется, битый backup удаляется. После save проверяем, что
 *      файл не пуст — иначе восстанавливаем из backup.
 */
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

            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
            File backup = new File(plugin.getDataFolder(),
                    fileName + ".backup-" + stamp);
            int uniq = 0;
            while (backup.exists()) {
                uniq++;
                backup = new File(plugin.getDataFolder(),
                        fileName + ".backup-" + stamp + "-" + uniq);
            }

            // ★ M4 — backup + verify size.
            long originalSize = Files.size(onDisk.toPath());
            Files.copy(onDisk.toPath(), backup.toPath());

            long backupSize = Files.size(backup.toPath());
            if (backupSize != originalSize || backupSize == 0) {
                plugin.getLogger().severe("[Config] " + fileName
                        + ": backup не совпал по размеру (orig=" + originalSize
                        + ", backup=" + backupSize + ") — перезапись отменена.");
                try { Files.deleteIfExists(backup.toPath()); } catch (IOException ignored) {}
                return;
            }

            current.save(onDisk);

            // ★ M4 — верифицируем, что новый файл не пуст.
            if (Files.size(onDisk.toPath()) == 0) {
                plugin.getLogger().severe("[Config] " + fileName
                        + ": сохранённый файл пуст! Восстанавливаю из бэкапа.");
                Files.copy(backup.toPath(), onDisk.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                return;
            }

            plugin.getLogger().info("[Config] " + fileName
                    + ": добавлено ключей — " + added
                    + (legacyMoved > 0 ? ", перенесено legacy — " + legacyMoved : "")
                    + ", backup → " + backup.getName());
        } catch (IOException e) {
            plugin.getLogger().warning("[Config] Ошибка миграции " + fileName + ": " + e.getMessage());
        }
    }

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