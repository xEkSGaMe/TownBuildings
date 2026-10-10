package ru.example.townbuildings.gui.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.config.BuildingDefinition;
import ru.example.townbuildings.config.BuildingLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кэш статических частей lore.
 * MiniMessage разбирается один раз при первом запросе, дальше отдаются готовые Component.
 */
public final class LoreCache {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final ConcurrentHashMap<String, List<Component>> CACHE = new ConcurrentHashMap<>();

    private LoreCache() {}

    /** Бонусы уровня здания (статичны). */
    public static @NotNull List<Component> bonusLore(@NotNull BuildingDefinition def, int level) {
        String key = def.id() + ":" + level;
        return CACHE.computeIfAbsent(key, k -> {
            BuildingLevel bl = def.level(level).orElse(null);
            if (bl == null) return List.of();
            List<Component> out = new ArrayList<>(bl.bonusLore().size());
            for (String s : bl.bonusLore()) out.add(MM.deserialize(s));
            return List.copyOf(out);
        });
    }

    /** Очистить при reload. */
    public static void clear() {
        CACHE.clear();
    }

    public static int size() {
        return CACHE.size();
    }
}