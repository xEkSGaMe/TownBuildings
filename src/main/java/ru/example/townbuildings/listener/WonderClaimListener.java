package ru.example.townbuildings.listener;

import com.palmergames.bukkit.towny.event.TownClaimEvent;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;

/**
 * Ласточкино Гнездо: −25% к цене /t claim (возврат части в казну).
 * Towny списывает полную цену — возвращаем скидку через deposit.
 */
public final class WonderClaimListener implements Listener {

    private final TownBuildingsPlugin plugin;

    public WonderClaimListener(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClaim(@NotNull TownClaimEvent event) {
        if (!plugin.getConfigManager().features().wonders()) return;
        Town town = event.getTown();
        if (town == null) return;

        double discount = plugin.wonderService().getClaimDiscountPercent(town.getUUID());
        if (discount <= 0) return;

        try {
            double price = com.palmergames.bukkit.towny.TownySettings.getClaimPrice();
            if (price <= 0) return;
            double refund = price * Math.min(discount, 100.0) / 100.0;
            if (refund <= 0) return;
            town.getAccount().deposit(refund, "Wonder: claim discount");
        } catch (Throwable t) {
            plugin.getLogger().warning("[Wonder] claim discount refund: " + t.getMessage());
        }
    }
}