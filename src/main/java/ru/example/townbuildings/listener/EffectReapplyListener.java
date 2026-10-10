package ru.example.townbuildings.listener;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Митигатор: некоторые плагины (или ванильная механика при
 * cross-world-переходе) снимают PotionEffect на телепорт / респавн.
 *
 * 4.2 — коалессинг: на все события в одном тике — одна задача.
 *       Плюс игнор minecraft:* команд и открытия инвентаря игрока
 *       (там эффекты не слетают).
 */
public final class EffectReapplyListener implements Listener {

    private final TownBuildingsPlugin plugin;

    /** 4.2 — pending-очередь на текущий тик. */
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    /** Флаг: задача на текущий тик уже запланирована. */
    private final AtomicBoolean taskScheduled = new AtomicBoolean(false);

    public EffectReapplyListener(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(@NotNull PlayerTeleportEvent event) {
        enqueue(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRespawn(@NotNull PlayerRespawnEvent event) {
        enqueue(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(@NotNull InventoryOpenEvent event) {
        // 4.2 — открытие инвентаря игрока не снимает эффекты.
        if (event.getInventory().getType() == InventoryType.PLAYER) return;
        if (event.getPlayer() instanceof Player p) enqueue(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(@NotNull PlayerCommandPreprocessEvent event) {
        // 4.2 — ванильные команды не снимают эффекты.
        String msg = event.getMessage();
        if (msg.startsWith("/minecraft:")) return;
        enqueue(event.getPlayer());
    }

    private void enqueue(@NotNull Player player) {
        if (!player.isOnline()) return;
        pending.add(player.getUniqueId());

        // 4.2 — задача на этот тик уже есть — просто добавили игрока в pending.
        if (!taskScheduled.compareAndSet(false, true)) return;

        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            taskScheduled.set(false);
            processPending();
        }, 1L);
    }

    private void processPending() {
        if (pending.isEmpty()) return;

        // Снимок и очистка. Игроки, пришедшие во время обработки, попадут
        // в следующий тик (их enqueue увидит taskScheduled=false).
        Set<UUID> batch = ConcurrentHashMap.newKeySet();
        batch.addAll(pending);
        pending.removeAll(batch);

        for (UUID uuid : batch) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;

            Resident res = TownyAPI.getInstance().getResident(uuid);
            if (res == null || !res.hasTown()) continue;
            Town town = res.getTownOrNull();
            if (town == null) continue;

            plugin.effectService().reapplyFor(p, town.getUUID());
        }
    }
}