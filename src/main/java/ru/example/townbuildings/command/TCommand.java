package ru.example.townbuildings.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.gui.menu.MainBuildingsMenu;
import ru.example.townbuildings.gui.menu.WonderMenu;

import java.util.UUID;

/**
 * /tbuildings builds    — меню построек города
 * /tbuildings inventory — склад города
 * /tbuildings stock     — хранилище города
 * /tbuildings wonders   — меню чудес света
 */
public final class TCommand {

    private final TownBuildingsPlugin plugin;

    public TCommand(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
    }

    public @NotNull LiteralCommandNode<CommandSourceStack> buildUnder(@NotNull String root) {
        return Commands.literal(root)
                // ---- /tbuildings builds ----
                .then(Commands.literal("builds")
                        .requires(src -> src.getSender().hasPermission("townbuildings.builds"))
                        .executes(ctx -> {
                            if (!(ctx.getSource().getSender() instanceof Player player)) {
                                ctx.getSource().getSender().sendMessage(
                                        Component.text("Только для игроков.", NamedTextColor.RED));
                                return 0;
                            }
                            var town = plugin.towny().getTownOf(player);
                            if (town == null) {
                                player.sendMessage(plugin.getMessagesConfig().get("general.no-town"));
                                return 0;
                            }
                            UUID townUuid = town.getUUID();
                            MainBuildingsMenu menu = new MainBuildingsMenu(
                                    plugin, player, townUuid, town.getName());
                            plugin.menuManager().register(player, menu);
                            menu.open();
                            return 1;
                        }))
                // ---- /tbuildings inventory ----
                .then(Commands.literal("inventory")
                        .requires(src -> src.getSender().hasPermission("townbuildings.inventory"))
                        .executes(ctx -> {
                            if (!(ctx.getSource().getSender() instanceof Player player)) {
                                ctx.getSource().getSender().sendMessage(
                                        Component.text("Только для игроков.", NamedTextColor.RED));
                                return 0;
                            }
                            var town = plugin.towny().getTownOf(player);
                            if (town == null) {
                                player.sendMessage(plugin.getMessagesConfig().get("general.no-town"));
                                return 0;
                            }
                            plugin.cityInventoryMenu()
                                    .openFirstPage(player, town.getUUID(), town.getName());
                            return 1;
                        }))
                // ---- /tbuildings stock ----
                .then(Commands.literal("stock")
                        .requires(src -> src.getSender().hasPermission("townbuildings.stock"))
                        .executes(ctx -> {
                            if (!(ctx.getSource().getSender() instanceof Player player)) {
                                ctx.getSource().getSender().sendMessage(
                                        Component.text("Только для игроков.", NamedTextColor.RED));
                                return 0;
                            }
                            var town = plugin.towny().getTownOf(player);
                            if (town == null) {
                                player.sendMessage(plugin.getMessagesConfig().get("general.no-town"));
                                return 0;
                            }
                            plugin.cityStockMenu()
                                    .openFirstPage(player, town.getUUID(), town.getName());
                            return 1;
                        }))
                // ---- /tbuildings wonders ----
                .then(Commands.literal("wonders")
                        .requires(src -> src.getSender().hasPermission("townbuildings.wonders"))
                        .executes(ctx -> {
                            if (!(ctx.getSource().getSender() instanceof Player player)) {
                                ctx.getSource().getSender().sendMessage(
                                        Component.text("Только для игроков.", NamedTextColor.RED));
                                return 0;
                            }
                            var town = plugin.towny().getTownOf(player);
                            if (town == null) {
                                player.sendMessage(plugin.getMessagesConfig().get("general.no-town"));
                                return 0;
                            }
                            if (!plugin.getConfigManager().features().wonders()) {
                                player.sendActionBar(plugin.getMessagesConfig().get("general.internal-error"));
                                return 0;
                            }
                            new WonderMenu(plugin, player, town.getUUID(), town.getName(), null).open();
                            return 1;
                        }))
                .build();
    }
}