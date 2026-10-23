package ru.example.cloudcore.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.config.BuildingBranch;
import ru.example.cloudcore.config.BuildingDefinition;
import ru.example.cloudcore.config.BuildingDefinitionRegistry;
import ru.example.cloudcore.service.BuildingService;
import ru.example.cloudcore.service.UpgradeResult;
import ru.example.cloudcore.CloudCorePlugin;

import java.util.UUID;

import ru.example.cloudcore.CloudCorePlugin;
/**
 * Дебаг-команда /tb.
 *
 * M-9 — /tb list показывает branch, icon, max, levels, WIP.
 */
public final class TbCommand {

    private final CloudCorePlugin plugin;
    private final BuildingDefinitionRegistry registry;
    private final BuildingService buildingService;

    public TbCommand(@NotNull CloudCorePlugin plugin,
                     @NotNull BuildingDefinitionRegistry registry,
                     @NotNull BuildingService buildingService) {
        this.plugin = plugin;
        this.registry = registry;
        this.buildingService = buildingService;
    }

    public @NotNull LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("tb")
                .requires(src -> src.getSender().hasPermission("townbuildings.admin"))
                .then(buildList())
                .then(buildUpgrade())
                .build();
    }

    private @NotNull LiteralArgumentBuilder<CommandSourceStack> buildList() {
        return Commands.literal("list").executes(ctx -> {
            var sender = ctx.getSource().getSender();
            sender.sendMessage(Component.text(
                    "===== TownBuildings: список зданий =====", NamedTextColor.GOLD));

            for (BuildingBranch branch : BuildingBranch.values()) {
                var list = registry.ofBranch(branch);
                if (list.isEmpty()) continue;

                sender.sendMessage(Component.empty()
                        .append(Component.text("▶ ", NamedTextColor.DARK_GRAY))
                        .append(branch.asComponent())
                        .append(Component.text(" (" + list.size() + ")",
                                NamedTextColor.GRAY)));

                for (BuildingDefinition def : list) {
                    // ★ M-9 — branch + icon + max + levels + WIP.
                    // Если у BuildingDefinition нет метода levels() — убери
                    // фрагмент `+ ", levels=" + def.levels().size()`.
                    sender.sendMessage(Component.empty()
                            .append(Component.text("   • ", NamedTextColor.DARK_GRAY))
                            .append(Component.text(def.displayName(), NamedTextColor.WHITE))
                            .append(Component.text(" [" + def.id()
                                            + ", branch=" + def.branch().name()
                                            + ", icon=" + def.icon().name()
                                            + ", max=" + def.maxLevel()
                                            + ", levels=" + def.levels().size()
                                            + (def.inDevelopment() ? ", WIP" : "") + "]",
                                    NamedTextColor.DARK_GRAY)));
                }
            }
            return 1;
        });
    }

    private @NotNull LiteralArgumentBuilder<CommandSourceStack> buildUpgrade() {
        return Commands.literal("upgrade")
                .then(Commands.argument("buildingId", StringArgumentType.word())
                        .executes(ctx -> {
                            if (!(ctx.getSource().getSender() instanceof Player player)) {
                                ctx.getSource().getSender().sendMessage(
                                        Component.text("Только для игроков.", NamedTextColor.RED));
                                return 0;
                            }
                            String buildingId = StringArgumentType.getString(ctx, "buildingId");
                            var town = plugin.towny().getTownOf(player);
                            if (town == null) {
                                player.sendMessage(Component.text("Вы не в городе.",
                                        NamedTextColor.RED));
                                return 0;
                            }
                            UUID townUuid = town.getUUID();
                            player.sendMessage(Component.text(
                                    "Попытка улучшить " + buildingId + "...",
                                    NamedTextColor.YELLOW));

                            buildingService.tryUpgrade(townUuid, buildingId, player,
                                    result -> player.sendMessage(formatResult(result)));
                            return 1;
                        }));
    }

    private @NotNull Component formatResult(@NotNull UpgradeResult r) {
        NamedTextColor color = r.isSuccess() ? NamedTextColor.GREEN : NamedTextColor.RED;
        StringBuilder sb = new StringBuilder(r.status().name());
        if (r.isSuccess()) sb.append(" → уровень ").append(r.newLevel());
        if (r.check() != null) {
            sb.append(" | coins ")
                    .append(r.check().coins().have()).append("/").append(r.check().coins().need());
        }
        return Component.text(sb.toString(), color);
    }
}