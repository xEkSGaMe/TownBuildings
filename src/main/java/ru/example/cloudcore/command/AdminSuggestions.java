package ru.example.cloudcore.command;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Town;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import ru.example.cloudcore.config.BuildingDefinition;
import ru.example.cloudcore.config.WonderConfig;

import ru.example.cloudcore.CloudCorePlugin;
/** Подсказки для аргументов /tadmin. */
public final class AdminSuggestions {

    private AdminSuggestions() {}

    /** Названия городов Towny. */
    public static final SuggestionProvider<CommandSourceStack> TOWNS = (ctx, builder) -> {
        try {
            var towns = TownyAPI.getInstance().getTowns();
            if (towns != null) {
                for (Town town : towns) {
                    if (town != null) builder.suggest(town.getName());
                }
            }
        } catch (Throwable t) {
            // Towny ещё не готов — тихо возвращаем пустой список.
        }
        return builder.buildFuture();
    };

    /** id зданий из buildings.yml. */
    public static final SuggestionProvider<CommandSourceStack> BUILDINGS = (ctx, builder) -> {
        try {
            CloudCorePlugin plugin = CloudCorePlugin.getInstance();
            var registry = plugin.buildingRegistry();
            if (registry != null) {
                for (BuildingDefinition def : registry.all()) {
                    builder.suggest(def.id());
                }
            }
        } catch (Throwable t) {
            // Плагин не инициализирован или реестр ещё не загружен —
            // автокомплит молча пустой.
        }
        return builder.buildFuture();
    };

    /** ★ id чудес из wonders.yml. */
    public static final SuggestionProvider<CommandSourceStack> WONDERS = (ctx, builder) -> {
        try {
            CloudCorePlugin plugin = CloudCorePlugin.getInstance();
            var registry = plugin.wonderRegistry();
            if (registry != null) {
                for (WonderConfig wc : registry.all()) {
                    builder.suggest(wc.id());
                }
            }
        } catch (Throwable ignored) {}
        return builder.buildFuture();
    };
}