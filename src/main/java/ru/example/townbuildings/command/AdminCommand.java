package ru.example.townbuildings.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;

import java.util.Map;

/**
 * Корневая команда /tadmin.
 *
 * Подкоманды:
 *   /tadmin reload
 *   /tadmin give <town> <building> [level]
 *   /tadmin remove <town> <building> [level]
 *   /tadmin setlevel <town> <building> <level>
 *   /tadmin logs <town> [limit]
 *   /tadmin info <town>
 *
 * P18 — handler'ы создаются один раз в конструкторе и переиспользуются.
 * B4  — reload прерывается на первой ошибке config.yml / messages.yml / buildings.yml.
 * M1  — после reload вызываем TownyWarService.retryFetch().
 * M11 — reload уведён в async; main-блок — только инвалидация кэшей и сообщения.
 * L1.6 — удалены неиспользуемые ok/fail.
 * L1.8 — удалён вызов LoreCache.clear() (класс LoreCache удалён целиком).
 */
public final class AdminCommand {

    private final TownBuildingsPlugin plugin;
    // P18 — handler'ы переиспользуются.
    private final AdminBuildingHandler buildingHandler;
    private final AdminLogsHandler logsHandler;
    private final AdminInfoHandler infoHandler;

    public AdminCommand(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
        this.buildingHandler = new AdminBuildingHandler(plugin);
        this.logsHandler = new AdminLogsHandler(plugin);
        this.infoHandler = new AdminInfoHandler(plugin);
    }

