package ru.example.cloudcore.service;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.CloudCorePlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import ru.example.cloudcore.CloudCorePlugin;
/**
 * Помнит блоки, поставленные игроками.
 *
 * M7 — время берём через System.nanoTime() (монотонное): перевод системных
 *      часов назад не растягивает TTL.
 */
public final class BlockPlaceTracker {

    /** ★ M7 — монотонное время в миллисекундах. */
    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    public record Pos(@NotNull UUID world, int x, int y, int z) {
        public static @NotNull Pos of(@NotNull Block block) {
            return new Pos(block.getWorld().getUID(),
                    block.getX(), block.getY(), block.getZ());
        }
        public static @NotNull Pos of(@NotNull World w, int x, int y, int z) {
            return new Pos(w.getUID(), x, y, z);
        }
    }

    private final CloudCorePlugin plugin;
    private volatile long ttlMs;
    private final Map<Pos, Long> placed = new ConcurrentHashMap<>();

    public BlockPlaceTracker(@NotNull CloudCorePlugin plugin) {
        this.plugin = plugin;
        this.ttlMs = plugin.getConfigManager().tracking().blockPlaceTtlMinutes() * 60_000L;
    }

    public void reloadSettings() {
        this.ttlMs = plugin.getConfigManager().tracking().blockPlaceTtlMinutes() * 60_000L;
        plugin.getLogger().info("[Tracking] TTL обновлён: " + (ttlMs / 60_000) + " мин");
    }

    public void remember(@NotNull Block block) {
        placed.put(Pos.of(block), nowMs());
    }

    public boolean consume(@NotNull Block block) {
        Pos key = Pos.of(block);
        Long ts = placed.remove(key);
        if (ts == null) return false;
        return nowMs() - ts <= ttlMs;
    }

    public void sweep() {
        long now = nowMs();
        placed.entrySet().removeIf(e -> now - e.getValue() > ttlMs);
    }

    public int size()   { return placed.size(); }
    public void clear() { placed.clear(); }
}