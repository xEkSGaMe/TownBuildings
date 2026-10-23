package ru.example.cloudcore.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;

/** Ветка зданий. Ветки НЕ взаимоисключающие. */
public enum BuildingBranch {

    ECONOMY("Экономическая",    "Экономика",      NamedTextColor.GOLD),
    MILITARY("Военная",         "Военная",        NamedTextColor.RED),
    TECH("Технологическая",     "Технологии",     NamedTextColor.AQUA),
    MANAGEMENT("Управленческая","Управление",     NamedTextColor.YELLOW),
    PRODUCTION("Производственная","Производство", NamedTextColor.GREEN);

    private final String displayName;
    private final String shortName;
    private final NamedTextColor color;

    BuildingBranch(String displayName, String shortName, NamedTextColor color) {
        this.displayName = displayName;
        this.shortName = shortName;
        this.color = color;
    }

    public @NotNull String displayName() { return displayName; }
    public @NotNull String shortName()   { return shortName; }
    public @NotNull NamedTextColor color(){ return color; }

    public @NotNull Component asComponent() {
        return Component.text(displayName, color);
    }
}