    public @NotNull LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("tadmin")
                .requires(src -> src.getSender().hasPermission("townbuildings.admin"))
                .then(buildReload())
                .then(buildGive())
                .then(buildRemove())
                .then(buildSetLevel())
                .then(buildLogs())
                .then(buildInfo())
                .build();
    }

    // ============================================================
    //   /tadmin reload
    // ============================================================

    private @NotNull LiteralArgumentBuilder<CommandSourceStack> buildReload() {
        return Commands.literal("reload")
                .requires(src -> src.getSender().hasPermission("townbuildings.admin.reload"))
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    sender.sendMessage(Component.text("Перезагрузка конфигов...",
                            NamedTextColor.GRAY));

                    // ★ M11 — reload в async, чтобы не фризить main на больших yml.
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        // 1. config.yml
                        try {
                            plugin.getConfigManager().load();
                        } catch (Throwable t) {
                            logReloadError("config.yml", t);
                            replyReload(sender, "config.yml", t);
                            return;
                        }

                        // 2. messages.yml
                        try {
                            plugin.getMessagesConfig().reload();
                        } catch (Throwable t) {
                            logReloadError("messages.yml", t);
                            replyReload(sender, "messages.yml", t);
                            return;
                        }

                        // 3. buildings.yml
                        try {
                            plugin.buildingRegistry().load();
                        } catch (Throwable t) {
                            logReloadError("buildings.yml", t);
                            replyReload(sender, "buildings.yml", t);
                            return;
                        }

                        // Обратно на main — инвалидация кэшей, runtime-reload, сообщения.
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            // ★ L1.8 — LoreCache удалён; вызов убран.
                            try { plugin.bonusService().invalidateCaches(); } catch (Throwable ignored) {}
                            try { plugin.effectService().invalidateCaches(); } catch (Throwable ignored) {}

                            try {
                                plugin.onlineBatchWriter().reloadSchedule(
                                        plugin.getConfigManager().online().batchFlushSeconds());
                            } catch (Throwable t) {
                                logReloadError("online-tracking.batch-flush-seconds", t);
                            }

                            try { plugin.inventorySessions().reloadSettings(); }
                            catch (Throwable t) { logReloadError("sessions (inventory)", t); }

                            try { plugin.stockSessions().reloadSettings(); }
                            catch (Throwable t) { logReloadError("sessions (stock)", t); }

                            try { plugin.effectService().reloadSettings(); }
                            catch (Throwable t) { logReloadError("effects", t); }

                            try { plugin.blockPlaceTracker().reloadSettings(); }
                            catch (Throwable t) { logReloadError("tracking", t); }

                            try { plugin.rewardService().reloadSettings(); }
                            catch (Throwable t) { logReloadError("online-tracking.max-hours-per-batch", t); }

                            // ★ M1 — попробовать снова подхватить TownyWar API.
                            try { plugin.townyWarService().retryFetch(); }
                            catch (Throwable t) { logReloadError("townywar", t); }

                            sender.sendMessage(plugin.getMessagesConfig().get("general.reloaded"));
                            plugin.logService().logReload(
                                    sender instanceof Player p ? p.getUniqueId() : null);
                            plugin.getLogger().info("[Admin] Конфиги перезагружены игроком "
                                    + sender.getName());
                        });
                    });

                    return 1;
                });
    }

    /** ★ M11 — хардкод-ответ при сбое (messages.yml мог быть сломан). */
    private static void replyReload(@NotNull CommandSender sender,
                                    @NotNull String file, @NotNull Throwable t) {
        Bukkit.getScheduler().runTask(TownBuildingsPlugin.getInstance(), () ->
                sender.sendMessage(Component.text("Reload не удался: " + file
                        + " (" + t.getMessage() + ")", NamedTextColor.RED)));
    }

    private static void logReloadError(@NotNull String file, @NotNull Throwable t) {
        TownBuildingsPlugin.getInstance().getLogger()
                .severe("[Admin] Ошибка reload " + file + ": " + t.getMessage());
    }

    // ============================================================
    //   /tadmin give <town> <building> [level]
    // ============================================================

    private @NotNull LiteralArgumentBuilder<CommandSourceStack> buildGive() {
        return Commands.literal("give")
                .requires(src -> src.getSender().hasPermission("townbuildings.admin.give"))
                .then(Commands.argument("town", StringArgumentType.word())
                        .suggests(AdminSuggestions.TOWNS)
                        .then(Commands.argument("building", StringArgumentType.word())
                                .suggests(AdminSuggestions.BUILDINGS)
                                .executes(ctx -> handleGiveOrSet(ctx, false, -1))
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 100))
                                        .executes(ctx -> handleGiveOrSet(ctx, false,
                                                IntegerArgumentType.getInteger(ctx, "level"))))));
    }

    // ============================================================
    //   /tadmin remove <town> <building> [level]
    // ============================================================

    private @NotNull LiteralArgumentBuilder<CommandSourceStack> buildRemove() {
        return Commands.literal("remove")
                .requires(src -> src.getSender().hasPermission("townbuildings.admin.remove"))
                .then(Commands.argument("town", StringArgumentType.word())
                        .suggests(AdminSuggestions.TOWNS)
                        .then(Commands.argument("building", StringArgumentType.word())
                                .suggests(AdminSuggestions.BUILDINGS)
                                .executes(ctx -> handleRemove(ctx, -1))
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 100))
                                        .executes(ctx -> handleRemove(ctx,
                                                IntegerArgumentType.getInteger(ctx, "level"))))));
    }

    // ============================================================
    //   /tadmin setlevel <town> <building> <level>
    // ============================================================

    private @NotNull LiteralArgumentBuilder<CommandSourceStack> buildSetLevel() {
        return Commands.literal("setlevel")
                .requires(src -> src.getSender().hasPermission("townbuildings.admin.setlevel"))
                .then(Commands.argument("town", StringArgumentType.word())
                        .suggests(AdminSuggestions.TOWNS)
                        .then(Commands.argument("building", StringArgumentType.word())
                                .suggests(AdminSuggestions.BUILDINGS)
                                .then(Commands.argument("level", IntegerArgumentType.integer(0, 100))
                                        .executes(ctx -> handleGiveOrSet(ctx, true,
                                                IntegerArgumentType.getInteger(ctx, "level"))))));
    }

    // ============================================================
    //   /tadmin logs <town> [limit]
    // ============================================================

    /**
     * ★ FIX — buildLogs имеет ДВА уровня .then() (town → limit), а не три
     * как buildGive/buildRemove/buildSetLevel. Раньше trailing `)))...`
     * копировался «по образцу» buildGive и получалась одна лишняя `)`.
     *
     * Разбито на два `var` — скобки легко проверить глазами.
     */
    private @NotNull LiteralArgumentBuilder<CommandSourceStack> buildLogs() {
        var limitArg = Commands.argument("limit", IntegerArgumentType.integer(1, 200))
                .executes(ctx -> handleLogs(ctx,
                        IntegerArgumentType.getInteger(ctx, "limit")));

        var townArg = Commands.argument("town", StringArgumentType.word())
                .suggests(AdminSuggestions.TOWNS)
                .executes(ctx -> handleLogs(ctx, 10))
                .then(limitArg);

        return Commands.literal("logs")
                .requires(src -> src.getSender().hasPermission("townbuildings.admin.logs"))
                .then(townArg);
    }

    // ============================================================
    //   /tadmin info <town>
    // ============================================================

    private @NotNull LiteralArgumentBuilder<CommandSourceStack> buildInfo() {
        return Commands.literal("info")
                .requires(src -> src.getSender().hasPermission("townbuildings.admin"))
                .then(Commands.argument("town", StringArgumentType.word())
                        .suggests(AdminSuggestions.TOWNS)
                        .executes(ctx -> {
                            String townName = StringArgumentType.getString(ctx, "town");
                            infoHandler.handle(ctx.getSource().getSender(), townName);
                            return 1;
                        }));
    }

    // ============================================================
    //   Обработчики
    // ============================================================

    private int handleGiveOrSet(@NotNull CommandContext<CommandSourceStack> ctx,
                                boolean isSetLevel, int level) {
        CommandSender sender = ctx.getSource().getSender();
        String townName = StringArgumentType.getString(ctx, "town");
        String buildingId = StringArgumentType.getString(ctx, "building");
        buildingHandler.giveOrSet(sender, townName, buildingId, level, isSetLevel);
        return 1;
    }

    private int handleRemove(@NotNull CommandContext<CommandSourceStack> ctx, int level) {
        CommandSender sender = ctx.getSource().getSender();
        String townName = StringArgumentType.getString(ctx, "town");
        String buildingId = StringArgumentType.getString(ctx, "building");
        buildingHandler.remove(sender, townName, buildingId, level);
        return 1;
    }

    private int handleLogs(@NotNull CommandContext<CommandSourceStack> ctx, int limit) {
        CommandSender sender = ctx.getSource().getSender();
        String townName = StringArgumentType.getString(ctx, "town");
        logsHandler.showLogs(sender, townName, limit);
        return 1;
    }

    // ============================================================
    //   Вспомогательный вывод
    // ============================================================

    /** Заголовок из messages.yml: admin.header. */
    static @NotNull Component header(@NotNull TownBuildingsPlugin plugin, @NotNull String title) {
        return plugin.getMessagesConfig().get("admin.header", Map.of("title", title));
    }
}