package ru.example.townbuildings.command;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Town;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.config.BuildingDefinition;

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
            TownBuildingsPlugin plugin = TownBuildingsPlugin.getInstance();
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
}