package ru.example.cloudcore.listener;

import com.palmergames.bukkit.towny.event.town.TownCalculateMaxTownBlocksEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.config.BuildingRewards;
import ru.example.cloudcore.CloudCorePlugin;

import java.util.UUID;

import ru.example.cloudcore.CloudCorePlugin;
/**
 * topographic_institute: увеличение лимита чанков города.
 *
 * Towny при расчёте максимального количества townblocks стреляет
 * TownCalculateMaxTownBlocksEvent. Мы перехватываем и прибавляем
 * суммарный townChunksBonus по всем зданиям города.
 *
 * Значение абсолютное: lvl 3 даёт +500 к базовому лимиту,
 * а не сумму приростов +100+300+500.
 * Обеспечивается тем, что мы читаем townChunksBonus() у ТЕКУЩЕГО
 * уровня здания, а не копим дельту.
 */
public final class TopographicInstituteListener implements Listener {

    private final CloudCorePlugin plugin;

    public TopographicInstituteListener(@NotNull CloudCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCalculateMaxTownBlocks(@NotNull TownCalculateMaxTownBlocksEvent event) {
        if (!plugin.getConfigManager().features().topographicInstitute()) return;

        // Событие в старых версиях Towny могло вызываться async.
        // Наш listener трогает BonusService (ConcurrentHashMap) и event.set* —
        // безопасно в обоих случаях, но проверим и уйдём, если что-то не так.
        if (event.getTown() == null) return;

        UUID townUuid = event.getTown().getUUID();

        BuildingRewards rewards;
        try {
            rewards = plugin.bonusService().getRewards(townUuid);
        } catch (Throwable t) {
            plugin.getLogger().warning("[Topographic] getRewards(" + townUuid + "): " + t.getMessage());
            return;
        }
        if (rewards == null) return;

        int bonus = rewards.townChunksBonus();
        if (bonus <= 0) return;

        int base = event.getTownBlockCount();
        event.setTownBlockCount(base + bonus);
    }
}