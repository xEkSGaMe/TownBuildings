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
 *
 * P18 — handler'ы создаются один раз в конструкторе и переиспользуются.
 *       Раньше new AdminBuildingHandler/AdminLogsHandler/AdminInfoHandler
 *       на каждый вызов команды: лишние аллокации + GC-давление на
 *       активных админах.
 *
 * B4 — reload прерывается на первой ошибке config.yml / messages.yml /
 *      buildings.yml. Раньше при падении configManager.load() всё равно
 *      грузились messages.yml и buildings.yml → плагин оставался в смешанном
 *      состоянии (language/cache/sessions — старые, тексты/здания — новые),
 *      админ видел general.internal-error без понимания, что не применилось.
 */
public final class AdminCommand {

    private final TownBuildingsPlugin plugin;
    // P18 — handler'ы переиспользуются. Раньше new на каждый вызов
    // команды: лишние аллокации + GC-давление на активных админах.
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

                    // ★ B4 — прерываем reload на первой ошибке: иначе плагин
                    // остаётся в смешанном состоянии (config.yml старый,
                    // messages.yml / buildings.yml новые — language/cache/sessions
                    // не применились, тексты/здания применились).
                    // Хардкод-сообщение, т.к. messages.yml мог быть сломан
                    // на предыдущей попытке.

                    // 1. config.yml — базовый, от него зависят остальные.
                    try {
                        plugin.getConfigManager().load();
                    } catch (Throwable t) {
                        logReloadError("config.yml", t);
                        sender.sendMessage(Component.text("Reload не удался: config.yml ("
                                + t.getMessage() + ")", NamedTextColor.RED));
                        return 0;
                    }

                    // 2. messages.yml — тексты.
                    try {
                        plugin.getMessagesConfig().reload();
                    } catch (Throwable t) {
                        logReloadError("messages.yml", t);
                        sender.sendMessage(Component.text("Reload не удался: messages.yml ("
                                + t.getMessage() + ")", NamedTextColor.RED));
                        return 0;
                    }

                    // 3. buildings.yml — определения зданий.
                    try {
                        plugin.buildingRegistry().load();
                    } catch (Throwable t) {
                        logReloadError("buildings.yml", t);
                        sender.sendMessage(Component.text("Reload не удался: buildings.yml ("
                                + t.getMessage() + ")", NamedTextColor.RED));
                        return 0;
                    }

                    // --- Инвалидация кэшей (все 3 файла загружены успешно). ---
                    // стало (H-6):
                    try { plugin.bonusService().invalidateCaches(); } catch (Throwable ignored) {}
                    try { plugin.effectService().invalidateCaches(); } catch (Throwable ignored) {}
                    try { LoreCache.clear(); }
                    catch (Throwable ignored) {}

                    // --- Runtime-настройки сервисов из свежего config.yml. ---
                    // Ошибки в reloadSettings не откатывают базовый reload — это
                    // точечные пересчёты, их падение не делает состояние плагина
                    // неконсистентным (config уже применён, кэши сброшены).
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