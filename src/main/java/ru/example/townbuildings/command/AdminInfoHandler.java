package ru.example.townbuildings.command;

import com.palmergames.bukkit.towny.object.Town;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.config.BuildingRewards;
import ru.example.townbuildings.database.model.BuildingData;
import ru.example.townbuildings.database.model.TownData;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Показывает сводную информацию по городу (/tadmin info).
 *
 * C-1 — getOrCreate перед getBuildings: FK на tb_buildings будет нарушен,
 *       если город есть в Towny, но ещё не заведён в tb_towns.
 */
public final class AdminInfoHandler {

    private final TownBuildingsPlugin plugin;

    public AdminInfoHandler(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
    }

    public void handle(@NotNull CommandSender sender, @NotNull String townName) {
        Town town = plugin.towny().getTownByName(townName);
        if (town == null) {
            sender.sendMessage(plugin.getMessagesConfig().get("admin.town-not-found",
                    Map.of("town", townName)));
            return;
        }
        UUID townUuid = town.getUUID();

        plugin.townService().getOrCreate(townUuid, town.getName())
                .thenCompose(td -> plugin.townService().getBuildings(townUuid))
                .thenAccept(buildings -> Bukkit.getScheduler().runTask(plugin, () ->
                        render(sender, town, townUuid, buildings)))
                .exceptionally(ex -> {
                    plugin.getLogger().warning("[Admin] info(" + townUuid
                            + ") упал: " + ex.getMessage());
                    Bukkit.getScheduler().runTask(plugin, () ->
                            sender.sendMessage(plugin.getMessagesConfig()
                                    .get("general.internal-error")));
                    return null;
                });
    }

    private void render(@NotNull CommandSender sender,
                        @NotNull Town town,
                        @NotNull UUID townUuid,
                        @NotNull List<BuildingData> buildings) {

        sender.sendMessage(AdminCommand.header(plugin, "Город: " + town.getName()));

        sender.sendMessage(Component.text("UUID: ", NamedTextColor.GRAY)
                .append(Component.text(townUuid.toString(), NamedTextColor.WHITE)));

        UUID mayor = plugin.towny().getMayorUuid(town);
        sender.sendMessage(Component.text("Мэр: ", NamedTextColor.GRAY)
                .append(Component.text(mayor != null ? mayor.toString() : "—",
                        NamedTextColor.WHITE)));

        TownData cached = plugin.townService().getCachedTown(townUuid);
        long onlineSeconds = cached != null ? cached.cityOnlineSeconds() : 0L;
        sender.sendMessage(Component.text("Онлайн города (сек): ", NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(onlineSeconds), NamedTextColor.WHITE)));

        BuildingRewards r = plugin.bonusService().getRewards(townUuid);
        sender.sendMessage(Component.text("Бонусы (per hour / %):", NamedTextColor.GOLD));
        sender.sendMessage(line("town-coins", r.townCoinsPerHour()));
        sender.sendMessage(line("citizen-coins", r.citizenCoinsPerHour()));
        sender.sendMessage(line("chunks", r.townChunksBonus()));
        sender.sendMessage(line("tax%", r.taxReductionPercent()));
        sender.sendMessage(line("exp%", r.expBonusPercent()));
        sender.sendMessage(line("harvest%", r.harvestBonusPercent()));

        sender.sendMessage(Component.text("Здания (" + buildings.size() + "):",
                NamedTextColor.GOLD));
        if (buildings.isEmpty()) {
            sender.sendMessage(Component.text("  —", NamedTextColor.DARK_GRAY));
            return;
        }
        for (BuildingData bd : buildings) {
            sender.sendMessage(Component.text("  • ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(bd.buildingId(), NamedTextColor.WHITE))
                    .append(Component.text(" = " + bd.level(), NamedTextColor.GRAY)));
        }
    }

    private @NotNull Component line(@NotNull String key, @NotNull Object value) {
        return Component.text("  " + key + ": ", NamedTextColor.DARK_GRAY)
                .append(Component.text(String.valueOf(value), NamedTextColor.WHITE));
    }
}