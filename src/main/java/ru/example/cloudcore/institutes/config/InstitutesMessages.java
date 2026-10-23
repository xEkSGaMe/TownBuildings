package ru.example.cloudcore.institutes.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.CloudCorePlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ★ 5b — рендер institutes-сообщений.
 *
 * Системные сообщения (no-permission, choice-*, lore-*, menu-titles.*, reloaded)
 * читаются из messages.yml (institutes.*) — единый файл сообщений плагина.
 *
 * Контентные строки (religions.*.lore, forms.*.lore, ideologies.*.lore)
 * продолжают читаться из institutes.yml — это данные, а не сообщения.
 *
 * Парсинг — LegacyComponentSerializer.legacyAmpersand() (& формат),
 * сохранён для совместимости с institutes-форматом из прежней версии.
 */
public final class InstitutesMessages {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private final CloudCorePlugin plugin;

    public InstitutesMessages(@NotNull CloudCorePlugin plugin) {
        this.plugin = plugin;
    }

    // ============================================================
    //   Системные сообщения (messages.yml → institutes.*)
    // ============================================================

    /** Префикс из institutes.prefix (или пустая строка). */
    public @NotNull Component prefix() {
        String raw = plugin.getMessagesConfig().getInstitutesRaw("prefix");
        return raw.isBlank() ? Component.empty() : LEGACY.deserialize(raw);
    }

    /** institutes.<key> с префиксом. */
    public @NotNull Component prefixed(@NotNull String key) {
        Component body = raw(key);
        if (body == Component.empty()) return Component.empty();
        return Component.empty().append(prefix()).append(body);
    }

    /** institutes.<key> с префиксом + плейсхолдерами {name} → value. */
    public @NotNull Component prefixed(@NotNull String key,
                                       @NotNull Map<String, String> placeholders) {
        String raw = plugin.getMessagesConfig().getInstitutesRaw(key);
        if (raw.isBlank()) return Component.empty();
        for (var e : placeholders.entrySet()) {
            raw = raw.replace("{" + e.getKey() + "}",
                    e.getValue() == null ? "" : e.getValue());
        }
        return Component.empty().append(prefix()).append(LEGACY.deserialize(raw));
    }

    /** institutes.<key> без префикса. */
    public @NotNull Component raw(@NotNull String key) {
        String raw = plugin.getMessagesConfig().getInstitutesRaw(key);
        return raw.isBlank() ? Component.empty() : LEGACY.deserialize(raw);
    }

    /** Заголовок меню: institutes.menu-titles.<menuType>. */
    public @NotNull Component title(@NotNull String menuTypeKey) {
        String raw = plugin.getMessagesConfig()
                .getInstitutesRaw("menu-titles." + menuTypeKey);
        if (raw.isBlank()) return Component.text(menuTypeKey);
        return LEGACY.deserialize(raw);
    }

    // ============================================================
    //   Контентные строки (institutes.yml — религии/формы/идеологии)
    // ============================================================

    /**
     * Парсит произвольную &-строку (для lore из institutes.yml).
     * Не трогает messages.yml.
     */
    public @NotNull Component colored(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        return LEGACY.deserialize(raw);
    }

    /** Парсит список &-строк (lore). */
    public @NotNull List<Component> coloredLore(@NotNull List<String> rawLines) {
        List<Component> out = new ArrayList<>(rawLines.size());
        for (String s : rawLines) out.add(LEGACY.deserialize(s == null ? "" : s));
        return out;
    }

    /** Список lore из institutes.yml по пути. */
    public @NotNull List<Component> lore(@NotNull String path) {
        return coloredLore(plugin.institutesConfig().lore(path));
    }
}