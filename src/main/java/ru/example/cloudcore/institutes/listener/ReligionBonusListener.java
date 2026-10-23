package ru.example.cloudcore.institutes.listener;

import com.palmergames.bukkit.towny.object.Town;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.CloudCorePlugin;
import ru.example.cloudcore.institutes.data.Religion;
import ru.example.cloudcore.institutes.data.TownInstituteData;
import ru.example.cloudcore.institutes.util.LuckPermsUtil;

/**
 * Религиозные бонусы и штрафы на уровне событий.
 *
 * Сообщения переведены на Adventure Component; тексты сохранены как в
 * старой версии Institutes.
 */
public class ReligionBonusListener implements Listener {

    private static final String TNT_META = "cloudcore_institutes_no_block_damage";

    private final CloudCorePlugin plugin;

    public ReligionBonusListener(@NotNull CloudCorePlugin plugin) {
        this.plugin = plugin;
    }

    // ============================================================
    //   ПРАВОСЛАВИЕ
    // ============================================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOrthodoxConsume(PlayerItemConsumeEvent e) {
        TownInstituteData data = dataOf(e.getPlayer());
        if (data == null || data.religion() != Religion.ORTHODOX) return;

        Player player = e.getPlayer();
        Material m = e.getItem().getType();

        if (m == Material.PUMPKIN_PIE || m == Material.COOKIE) {
            e.setCancelled(true);
            player.sendMessage(Component.text(
                    "Ваша вера запрещает вам есть сладкое.", NamedTextColor.RED));
            return;
        }

