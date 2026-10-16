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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Обёртка над messages.yml.
 *
 * P19 — кэш Component/raw/MiniMessage-парсинга.
 * M13 — soundCache: резолв Sound по ключу кэшируется. Раньше getSound()
 *       читал yaml + прогонял матчинг на КАЖДЫЙ вызов — а зовётся он
 *       из горячего пути (playError/playUpgradeSuccess).
 */
public final class MessagesConfig {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final TownBuildingsPlugin plugin;
    private final File file;
    private YamlConfiguration yaml;

    private @Nullable Component prefix;

    /** 5.1 — предупреждаем один раз на ключ/звук. Сбрасывается в load(). */
    private final Set<String> warnedMissing = ConcurrentHashMap.newKeySet();
    private final Set<String> warnedSounds  = ConcurrentHashMap.newKeySet();

    /** P19 — кэш готовых Component (см. cached()); сбрасывается в load(). */
    private final Map<String, Component> componentCache = new ConcurrentHashMap<>();
    /** P19 — кэш сырых строк yaml (см. cachedRaw()); сбрасывается в load(). */
    private final Map<String, String> rawCache = new ConcurrentHashMap<>();
    /** P19 — кэш MM.deserialize по строке; bounded через trimStringCache(). */
    private final Map<String, Component> stringCache = new ConcurrentHashMap<>();
    private static final int STRING_CACHE_MAX = 4096;

    /** ★ M13 — кэш резолвнутых Sound по config-ключу. Сбрасывается в load(). */
    private final Map<String, Optional<Sound>> soundCache = new ConcurrentHashMap<>();

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
        componentCache.clear();
        rawCache.clear();
        stringCache.clear();
        // ★ M13 — после reload надо перерезолвить звуки.
        soundCache.clear();

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
     * Использовать ТОЛЬКО для значений, которые мы сами контролируем.
     */
    public @NotNull Component getUnescaped(@NotNull String key,
                                           @NotNull Map<String, String> placeholders) {
        return render(key, placeholders, false);
    }

    /**
     * P19 — кэш готовых Component.
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

        if (!staticValues && !placeholders.isEmpty()) {
            if (!yaml.isConfigurationSection(key) && yaml.getString(key) == null) {
                return missingComponent(key);
            }
            return render(key, placeholders, true);
        }

        Component hit = componentCache.get(ck);
        if (hit != null) return hit;
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
     * 10.3 — вариант для технических плейсхолдеров (цвета, градиенты),
     * которые мы сами контролируем в messages.yml.
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

    /**
     * Индекс звуков: нормализованное имя → Sound. Строится один раз лениво.
     */
    private static final class SoundIndex {
        static final Map<String, Sound> MAP = build();

        private static Map<String, Sound> build() {
            Map<String, Sound> m = new HashMap<>();
            try {
                for (Sound s : Registry.SOUNDS) {
                    NamespacedKey k = s.getKey();
                    String full = k.getNamespace() + ":" + k.getKey();
                    m.put(full.toLowerCase(Locale.ROOT), s);
                    m.put(k.getKey().toLowerCase(Locale.ROOT), s);
                    m.put(norm(full), s);
                    m.put(norm(k.getKey()), s);
                    m.put(legacyName(k.getKey()), s);
                }
            } catch (Throwable t) {
                // Registry.SOUNDS может быть недоступен на очень раннем этапе —
                // тогда getSound() отдаст null, а SoundService сыграет fallback.
            }
            return m;
        }

        private static @NotNull String norm(@NotNull String s) {
            return s.toLowerCase(Locale.ROOT)
                    .replace("_", "")
                    .replace(".", "")
                    .replace(":", "")
                    .replace("-", "");
        }

        /** "block.note_block.bass" → "BLOCK_NOTE_BLOCK_BASS". */
        private static @NotNull String legacyName(@NotNull String key) {
            return key.toUpperCase(Locale.ROOT)
                    .replace(".", "_")
                    .replace("-", "_");
        }
    }

    /**
     * ★ M13 — резолв Sound кэшируется: горячий путь (playError /
     * playUpgradeSuccess) больше не читает yaml + не прогоняет матчинг.
     */
    public @Nullable Sound getSound(@NotNull String key) {
        Optional<Sound> cached = soundCache.computeIfAbsent(key, k -> {
            Sound resolved = resolveSound(k);
            return Optional.ofNullable(resolved);
        });
        return cached.orElse(null);
    }

    private @Nullable Sound resolveSound(@NotNull String key) {
        String name = yaml.getString(key);
        if (name == null || name.isBlank()) return null;

        String trimmed = name.trim();

        // 1. Прямой матч по индексу.
        Sound s = SoundIndex.MAP.get(trimmed.toLowerCase(Locale.ROOT));
        if (s == null) s = SoundIndex.MAP.get(trimmed);
        if (s == null) s = SoundIndex.MAP.get(trimmed.toUpperCase(Locale.ROOT));
        if (s == null) {
            String norm = trimmed.toLowerCase(Locale.ROOT)
                    .replace("_", "").replace(".", "").replace(":", "").replace("-", "");
            s = SoundIndex.MAP.get(norm);
        }
        if (s != null) return s;

        // 2. Fallback: пробуем как NamespacedKey напрямую.
        try {
            String k = trimmed.toLowerCase(Locale.ROOT);
            if (!k.contains(":")) k = "minecraft:" + k;
            NamespacedKey nk = NamespacedKey.fromString(k);
            if (nk != null) {
                Sound direct = Registry.SOUNDS.get(nk);
                if (direct != null) return direct;
            }
        } catch (Throwable ignored) {}

        // 5.1 — warning один раз на ключ.
        if (warnedSounds.add(key)) {
            plugin.getLogger().warning("[Messages] Неизвестный Sound '" + name
                    + "' для ключа " + key
                    + " — используйте legacy-имя (BLOCK_NOTE_BLOCK_BASS) "
                    + "или modern-ID (block.note_block.bass / minecraft:block.note_block.bass).");
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
     * При debug=true — показываем &lt;missing:key&gt;.
     * При debug=false — пустой Component.
     * Warning в консоль один раз на ключ за сессию.
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