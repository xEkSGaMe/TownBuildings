package ru.example.cloudcore.listener;

import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.potion.PotionEffect;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.CloudCorePlugin;
import ru.example.cloudcore.config.HolySiteEffects;
import ru.example.cloudcore.institutes.data.TownInstituteData;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Боевые и бытовые бонусы Священных мест.
 *
 * Все обработчики сначала проверяют, что у города игрока есть Священное
 * место и что соответствующий бонус включён. При выключенном
 * features.holy-sites слушатель не делает ничего.
 *
 * Экономические бонусы (монеты, сток, опыт, чанки, слоты) идут через
 * BuildingRewards ⇒ BonusService/RewardService, здесь их нет.
 */
public final class HolySiteEffectListener implements Listener {

    private final CloudCorePlugin plugin;

    public HolySiteEffectListener(@NotNull CloudCorePlugin plugin) {
        this.plugin = plugin;
    }

    // ============================================================
    //   Сырое мясо ×2 голода
    // ============================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRawMeat(@NotNull PlayerItemConsumeEvent event) {
        if (!enabled()) return;
        if (!isRawMeat(event.getItem().getType())) return;

        Player player = event.getPlayer();
        HolySiteEffects effects = effectsOf(player);
        if (!effects.doubleRawMeat()) return;

        final int foodBefore = player.getFoodLevel();
        final float satBefore = player.getSaturation();

        // Ванильное насыщение применится после события — на следующем тике
        // считаем фактическую дельту и добавляем её ещё раз.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.isDead()) return;
            int foodDelta = player.getFoodLevel() - foodBefore;
            if (foodDelta > 0) {
                player.setFoodLevel(Math.min(20, foodBefore + foodDelta * 2));
            }
            float satDelta = player.getSaturation() - satBefore;
            if (satDelta > 0) {
                float target = satBefore + satDelta * 2.0f;
                player.setSaturation(Math.min(player.getFoodLevel(), target));
            }
        });
    }

    private static boolean isRawMeat(@NotNull Material m) {
        return m == Material.BEEF || m == Material.PORKCHOP || m == Material.CHICKEN
                || m == Material.MUTTON || m == Material.RABBIT;
    }

    // ============================================================
    //   Урон: +% по игрокам с религией / ×2 по монстрам / HP<40%
    // ============================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageByPlayer(@NotNull EntityDamageByEntityEvent event) {
        if (!enabled()) return;
        if (!(event.getDamager() instanceof Player attacker)) return;

        HolySiteEffects effects = effectsOf(attacker);
        if (!effects.hasAny()) return;

        double damage = event.getDamage();

        if (effects.damageVsReligiousPercent() > 0.0
                && event.getEntity() instanceof Player victim
                && hasReligion(victim)) {
            damage *= 1.0 + effects.damageVsReligiousPercent() / 100.0;
        }

        if (effects.monsterDamageMultiplier() > 1.0
                && event.getEntity() instanceof Enemy) {
            damage *= effects.monsterDamageMultiplier();
        }

        if (effects.lowHealthDamagePercent() > 0.0 && isBelowHealthThreshold(attacker, effects)) {
            damage *= 1.0 + effects.lowHealthDamagePercent() / 100.0;
        }

        if (damage != event.getDamage()) event.setDamage(damage);
    }

    private boolean isBelowHealthThreshold(@NotNull Player player, @NotNull HolySiteEffects effects) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        double max = attr != null ? attr.getValue() : 20.0;
        if (max <= 0) return false;
        double pct = player.getHealth() / max * 100.0;
        return pct < effects.lowHealthThresholdPercent();
    }

    private boolean hasReligion(@NotNull Player player) {
        var institutes = plugin.institutesService();
        if (institutes == null || plugin.towny() == null) return false;
        Town town = plugin.towny().getTownOf(player);
        if (town == null) return false;
        TownInstituteData data = institutes.getCached(town.getUUID());
        return data != null && data.religion() != null;
    }

    // ============================================================
    //   Уклонение
    // ============================================================

    /**
     * Уклонение работает только от атак (EntityDamageByEntityEvent), а не
     * от падения/огня/пустоты — иначе «додж» отменял бы урон окружения.
     * HIGHEST: выполняется после onDamageByPlayer (HIGH), чтобы отмена
     * события не мешала расчёту усиленного урона.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDodge(@NotNull EntityDamageByEntityEvent event) {
        if (!enabled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getDamage() <= 0.0) return;

        HolySiteEffects effects = effectsOf(player);
        if (effects.dodgeChancePercent() <= 0.0) return;

        double roll = ThreadLocalRandom.current().nextDouble(100.0);
        if (roll >= effects.dodgeChancePercent()) return;

        event.setCancelled(true);
        player.sendActionBar(plugin.getMessagesConfig().get("holy-sites.effect.dodge"));
    }

    // ============================================================
    //   Длительность зелий ×2
    // ============================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionApplied(@NotNull EntityPotionEffectEvent event) {
        if (!enabled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getAction() == EntityPotionEffectEvent.Action.REMOVED
                || event.getAction() == EntityPotionEffectEvent.Action.CLEARED) return;

        EntityPotionEffectEvent.Cause cause = event.getCause();
        if (cause != EntityPotionEffectEvent.Cause.POTION_DRINK
                && cause != EntityPotionEffectEvent.Cause.POTION_SPLASH
                && cause != EntityPotionEffectEvent.Cause.AREA_EFFECT_CLOUD) return;

        PotionEffect applied = event.getNewEffect();
        if (applied == null) return;

        HolySiteEffects effects = effectsOf(player);
        if (effects.potionDurationMultiplier() <= 1.0) return;

        int targetDuration = (int) Math.min(Integer.MAX_VALUE,
                Math.round(applied.getDuration() * effects.potionDurationMultiplier()));
        if (targetDuration <= applied.getDuration()) return;

        // setNewEffect в API нет — переприменяем на следующем тике.
        // Причина будет PLUGIN, поэтому наш же обработчик не зациклится.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.isDead()) return;
            PotionEffect current = player.getPotionEffect(applied.getType());
            if (current == null || current.getDuration() <= 0) return;
            if (current.getDuration() >= targetDuration) return;
            player.addPotionEffect(new PotionEffect(
                    applied.getType(), targetDuration, current.getAmplifier(),
                    current.isAmbient(), current.hasParticles(), current.hasIcon()));
        });
    }

    // ============================================================
    //   Хелперы
    // ============================================================

    private boolean enabled() {
        return plugin.getConfigManager().features().holySites()
                && plugin.holySiteService() != null;
    }

    private @NotNull HolySiteEffects effectsOf(@NotNull Player player) {
        if (plugin.towny() == null) return HolySiteEffects.EMPTY;
        Town town = plugin.towny().getTownOf(player);
        if (town == null) return HolySiteEffects.EMPTY;
        UUID townUuid = town.getUUID();
        return plugin.holySiteService().getEffects(townUuid);
    }

    /** Используется ReligionBonusListener для усиления регенерации GLADIATOR. */
    public @Nullable HolySiteEffects effectsFor(@NotNull Player player) {
        if (!enabled()) return null;
        return effectsOf(player);
    }
}
