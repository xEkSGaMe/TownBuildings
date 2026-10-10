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

    /** P19 — кэш готовых Component (см. cached()); сбрасывается в load(). */
    private final Map<String, Component> componentCache = new ConcurrentHashMap<>();
    /** P19 — кэш сырых строк yaml (см. cachedRaw()); сбрасывается в load(). */
    private final Map<String, String> rawCache = new ConcurrentHashMap<>();
    /** P19 — кэш MM.deserialize по строке; bounded через trimStringCache(). */
    private final Map<String, Component> stringCache = new ConcurrentHashMap<>();
    private static final int STRING_CACHE_MAX = 4096;

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
        componentCache.clear(); // P19 — после reload старые Component невалидны.
        rawCache.clear();
        stringCache.clear();

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
        return render(key, placeholders, true);
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
        return render(key, placeholders, false);
    }

    /**
     * P19 — кэш готовых Component.
     *
     * @param staticValues true, если значения плейсхолдеров статичны (цвета,
     *                     символы, римские цифры) и их можно включать в ключ;
     *                     false — пользовательский ввод: значения экранируются
     *                     как в get() и подставляются при каждом вызове
     *                     (в ключ кэша НЕ попадают — иначе память растёт
     *                     линейно от числа уникальных имён/чисел).
     */
    public @NotNull Component cached(@NotNull String key,
                                     @NotNull Map<String, String> placeholders,
                                     boolean staticValues) {
        String ck;
        StringBuilder sb = new StringBuilder(key.length() + 32);
        sb.append(staticValues ? 'S' : 'D').append('\u0000').append(key);
        if (staticValues) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                sb.append('\u0000').append(e.getKey()).append('=').append(e.getValue());
            }
        }
        ck = sb.toString();

        // D-вариант с плейсхолдерами не кэшируем по значению: рендерим на лету
        // (экранирование+подстановка дешёвые, дорогой здесь только deserialize
        // шаблона — его кэшируем ниже по чистому ключу).
        if (!staticValues && !placeholders.isEmpty()) {
            if (!yaml.isConfigurationSection(key) && yaml.getString(key) == null) {
                return missingComponent(key);
            }
            return render(key, placeholders, true);
        }

        Component hit = componentCache.get(ck);
        if (hit != null) return hit;
        // P19 — если ключа нет в yaml, render вернёт missingComponent и
        // забьёт кэш мусором: проверяем наличие ключа до рендера/кэширования.
        if (!yaml.isConfigurationSection(key) && yaml.getString(key) == null) {
            return missingComponent(key);
        }
        Component c = render(key, placeholders, !staticValues);
        componentCache.put(ck, c);
        return c;
    }

    /** Кэш сырой строки из yaml по ключу (для ручного форматирования в GUI). */
    public @NotNull String cachedRaw(@NotNull String key) {
        return rawCache.computeIfAbsent(key, k -> yaml.getString(k, ""));
    }

    /** P19 — MiniMessage-парсинг с кэшем по сырой строке (bounded, см. trim). */
    public @NotNull Component deserializeCached(@NotNull String rawMiniMessage) {
        Component c = stringCache.computeIfAbsent(rawMiniMessage, MM::deserialize);
        if (stringCache.size() > STRING_CACHE_MAX) stringCache.clear();
        return c;
    }

    private @NotNull Component render(@NotNull String key,
                                      @NotNull Map<String, String> placeholders,
                                      boolean escapeValues) {
        String raw = yaml.getString(key);
        if (raw == null) return missingComponent(key);
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue();
            if (escapeValues) v = escapeMiniMessage(v);
            raw = raw.replace("{" + e.getKey() + "}", v);
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

        // 5.x — убираем deprecated Sound.valueOf(String): единственный путь —
        // Registry.SOUNDS по NamespacedKey. Поддерживаем и «BLOCK_NOTE_PLING»,
        // и «block.note.pling», и «minecraft:xyz» без дублирования логики.
        try {
            String k = name.toLowerCase(Locale.ROOT).replace('_', '.');
            if (!k.contains(":") && !k.contains(".")) k = "minecraft:" + k;
            else if (!k.contains(":")) k = "minecraft:" + k.substring(0, k.indexOf('.'))
                    + ":" + k.substring(k.indexOf('.') + 1);
            NamespacedKey nk = NamespacedKey.fromString(k);
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