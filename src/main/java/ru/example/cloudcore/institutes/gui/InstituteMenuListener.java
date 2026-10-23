package ru.example.cloudcore.institutes.gui;

import com.palmergames.bukkit.towny.object.Town;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.CloudCorePlugin;
import ru.example.cloudcore.institutes.data.GovernmentForm;
import ru.example.cloudcore.institutes.data.Ideology;
import ru.example.cloudcore.institutes.data.MenuType;
import ru.example.cloudcore.institutes.data.Religion;
import ru.example.cloudcore.institutes.service.InstituteSelectionService;

import java.util.Map;

/**
 * Обработка кликов в InstituteMenu.
 *
 * ВАЖНО: selection теперь async — сначала dao.save, затем XP + addBonusBlocks
 * на main-thread. Клик -> applyAsync(...) -> callback(Result).
 *
 * Гарантии:
 *  - inFlight per town в InstituteSelectionService блокирует параллельные клики
 *    по одному городу.
 *  - GUI перестраивается только при успешной смене/первом выборе.
 */
public class InstituteMenuListener implements Listener {

    private final CloudCorePlugin plugin;

    public InstituteMenuListener(@NotNull CloudCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (!(holder instanceof InstituteMenu menu)) return;
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getClickedInventory() == null) return;
        if (!e.getClickedInventory().equals(e.getView().getTopInventory())) return;

        Object value = menu.valueAt(e.getSlot());
        if (value == null) return;

        if (plugin.institutesService() == null) {
            player.sendMessage(Component.text("Плагин загружается или БД недоступна.",
                    NamedTextColor.RED));
            plugin.instituteSelection().playDenySound(player);
            player.closeInventory();
            return;
        }

        if (!player.hasPermission(plugin.institutesConfig().permissionNode())
                && !player.hasPermission(plugin.institutesConfig().permissionBypass())) {
            player.sendMessage(plugin.institutesMessages().prefixed("no-permission"));
            plugin.instituteSelection().playDenySound(player);
            player.closeInventory();
            return;
        }

        Town town = plugin.towny().getTownOf(player);
        if (town == null) {
            player.sendMessage(plugin.institutesMessages().prefixed("no-town"));
            plugin.instituteSelection().playDenySound(player);
            return;
        }

        MenuType type = menu.type();
        int actualCost = plugin.instituteSelection().costFor(town, type);

        // Async: dao.save → main: XP + addBonusBlocks.
        plugin.instituteSelection().applyAsync(player, town, type, value, result ->
                handleResult(player, town, menu, type, value, result, actualCost));
    }

    private void handleResult(@NotNull Player player,
                              @NotNull Town town,
                              @NotNull InstituteMenu menu,
                              @NotNull MenuType type,
                              @NotNull Object value,
                              @NotNull InstituteSelectionService.Result result,
                              int actualCost) {

        switch (result) {
            case SAME_CHOICE -> {
                player.sendMessage(plugin.institutesMessages().prefixed("choice-same"));
                plugin.instituteSelection().playDenySound(player);
            }
            case NOT_ENOUGH_XP -> {
                player.sendMessage(plugin.institutesMessages().prefixed("not-enough-xp",
                        Map.of("need", String.valueOf(actualCost),
                                "have", String.valueOf(player.getLevel()))));
                plugin.instituteSelection().playDenySound(player);
            }
            case NO_TOWN -> {
                player.sendMessage(plugin.institutesMessages().prefixed("no-town"));
                plugin.instituteSelection().playDenySound(player);
            }
            case BUSY -> {
                player.sendActionBar(Component.text("Подождите, операция выполняется…",
                        NamedTextColor.YELLOW));
            }
            case ERROR -> {
                player.sendMessage(Component.text("Ошибка сохранения. Попробуйте позже.",
                        NamedTextColor.RED));
            }
            case FIRST_CHOICE, CHANGED -> {
                String displayKey = switch (type) {
                    case RELIGION -> "religions.";
                    case FORM     -> "forms.";
                    case IDEOLOGY -> "ideologies.";
                };
                String enumKey = enumKey(type, value);
                String display = plugin.institutesConfig().display(displayKey + enumKey + ".display");

                String msgKey = result == InstituteSelectionService.Result.FIRST_CHOICE
                        ? "choice-first" : "choice-changed";
                player.sendMessage(plugin.institutesMessages().prefixed(msgKey,
                        Map.of("value", display,
                                "cost", String.valueOf(actualCost))));

                playSuccessEffect(player);

                // Перестраиваем GUI, если игрок всё ещё в нём.
                if (player.isOnline()
                        && player.getOpenInventory().getTopInventory().getHolder() == menu) {
                    menu.open(player);
                }
            }
        }
    }

    private void playSuccessEffect(@NotNull Player player) {
        player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING,
                player.getLocation().add(0, 1.0, 0), 30, 0.6, 0.6, 0.6, 0.05);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.4f);
    }

    private @NotNull String enumKey(@NotNull MenuType type, @NotNull Object value) {
        return switch (type) {
            case RELIGION -> ((Religion) value).key();
            case FORM     -> ((GovernmentForm) value).key();
            case IDEOLOGY -> ((Ideology) value).key();
        };
    }
}