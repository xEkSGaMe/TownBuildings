package ru.example.cloudcore.institutes.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.CloudCorePlugin;
import ru.example.cloudcore.institutes.data.MenuType;
import ru.example.cloudcore.institutes.gui.InstituteMenu;

import java.util.Set;

/**
 * Перехват /t religion|form|ideology → открывает InstituteMenu.
 *
 * Грациозно деградирует, если institutesService ещё не инициализирован
 * (БД в процессе загрузки или недоступна).
 */
public class CommandInterceptor implements Listener {

    private static final Set<String> ROOTS = Set.of("/t", "/towny");

    private final CloudCorePlugin plugin;

    public CommandInterceptor(@NotNull CloudCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String raw = e.getMessage();
        int space = raw.indexOf(' ');
        if (space == -1) return;

        String root = raw.substring(0, space).toLowerCase();
        if (!ROOTS.contains(root)) return;

        String rest = raw.substring(space + 1).trim().toLowerCase();
        if (rest.isEmpty()) return;

        String subCmd = rest.contains(" ")
                ? rest.substring(0, rest.indexOf(' '))
                : rest;

        MenuType type = switch (subCmd) {
            case "religion" -> MenuType.RELIGION;
            case "form"     -> MenuType.FORM;
            case "ideology" -> MenuType.IDEOLOGY;
            default -> null;
        };
        if (type == null) return;

        e.setCancelled(true);

        Player p = e.getPlayer();

        if (plugin.institutesService() == null) {
            p.sendMessage(Component.text("Институты загружаются или БД недоступна. " +
                    "Попробуйте позже.", NamedTextColor.RED));
            return;
        }

        if (!p.hasPermission(plugin.institutesConfig().permissionNode())
                && !p.hasPermission(plugin.institutesConfig().permissionBypass())) {
            p.sendMessage(plugin.institutesMessages().prefixed("no-permission"));
            return;
        }

        new InstituteMenu(plugin, type).open(p);
    }
}