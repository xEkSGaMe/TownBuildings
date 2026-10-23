package ru.example.cloudcore.institutes.util;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.CloudCorePlugin;

/**
 * Обёртка над Vault Economy.
 *
 * Отдельный класс — JVM не пытается загрузить Economy, если Vault не установлен
 * (Static initializer может бросить NoClassDefFoundError).
 *
 * Возвращается через tryCreate() — null, если Vault / provider недоступен.
 */
public final class VaultEconomyHolder {

    private final Economy economy;

    private VaultEconomyHolder(@NotNull Economy economy) {
        this.economy = economy;
    }

    public @NotNull Economy economy() { return economy; }

    public static @Nullable VaultEconomyHolder tryCreate(@NotNull CloudCorePlugin plugin) {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("[Institutes/Vault] Vault не установлен — " +
                    "экономика институтов отключена.");
            return null;
        }
        RegisteredServiceProvider<Economy> rsp =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            plugin.getLogger().warning("[Institutes/Vault] Провайдер Economy не найден — " +
                    "экономика институтов отключена.");
            return null;
        }
        return new VaultEconomyHolder(rsp.getProvider());
    }
}