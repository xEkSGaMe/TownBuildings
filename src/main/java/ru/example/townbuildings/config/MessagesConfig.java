package ru.example.townbuildings.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.townbuildings.TownBuildingsPlugin;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MessagesConfig {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final TownBuildingsPlugin plugin;
    private final File file;
    private YamlConfiguration yaml;

    private @Nullable Component prefix;

    /**
     * 5.1 — предупреждаем один раз на ключ/звук, а не на каждый вызов.
     * Сбрасывается в load().
     */
    private final Set<String> warnedMissing = ConcurrentHashMap.newKeySet();
    private final Set<String> warnedSounds  = ConcurrentHashMap.newKeySet();

    public MessagesConfig(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "messages.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        this.yaml = YamlConfiguration.loadConfiguration(file);

        // 5.1 — очистка при reload, чтобы можно было поправить конфиг
        //       и увидеть предупреждения снова.
        warnedMissing.clear();
        warnedSounds.clear();

        String rawPrefix = yaml.getString("prefix", "");
        this.prefix = rawPrefix.isBlank() ? null : MM.deserialize(rawPrefix);

        if (plugin.getConfigManager() != null && plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[Messages] Загружено " + yaml.getKeys(true).size() + " ключей.");
        }
    }

    public void reload() {
        load();
    }

    // ============================================================
    //   Основное API
    // ============================================================

    public @NotNull Component get(@NotNull String key) {
        return get(key, Map.of());
    }

    /**
     * 11-B1 — все плейсхолдеры экранируются: пользовательский текст
     * (имя города, ник, произвольный ввод) не парсится как MiniMessage.
     */
    public @NotNull Component get(@NotNull String key, @NotNull Map<String, String> placeholders) {
        String raw = yaml.getString(key);
        if (raw == null) return missingComponent(key);
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            String safe = e.getValue() == null ? "" : escapeMiniMessage(e.getValue());
            raw = raw.replace("{" + e.getKey() + "}", safe);
        }
        return MM.deserialize(raw);
    }

    /**
     * Аналог {@link #get(String, Map)}, но НЕ экранирует значения плейсхолдеров.
     *
     * Использовать ТОЛЬКО для значений, которые мы сами контролируем:
     *   - цвета/градиенты из gui.branch-meta.*
     *   - сырые MiniMessage-строки из bonus-lore
     *   - римские цифры, числа, id
     *
     * НИКОГДА не передавать сюда пользовательский ввод
     * (имена городов, никнеймы, названия предметов из чата).
     */
    public @NotNull Component getUnescaped(@NotNull String key,
                                           @NotNull Map<String, String> placeholders) {
        String raw = yaml.getString(key);
        if (raw == null) return missingComponent(key);
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            raw = raw.replace("{" + e.getKey() + "}",
                    e.getValue() == null ? "" : e.getValue());
        }
        return MM.deserialize(raw);
    }

    /** Экранирует угловые скобки, чтобы текст не парсился как MiniMessage-теги. */
    private static @NotNull String escapeMiniMessage(@NotNull String input) {
        return input.replace("<", "\\<").replace(">", "\\>");
    }

    // ============================================================
    //   getRaw — сырые строки / технические плейсхолдеры
    // ============================================================

    /** Сырая строка из yaml (без парсинга MiniMessage). */
    public @NotNull String getRaw(@NotNull String key) {
        return yaml.getString(key, "");
    }

    /**
     * 10.3 — специальный вариант для технических плейсхолдеров (цвета,
     * градиенты), которые мы сами контролируем в messages.yml.
     * НЕ экранирует `<`/`>` — вставляется как есть.
     */
    public @NotNull Component getRaw(@NotNull String key,
                                     @NotNull Map<String, String> placeholders) {
        String raw = yaml.getString(key);
        if (raw == null) return missingComponent(key);
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            raw = raw.replace("{" + e.getKey() + "}",
                    e.getValue() == null ? "" : e.getValue());
        }
        return MM.deserialize(raw);
    }

    // ============================================================
    //   Звуки
    // ============================================================

    public @Nullable Sound getSound(@NotNull String key) {
        String name = yaml.getString(key);
        if (name == null || name.isBlank()) return null;

        try {
            return Sound.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {}

        try {
            String k = name.toLowerCase(Locale.ROOT).replace('_', '.');
            if (!k.contains(".")) k = "minecraft:" + k;
            NamespacedKey nk = k.startsWith("minecraft:")
                    ? NamespacedKey.fromString(k)
                    : NamespacedKey.minecraft(k);
            if (nk != null) {
                Sound s = Registry.SOUNDS.get(nk);
                if (s != null) return s;
            }
        } catch (Throwable ignored) {}

        // 5.1 — warning один раз на ключ (опечатка в конфиге не спамит).
        if (warnedSounds.add(key)) {
            plugin.getLogger().warning("[Messages] Неизвестный Sound '" + name
                    + "' для ключа " + key);
        }
        return null;
    }

    // ============================================================
    //   Отправка
    // ============================================================

    public void send(@NotNull CommandSender target, @NotNull String key) {
        send(target, key, Map.of());
    }

    public void send(@NotNull CommandSender target, @NotNull String key,
                     @NotNull Map<String, String> placeholders) {
        Component body = get(key, placeholders);
        Component out = (prefix != null) ? Component.empty().append(prefix).append(body) : body;
        target.sendMessage(out);
    }

    public void actionBar(@NotNull Player player, @NotNull String key,
                          @NotNull Map<String, String> placeholders) {
        player.sendActionBar(get(key, placeholders));
    }

    public @Nullable Component prefix() {
        return prefix;
    }

    public @NotNull MiniMessage mini() {
        return MM;
    }

    // ============================================================
    //   5.1 — единый missing-фоллбэк
    // ============================================================

    /**
     * При debug=true — показываем &lt;missing:key&gt; (для диагностики).
     * При debug=false — отдаём пустой Component (игрок не видит мусор).
     * В любом случае пишем warning в консоль один раз на ключ за сессию.
     */
    private @NotNull Component missingComponent(@NotNull String key) {
        if (warnedMissing.add(key)) {
            plugin.getLogger().warning("[Messages] Отсутствует ключ: " + key);
        }
        boolean debug = plugin.getConfigManager() != null
                && plugin.getConfigManager().isDebug();
        return debug ? Component.text("<missing:" + key + ">") : Component.empty();
    }
}