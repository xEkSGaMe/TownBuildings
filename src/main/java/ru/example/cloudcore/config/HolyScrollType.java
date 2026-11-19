package ru.example.cloudcore.config;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Тип свитка, которым открывается Священное место.
 *
 * MYSTERY — «Таинственный Свиток» (FLOW_BANNER_PATTERN);
 * ANCIENT — «Древний Свиток» (MOJANG_BANNER_PATTERN).
 *
 * Тип задаётся в holy-sites.yml полем {@code scroll}. Если поле не указано,
 * используется MYSTERY — так старые конфиги продолжают работать.
 */
public enum HolyScrollType {

    MYSTERY("mystery", Material.FLOW_BANNER_PATTERN),
    ANCIENT("ancient", Material.MOJANG_BANNER_PATTERN);

    private final String key;
    private final Material material;

    HolyScrollType(@NotNull String key, @NotNull Material material) {
        this.key = key;
        this.material = material;
    }

    public @NotNull String key() { return key; }

    public @NotNull Material material() { return material; }

    public static @Nullable HolyScrollType byKey(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (HolyScrollType type : values()) {
            if (type.name().equals(normalized) || type.key.equalsIgnoreCase(raw.trim())) {
                return type;
            }
        }
        return null;
    }
}
