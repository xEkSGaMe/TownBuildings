package ru.example.cloudcore.listener;

import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.jetbrains.annotations.NotNull;

import ru.example.cloudcore.CloudCorePlugin;
/**
 * Колосс Родосский: удары игроков города поджигают цель на 3 секунды.
 * Работает только пока features.wonders = true и чудо построено.
 */
public final class WonderColossusListener implements Listener {

    private static final String WONDER_ID = "colossus_of_rhodes";
    private static final int FIRE_TICKS = 60; // 3 сек

    private final CloudCorePlugin plugin;

    public WonderColossusListener(@NotNull CloudCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(@NotNull EntityDamageByEntityEvent event) {
        if (!plugin.getConfigManager().features().wonders()) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (target.getUniqueId().equals(player.getUniqueId())) return;

        Town town = plugin.towny().getTownOf(player);
        if (town == null) return;

        boolean hasColossus = plugin.wonderService().getBuiltWonders(town.getUUID())
                .stream().anyMatch(w -> WONDER_ID.equals(w.id()));
        if (!hasColossus) return;

        target.setFireTicks(Math.max(target.getFireTicks(), FIRE_TICKS));
    }
}