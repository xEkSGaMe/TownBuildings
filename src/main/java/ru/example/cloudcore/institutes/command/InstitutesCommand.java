package ru.example.cloudcore.institutes.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.CloudCorePlugin;

/**
 * /institutes [reload]
 *
 * Быстрый reload institutes-only. Полный reload — через /tadmin reload.
 */
public class InstitutesCommand {

    private final CloudCorePlugin plugin;

    public InstitutesCommand(@NotNull CloudCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean handle(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!sender.hasPermission(plugin.institutesConfig().permissionBypass())) {
            sender.sendMessage(plugin.institutesMessages().prefixed("no-permission"));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(Component.text("Использование: /institutes reload",
                    NamedTextColor.GRAY));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            // Полный institutes-reload: конфиг + кэши + reconcile.
            plugin.institutesConfig().load();
            plugin.onInstitutesReload();
            sender.sendMessage(plugin.institutesMessages().prefixed("reloaded"));
            return true;
        }

        sender.sendMessage(Component.text("Неизвестная подкоманда.", NamedTextColor.RED));
        return true;
    }
}