        if (m == Material.BREAD) {
            final int foodBefore = player.getFoodLevel();
            final float satBefore = player.getSaturation();
            Bukkit.getScheduler().runTask(plugin, () -> {
                int targetFood = Math.min(foodBefore + 8, 20);
                float targetSat = Math.min(satBefore + 8.0f, (float) targetFood);
                player.setFoodLevel(targetFood);
                player.setSaturation(targetSat);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_BURP, 0.5f, 1f);
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOrthodoxCake(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = e.getClickedBlock();
        if (block == null || block.getType() != Material.CAKE) return;

        TownInstituteData data = dataOf(e.getPlayer());
        if (data == null || data.religion() != Religion.ORTHODOX) return;

        e.setCancelled(true);
        e.getPlayer().sendMessage(Component.text(
                "Ваша вера запрещает вам есть сладкое.", NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOrthodoxDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        TownInstituteData data = dataOf(attacker);
        if (data == null || data.religion() != Religion.ORTHODOX) return;

        double mult = LuckPermsUtil.hasGroupOrHigher(attacker,
                plugin.institutesConfig().eliteGroupWarden())
                ? plugin.institutesConfig().orthodoxDamageElite()
                : plugin.institutesConfig().orthodoxDamage();
        e.setDamage(e.getDamage() * mult);
    }

    // ============================================================
    //   КАТОЛИЦИЗМ
    // ============================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCatholicConsume(PlayerItemConsumeEvent e) {
        TownInstituteData data = dataOf(e.getPlayer());
        if (data == null || data.religion() != Religion.CATHOLIC) return;

        Material m = e.getItem().getType();
        if (m == Material.MUTTON || m == Material.COOKED_MUTTON
                || m == Material.RABBIT || m == Material.COOKED_RABBIT) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Component.text(
                    "Ваша вера не позволяет вам есть это мясо.", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCatholicKill(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;

        TownInstituteData victimData = dataOf(victim);
        if (victimData == null || victimData.religion() != Religion.ISLAM) return;

        TownInstituteData killerData = dataOf(killer);
        if (killerData == null || killerData.religion() != Religion.CATHOLIC) return;

        double max = 20.0;
        AttributeInstance attr = killer.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) max = attr.getValue();

        killer.setHealth(Math.min(killer.getHealth() + 6.0, max));
        killer.sendMessage(Component.text(
                "Крестовый поход: +3 сердца!", NamedTextColor.GOLD));
    }

    // ============================================================
    //   ИСЛАМ
    // ============================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIslamConsume(PlayerItemConsumeEvent e) {
        TownInstituteData data = dataOf(e.getPlayer());
        if (data == null || data.religion() != Religion.ISLAM) return;

        Player player = e.getPlayer();
        Material m = e.getItem().getType();

        if (m == Material.PORKCHOP || m == Material.COOKED_PORKCHOP) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,
                    400, 1, true, false, false));
            player.sendMessage(Component.text(
                    "Ваша вера запрещает вам есть свинину!", NamedTextColor.RED));
            return;
        }
        if (m == Material.POTION || m == Material.SPLASH_POTION
                || m == Material.LINGERING_POTION) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,
                    400, 1, true, false, false));
            player.sendMessage(Component.text(
                    "Ваша вера запрещает вам пить зелья!", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onIslamEliteDeath(PlayerDeathEvent e) {
        Player player = e.getEntity();
        TownInstituteData data = dataOf(player);
        if (data == null || data.religion() != Religion.ISLAM) return;
        if (!LuckPermsUtil.hasGroupOrHigher(player,
                plugin.institutesConfig().eliteGroupWarden())) return;

        Location loc = player.getLocation().clone();
        if (loc.getWorld() == null) return;

        TNTPrimed tnt = loc.getWorld().spawn(loc, TNTPrimed.class);
        tnt.setFuseTicks(40);
        tnt.setMetadata(TNT_META, new FixedMetadataValue(plugin, true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTntExplode(EntityExplodeEvent e) {
        if (!(e.getEntity() instanceof TNTPrimed tnt)) return;
        if (!tnt.hasMetadata(TNT_META)) return;
        e.blockList().clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemExplosionDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Item)) return;
        if (!(e.getDamager() instanceof TNTPrimed tnt)) return;
        if (!tnt.hasMetadata(TNT_META)) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslamGladiatorKill(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;

        TownInstituteData data = dataOf(killer);
        if (data == null || data.religion() != Religion.ISLAM) return;
        if (!LuckPermsUtil.hasGroupOrHigher(killer,
                plugin.institutesConfig().eliteGroupGladiator())) return;

        killer.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,
                300, 0, true, false, false));
        killer.sendMessage(Component.text(
                "Благословение Аллаха: Регенерация I!", NamedTextColor.GREEN));
    }

    // ============================================================
    //   ИНДУИЗМ
    // ============================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHinduConsume(PlayerItemConsumeEvent e) {
        TownInstituteData data = dataOf(e.getPlayer());
        if (data == null || data.religion() != Religion.HINDUISM) return;

        Material m = e.getItem().getType();
        if (m == Material.BEEF || m == Material.COOKED_BEEF) {
            e.getPlayer().addPotionEffect(new PotionEffect(PotionEffectType.HUNGER,
                    100, 2, true, false, false));
            e.getPlayer().sendMessage(Component.text(
                    "Корова — священное животное! Вы осквернили себя.", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHinduKill(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;

        TownInstituteData data = dataOf(killer);
        if (data == null || data.religion() != Religion.HINDUISM) return;
        if (LuckPermsUtil.hasGroupOrHigher(killer,
                plugin.institutesConfig().eliteGroupWarden())) return;

        killer.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,
                100, 0, true, false, false));
        killer.sendMessage(Component.text(
                "Карма: вы чувствуете иссушение.", NamedTextColor.RED));
    }

    // ============================================================
    //   КОНФУЦИАНСТВО
    // ============================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConfucianExp(PlayerExpChangeEvent e) {
        TownInstituteData data = dataOf(e.getPlayer());
        if (data == null || data.religion() != Religion.CONFUCIANISM) return;

        int amount = e.getAmount();
        if (amount <= 0) return;
        e.setAmount((int) Math.ceil(amount * 1.25));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConfucianDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (!(e.getEntity() instanceof Player)) return;

        TownInstituteData data = dataOf(attacker);
        if (data == null || data.religion() != Religion.CONFUCIANISM) return;

        e.setDamage(e.getDamage() * plugin.institutesConfig().confucianDamageReduction());
    }

    // ============================================================
    //   ХЕЛПЕРЫ
    // ============================================================

    /**
     * Возвращает данные институтов игрока. null, если:
     *  - institutesService ещё не инициализирован (БД грузится / недоступна);
     *  - towny ещё не инициализирован;
     *  - игрок не в городе;
     *  - данные города ещё не прогреты в кэше.
     *
     * Такая грациозная деградация безопасна: без данных событие просто
     * не обрабатывается.
     */
    private @Nullable TownInstituteData dataOf(@NotNull Player player) {
        if (plugin.institutesService() == null) return null;
        if (plugin.towny() == null) return null;
        Town town = plugin.towny().getTownOf(player);
        if (town == null) return null;
        return plugin.institutesService().getCached(town.getUUID());
    }
}