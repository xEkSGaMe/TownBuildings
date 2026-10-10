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
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.gui.util.LoreCache;

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
 */
public final class AdminCommand {

    private final TownBuildingsPlugin plugin;

    public AdminCommand(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
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

                    Throwable firstError = null;

                    try { plugin.getConfigManager().load(); }
                    catch (Throwable t) { firstError = t; logReloadError("config.yml", t); }

                    try { plugin.getMessagesConfig().reload(); }
                    catch (Throwable t) {
                        if (firstError == null) firstError = t;
                        logReloadError("messages.yml", t);
                    }

                    try { plugin.buildingRegistry().load(); }
                    catch (Throwable t) {
                        if (firstError == null) firstError = t;
                        logReloadError("buildings.yml", t);
                    }

                    // ★ Инвалидация кэшей — в любом случае, даже если что-то упало.
                    try { plugin.bonusService().invalidateAll(); }
                    catch (Throwable ignored) {}
                    try { plugin.effectService().invalidateAll(); }
                    catch (Throwable ignored) {}
                    try { LoreCache.clear(); }
                    catch (Throwable ignored) {}

                    // 4.4 — runtime-настройки сервисов, читаемые из config.yml.
                    //       Идут ПОСЛЕ configManager.load(), чтобы читать свежий конфиг.
                    try { plugin.onlineBatchWriter().reloadSchedule(
                            plugin.getConfigManager().online().batchFlushSeconds()); }
                    catch (Throwable t) { logReloadError("online-tracking.batch-flush-seconds", t); }

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

                    if (firstError != null) {
                        sender.sendMessage(plugin.getMessagesConfig()
                                .get("general.internal-error"));
                        plugin.getLogger().severe("[Admin] Reload частично упал: "
                                + firstError.getMessage());
                        return 0;
                    }

                    sender.sendMessage(plugin.getMessagesConfig().get("general.reloaded"));
                    plugin.logService().logReload(
                            sender instanceof Player p ? p.getUniqueId() : null);
                    plugin.getLogger().info("[Admin] Конфиги перезагружены игроком "
                            + sender.getName());
                    return 1;
                });
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

    private @NotNull LiteralArgumentBuilder<CommandSourceStack> buildLogs() {
        return Commands.literal("logs")
                .requires(src -> src.getSender().hasPermission("townbuildings.admin.logs"))
                .then(Commands.argument("town", StringArgumentType.word())
                        .suggests(AdminSuggestions.TOWNS)
                        .executes(ctx -> handleLogs(ctx, 10))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 200))
                                .executes(ctx -> handleLogs(ctx,
                                        IntegerArgumentType.getInteger(ctx, "limit")))));
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
                            new AdminInfoHandler(plugin).handle(ctx.getSource().getSender(), townName);
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
        new AdminBuildingHandler(plugin).giveOrSet(sender, townName, buildingId, level, isSetLevel);
        return 1;
    }

    private int handleRemove(@NotNull CommandContext<CommandSourceStack> ctx, int level) {
        CommandSender sender = ctx.getSource().getSender();
        String townName = StringArgumentType.getString(ctx, "town");
        String buildingId = StringArgumentType.getString(ctx, "building");
        new AdminBuildingHandler(plugin).remove(sender, townName, buildingId, level);
        return 1;
    }

    private int handleLogs(@NotNull CommandContext<CommandSourceStack> ctx, int limit) {
        CommandSender sender = ctx.getSource().getSender();
        String townName = StringArgumentType.getString(ctx, "town");
        new AdminLogsHandler(plugin).showLogs(sender, townName, limit);
        return 1;
    }

    // ============================================================
    //   Вспомогательный вывод
    // ============================================================

    /** Заголовок из messages.yml: admin.header. */
    static @NotNull Component header(@NotNull TownBuildingsPlugin plugin, @NotNull String title) {
        return plugin.getMessagesConfig().get("admin.header", Map.of("title", title));
    }

    /** Галочка + текст (без префикса). */
    static void ok(@NotNull TownBuildingsPlugin plugin, @NotNull CommandSender to,
                   @NotNull String msg) {
        to.sendMessage(Component.text("✔ ", NamedTextColor.GREEN)
                .append(Component.text(msg, NamedTextColor.WHITE)));
    }

    /** Крестик + текст (без префикса). */
    static void fail(@NotNull TownBuildingsPlugin plugin, @NotNull CommandSender to,
                     @NotNull String msg) {
        to.sendMessage(Component.text("✘ ", NamedTextColor.RED)
                .append(Component.text(msg, NamedTextColor.WHITE)));
    }
}