package ru.example.cloudcore.institutes.data;

import org.jetbrains.annotations.NotNull;

public enum MenuType {
    RELIGION("religion"),
    FORM("form"),
    IDEOLOGY("ideology");

    private final String configKey;
    MenuType(String configKey) { this.configKey = configKey; }
    public @NotNull String configKey() { return configKey; }
}