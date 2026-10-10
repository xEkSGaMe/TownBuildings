package ru.example.townbuildings.command;

import com.palmergames.bukkit.towny.object.Town;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;

import java.util.Map;
import java.util.UUID;

/** /tadmin logs &lt;town&gt; [limit]. */
public final class AdminLogsHandler {

    private final TownBuildingsPlugin plugin;

    public AdminLogsHandler(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
    }

    public void showLogs(@NotNull CommandSender sender, @NotNull String townName, int limit) {
        Town town = plugin.towny().getTownByName(townName);
        if (town == null) {
            sender.sendMessage(plugin.getMessagesConfig().get("admin.town-not-found",
                    Map.of("town", townName)));
            return;
        }
        UUID townUuid = town.getUUID();

        plugin.logDao().recentByTown(townUuid, limit).thenAccept(lines ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(AdminCommand.header(plugin,
                            "Логи " + town.getName() + " (последние " + limit + ")"));
                    if (lines.isEmpty()) {
                        sender.sendMessage(Component.text("(пусто)", NamedTextColor.DARK_GRAY));
                        return;
                    }
                    for (String line : lines) {
                        sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
                    }
                }));
    }
}