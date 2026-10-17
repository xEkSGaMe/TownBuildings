package ru.example.townbuildings.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.palmergames.bukkit.towny.object.Town;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.service.WonderBuildResult;

import java.util.Map;
import java.util.UUID;

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
 *   /tadmin wonder give   <town> <wonderId>
 *   /tadmin wonder remove <wonderId>
 *   /tadmin wonder slots  <town> <bonus>
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
                .then(buildWonder())
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
    //   ★ /tadmin wonder give|remove|slots
    // ============================================================

    private @NotNull LiteralArgumentBuilder<CommandSourceStack> buildWonder() {
        var giveArg = Commands.argument("wonder", StringArgumentType.word())
                .suggests(AdminSuggestions.WONDERS)
                .executes(this::handleWonderGive);

        var giveTown = Commands.argument("town", StringArgumentType.word())
                .suggests(AdminSuggestions.TOWNS)
                .then(giveArg);

        var give = Commands.literal("give")
                .requires(src -> src.getSender().hasPermission("townbuildings.admin.wonders.give"))
                .then(giveTown);

        var remove = Commands.literal("remove")
                .requires(src -> src.getSender().hasPermission("townbuildings.admin.wonders.remove"))
                .then(Commands.argument("wonder", StringArgumentType.word())
                        .suggests(AdminSuggestions.WONDERS)
                        .executes(this::handleWonderRemove));

        var slots = Commands.literal("slots")
                .requires(src -> src.getSender().hasPermission("townbuildings.admin.wonders.slots"))
                .then(Commands.argument("town", StringArgumentType.word())
                        .suggests(AdminSuggestions.TOWNS)
                        .then(Commands.argument("bonus", IntegerArgumentType.integer(-100, 100))
                                .executes(this::handleWonderSlots)));

        return Commands.literal("wonder")
                .then(give).then(remove).then(slots);
    }

    private int handleWonderGive(@NotNull CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String townName = StringArgumentType.getString(ctx, "town");
        String wonderId = StringArgumentType.getString(ctx, "wonder");

        Town town = plugin.towny().getTownByName(townName);
        if (town == null) {
            sender.sendMessage(plugin.getMessagesConfig().get("admin.town-not-found",
                    Map.of("town", townName)));
            return 0;
        }
        if (plugin.wonderRegistry().byId(wonderId).isEmpty()) {
            sender.sendMessage(plugin.getMessagesConfig().get("admin.wonder-not-found",
                    Map.of("wonder", wonderId)));
            return 0;
        }

        UUID townUuid = town.getUUID();
        UUID actor = sender instanceof Player p ? p.getUniqueId() : null;

        plugin.townService().getOrCreate(townUuid, town.getName())
                .thenAccept(td -> Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.wonderService().adminGive(townUuid, wonderId, actor, result ->
                                Bukkit.getScheduler().runTask(plugin, () ->
                                        reportWonderGive(sender, town.getName(), result)))))
                .exceptionally(ex -> {
                    plugin.getLogger().warning("[Admin] wonder give(" + townUuid
                            + ") упал: " + ex.getMessage());
                    Bukkit.getScheduler().runTask(plugin, () ->
                            sender.sendMessage(plugin.getMessagesConfig()
                                    .get("general.internal-error")));
                    return null;
                });
        return 1;
    }

    private void reportWonderGive(@NotNull CommandSender sender,
                                  @NotNull String townName,
                                  @NotNull WonderBuildResult r) {
        var mc = plugin.getMessagesConfig();
        switch (r.status()) {
            case SUCCESS -> sender.sendMessage(mc.get("admin.wonder-give-success", Map.of(
                    "town",   townName,
                    "wonder", r.wonder() != null ? r.wonder().displayName() : "?")));
            case ALREADY_OWNED -> sender.sendMessage(mc.get("admin.wonder-already-owned", Map.of(
                    "town", townName,
                    "wonder", r.wonder() != null ? r.wonder().displayName() : "?")));
            case ALREADY_TAKEN -> sender.sendMessage(mc.get("wonders.upgrade.already-taken", Map.of(
                    "town", r.ownerTownName() != null ? r.ownerTownName() : "?")));
            case NO_SLOTS -> sender.sendMessage(mc.get("wonders.upgrade.no-slots", Map.of(
                    "used",  Integer.toString(r.usedSlots()),
                    "total", Integer.toString(r.slotLimit()))));
            default -> sender.sendMessage(mc.get("general.internal-error"));
        }
    }

    private int handleWonderRemove(@NotNull CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String wonderId = StringArgumentType.getString(ctx, "wonder");

        if (plugin.wonderRegistry().byId(wonderId).isEmpty()) {
            sender.sendMessage(plugin.getMessagesConfig().get("admin.wonder-not-found",
                    Map.of("wonder", wonderId)));
            return 0;
        }

        UUID owner = plugin.wonderService().getBuiltTownUuid(wonderId).orElse(null);
        if (owner == null) {
            sender.sendMessage(plugin.getMessagesConfig().get("admin.wonder-not-built",
                    Map.of("wonder", wonderId)));
            return 0;
        }
        String ownerName = plugin.wonderService().getBuiltBy(wonderId).orElse("?");
        UUID actor = sender instanceof Player p ? p.getUniqueId() : null;

        plugin.wonderService().adminRemove(wonderId, actor)
                .whenComplete((ok, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ex != null || !Boolean.TRUE.equals(ok)) {
                        sender.sendMessage(plugin.getMessagesConfig()
                                .get("general.internal-error"));
                        return;
                    }
                    sender.sendMessage(plugin.getMessagesConfig()
                            .get("admin.wonder-remove-success", Map.of(
                                    "wonder", wonderId,
                                    "town",   ownerName)));
                }));
        return 1;
    }

    private int handleWonderSlots(@NotNull CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String townName = StringArgumentType.getString(ctx, "town");
        int bonus = IntegerArgumentType.getInteger(ctx, "bonus");

        Town town = plugin.towny().getTownByName(townName);
        if (town == null) {
            sender.sendMessage(plugin.getMessagesConfig().get("admin.town-not-found",
                    Map.of("town", townName)));
            return 0;
        }

        UUID townUuid = town.getUUID();
        UUID actor = sender instanceof Player p ? p.getUniqueId() : null;

        plugin.townService().getOrCreate(townUuid, town.getName())
                .thenAccept(td -> plugin.wonderService()
                        .adminSetSlotsBonus(townUuid, bonus, actor)
                        .whenComplete((total, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (ex != null || total == null) {
                                sender.sendMessage(plugin.getMessagesConfig()
                                        .get("general.internal-error"));
                                return;
                            }
                            sender.sendMessage(plugin.getMessagesConfig()
                                    .get("admin.wonder-slots-success", Map.of(
                                            "town",  town.getName(),
                                            "bonus", Integer.toString(bonus),
                                            "total", Integer.toString(total))));
                        })))
                .exceptionally(ex -> {
                    plugin.getLogger().warning("[Admin] wonder slots(" + townUuid
                            + ") упал: " + ex.getMessage());
                    Bukkit.getScheduler().runTask(plugin, () ->
                            sender.sendMessage(plugin.getMessagesConfig()
                                    .get("general.internal-error")));
                    return null;
                });
        return 1;
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