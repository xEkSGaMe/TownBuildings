package ru.example.cloudcore.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.CloudCorePlugin;
import ru.example.cloudcore.database.model.BuildingData;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Библиотека 2 уровня: обмен опыта игрока на пузырьки опыта (Bottle o' Enchanting).
 *
 * /bottle get max      — конвертировать весь доступный опыт (ограничено числом пустых бутылок)
 * /bottle get {amount} — конвертировать ровно {amount} пузырьков опыта
 *
 * Курс обмена по официальной информации Minecraft 1.21.11:
 * Bottle o' Enchanting при разбитии даёт 3–11 XP, в среднем 7 XP
 * (https://minecraft.wiki/w/Bottle_o%27_Enchanting). Поэтому 1 пузырёк = 7 EXP.
 */
public final class BottleCommand {

    private static final String LIBRARY_ID = "library";
    private static final int REQUIRED_LIBRARY_LEVEL = 2;
    private static final int XP_PER_BOTTLE = 7;
    private static final String PERMISSION = "townbuildings.bottle";

    private final CloudCorePlugin plugin;

    public BottleCommand(@NotNull CloudCorePlugin plugin) {
        this.plugin = plugin;
    }

    public @NotNull LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("bottle")
                .then(Commands.literal("get")
                        .requires(src -> src.getSender().hasPermission(PERMISSION))
                        .executes(ctx -> {
                            if (!(ctx.getSource().getSender() instanceof Player player)) {
                                plugin.getMessagesConfig().send(
                                        ctx.getSource().getSender(), "general.player-only");
                                return 0;
                            }
                            plugin.getMessagesConfig().send(player, "bottle.usage");
                            return 0;
                        })
                        // ---- /bottle get max ----
                        .then(Commands.literal("max").executes(ctx -> {
                            if (!(ctx.getSource().getSender() instanceof Player player)) {
                                plugin.getMessagesConfig().send(
                                        ctx.getSource().getSender(), "general.player-only");
                                return 0;
                            }
                            handle(player, null);
                            return 1;
                        }))
                        // ---- /bottle get {amount} ----
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                                        plugin.getMessagesConfig().send(
                                                ctx.getSource().getSender(), "general.player-only");
                                        return 0;
                                    }
                                    int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                    handle(player, amount);
                                    return 1;
                                })))
                .build();
    }

    private void handle(@NotNull Player player, @Nullable Integer requested) {
        if (!plugin.getConfigManager().features().libraryExp()) {
            fail(player, "bottle.disabled");
            return;
        }

        var town = plugin.towny().getTownOf(player);
        if (town == null) {
            fail(player, "general.no-town");
            return;
        }
        UUID townUuid = town.getUUID();

        // Быстрый путь: кэш зданий обычно прогрет при входе / после улучшения.
        List<BuildingData> cached = plugin.townService().cache().getBuildings(townUuid);
        if (cached != null) {
            execute(player, requested, cached);
            return;
        }

        // Холодный кэш — читаем из БД, но все действия с инвентарём и EXPERIENCE
        // выполняем обратно на главном потоке.
        plugin.townService().getBuildings(townUuid).whenComplete((buildings, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (ex != null || buildings == null) {
                fail(player, "general.db-error");
                return;
            }
            execute(player, requested, buildings);
        }));
    }

    private void execute(@NotNull Player player,
                         @Nullable Integer requested,
                         @NotNull List<BuildingData> buildings) {
        boolean hasLibrary = buildings.stream()
                .anyMatch(b -> LIBRARY_ID.equals(b.buildingId()) && b.level() >= REQUIRED_LIBRARY_LEVEL);
        if (!hasLibrary) {
            fail(player, "bottle.no-library");
            return;
        }

        int totalExp = player.getTotalExperience();
        int bottlesHave = countItem(player.getInventory(), Material.GLASS_BOTTLE);

        // max = столько пузырьков, сколько хватит пустых бутылок.
        // Опыт, который не влез, остаётся у игрока — об этом отдельное уведомление.
        int maxAffordable = totalExp / XP_PER_BOTTLE;
        int amount;
        boolean partial = false;

        if (requested == null) {
            if (maxAffordable <= 0) {
                fail(player, "bottle.no-exp", Map.of(
                        "have", String.valueOf(totalExp),
                        "need", String.valueOf(XP_PER_BOTTLE)));
                return;
            }
            if (bottlesHave == 0) {
                fail(player, "bottle.no-bottles", Map.of(
                        "have", String.valueOf(0),
                        "need", String.valueOf(maxAffordable)));
                return;
            }
            amount = Math.min(maxAffordable, bottlesHave);
            partial = bottlesHave < maxAffordable;
        } else {
            amount = requested;
        }

        if (amount <= 0) {
            fail(player, "bottle.no-exp", Map.of(
                    "have", String.valueOf(totalExp),
                    "need", String.valueOf(XP_PER_BOTTLE)));
            return;
        }

        if (bottlesHave < amount) {
            fail(player, "bottle.no-bottles", Map.of(
                    "have", String.valueOf(bottlesHave),
                    "need", String.valueOf(amount)));
            return;
        }

        long needExpLong = (long) amount * XP_PER_BOTTLE;
        if (needExpLong > totalExp) {
            fail(player, "bottle.no-exp", Map.of(
                    "have", String.valueOf(totalExp),
                    "need", String.valueOf(needExpLong)));
            return;
        }

        PlayerInventory inv = player.getInventory();
        ItemStack glassCost = new ItemStack(Material.GLASS_BOTTLE, amount);

        // Забираем пустые бутылки. При неожиданной гонке возвращаем списанное.
        Map<Integer, ItemStack> notRemoved = inv.removeItem(glassCost);
        if (!notRemoved.isEmpty()) {
            int removed = amount - notRemoved.values().stream().mapToInt(ItemStack::getAmount).sum();
            if (removed > 0) inv.addItem(new ItemStack(Material.GLASS_BOTTLE, removed));
            fail(player, "bottle.no-bottles", Map.of(
                    "have", String.valueOf(bottlesHave - removed),
                    "need", String.valueOf(amount)));
            return;
        }

        // Проверяем место под результат до списания опыта.
        if (countFit(inv, Material.EXPERIENCE_BOTTLE) < amount) {
            inv.addItem(glassCost);
            fail(player, "bottle.no-space");
            return;
        }

        int needExp = (int) needExpLong;
        player.setTotalExperience(totalExp - needExp);
        inv.addItem(new ItemStack(Material.EXPERIENCE_BOTTLE, amount));

        plugin.getMessagesConfig().send(player, "bottle.success", Map.of(
                "exp", String.valueOf(needExp),
                "amount", String.valueOf(amount)));

        if (partial) {
            plugin.getMessagesConfig().send(player, "bottle.partial", Map.of(
                    "exp", String.valueOf(totalExp - needExp),
                    "bottles", String.valueOf(maxAffordable - amount)));
        }
        plugin.soundService().playUpgradeSuccess(player);
    }

    private void fail(@NotNull Player player, @NotNull String key) {
        fail(player, key, Map.of());
    }

    private void fail(@NotNull Player player, @NotNull String key,
                      @NotNull Map<String, String> placeholders) {
        plugin.getMessagesConfig().send(player, key, placeholders);
        plugin.soundService().playError(player);
    }

    private static int countItem(@NotNull PlayerInventory inv, @NotNull Material material) {
        int count = 0;
        for (ItemStack stack : inv.getStorageContents()) {
            if (stack != null && stack.getType() == material) count += stack.getAmount();
        }
        return count;
    }

    /** Сколько предметов given-материала поместится в инвентарь без учёта уже списанных бутылок. */
    private static int countFit(@NotNull PlayerInventory inv, @NotNull Material material) {
        int maxStack = material.getMaxStackSize();
        int fit = 0;
        for (ItemStack stack : inv.getStorageContents()) {
            if (stack == null || stack.isEmpty()) {
                fit += maxStack;
            } else if (stack.getType() == material) {
                fit += Math.max(0, maxStack - stack.getAmount());
            }
        }
        return fit;
    }
}
