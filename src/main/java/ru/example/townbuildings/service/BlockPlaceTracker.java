package ru.example.townbuildings.service;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Помнит блоки, поставленные игроками — чтобы не давать harvest-бонус
 * при их повторном разрушении.
 *
 * 11-D7 — TTL читается из config.yml (tracking.block-place-ttl-minutes).
 * 4.4   — ttlMs volatile, reloadSettings() пересчитывает без рестарта.
 */
public final class BlockPlaceTracker {

    public record Pos(@NotNull UUID world, int x, int y, int z) {
        public static @NotNull Pos of(@NotNull Block block) {
            return new Pos(block.getWorld().getUID(),
                    block.getX(), block.getY(), block.getZ());
        }
        public static @NotNull Pos of(@NotNull World w, int x, int y, int z) {
            return new Pos(w.getUID(), x, y, z);
        }
    }

    private final TownBuildingsPlugin plugin;

    /** 4.4 — volatile, чтобы reloadSettings мог переписать. */
    private volatile long ttlMs;

    private final Map<Pos, Long> placed = new ConcurrentHashMap<>();

    public BlockPlaceTracker(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
        this.ttlMs = plugin.getConfigManager().tracking().blockPlaceTtlMinutes() * 60_000L;
    }

    /**
     * 4.4 — пересчитать TTL из нового config.yml.
     */
    public void reloadSettings() {
        this.ttlMs = plugin.getConfigManager().tracking().blockPlaceTtlMinutes() * 60_000L;
        plugin.getLogger().info("[Tracking] TTL обновлён: " + (ttlMs / 60_000) + " мин");
    }

    public void remember(@NotNull Block block) {
        placed.put(Pos.of(block), System.currentTimeMillis());
    }

    public boolean consume(@NotNull Block block) {
        Pos key = Pos.of(block);
        Long ts = placed.remove(key);
        if (ts == null) return false;
        return System.currentTimeMillis() - ts <= ttlMs;
    }

    public void sweep() {
        long now = System.currentTimeMillis();
        placed.entrySet().removeIf(e -> now - e.getValue() > ttlMs);
    }

    public int size()   { return placed.size(); }
    public void clear() { placed.clear(); }
}