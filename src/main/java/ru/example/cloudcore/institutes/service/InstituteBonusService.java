package ru.example.cloudcore.institutes.service;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.CloudCorePlugin;
import ru.example.cloudcore.institutes.data.*;
import ru.example.cloudcore.institutes.util.LuckPermsUtil;
import ru.example.cloudcore.institutes.util.VaultEconomyHolder;
import ru.example.cloudcore.service.TownyIntegrationService;

import java.util.UUID;

/**
 * Тикеры бонусов институтов: эффекты, сердца, экономика форм.
 *
 * Все тикеры — main-thread (Bukkit.getScheduler().runTaskTimer).
 * Towny / Attribute / PotionEffect — не thread-safe, async недопустим.
 *
 * ★ 5a: refreshHealth() больше НЕ считает hearts самостоятельно — читает
 *        единое значение из BonusService (buildings + wonders + institutes).
 *        Institutes-вклад в BonusService добавляет InstitutesService.contributeHealthBonus().
 */
public final class InstituteBonusService {

    private static final long TICKS_PER_HOUR = 20L * 3600L;

    private final CloudCorePlugin plugin;
    private final TownyIntegrationService towny;
    private final InstitutesService institutes;
    private final InstitutesPlaytimeService playtime;

    private final NamespacedKey healthKey;

    private int fastTaskId         = -1;
    private int formEconomyTaskId  = -1;
    private int hinduTaskId        = -1;
    private int buddhismTaskId     = -1;
    private int economicPenalties  = -1;

    public InstituteBonusService(@NotNull CloudCorePlugin plugin,
                                 @NotNull TownyIntegrationService towny,
                                 @NotNull InstitutesService institutes,
                                 @NotNull InstitutesPlaytimeService playtime) {
        this.plugin = plugin;
        this.towny = towny;
        this.institutes = institutes;
        this.playtime = playtime;
        this.healthKey = new NamespacedKey(plugin, "institutes_health_bonus");
    }

    public void start() {
        long fast = 20L * plugin.institutesConfig().fastTickSeconds();
        fastTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tickFast, fast, fast).getTaskId();

        long form = 20L * plugin.institutesConfig().formCheckIntervalSeconds();
        formEconomyTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tickFormEconomy, form, form).getTaskId();

        long hin = 20L * plugin.institutesConfig().hinduTickSeconds();
        hinduTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tickHindu, hin, hin).getTaskId();

        long bud = 20L * plugin.institutesConfig().buddhismTickSeconds();
        buddhismTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tickBuddhism, bud, bud).getTaskId();

        economicPenalties = Bukkit.getScheduler()
                .runTaskTimer(plugin, this::tickEconomicPenalties, TICKS_PER_HOUR, TICKS_PER_HOUR).getTaskId();
    }

    public void stop() {
        if (fastTaskId != -1)        Bukkit.getScheduler().cancelTask(fastTaskId);
        if (formEconomyTaskId != -1) Bukkit.getScheduler().cancelTask(formEconomyTaskId);
        if (hinduTaskId != -1)       Bukkit.getScheduler().cancelTask(hinduTaskId);
        if (buddhismTaskId != -1)    Bukkit.getScheduler().cancelTask(buddhismTaskId);
        if (economicPenalties != -1) Bukkit.getScheduler().cancelTask(economicPenalties);
        fastTaskId = formEconomyTaskId = hinduTaskId = buddhismTaskId = economicPenalties = -1;
    }

    // ============================================================
    //   FAST TICK — эффекты религий (короткий интервал)
    // ============================================================

    private void tickFast() {
        int glowTicks = 20 * plugin.institutesConfig().glowDurationSeconds() + 40;

        for (Player p : Bukkit.getOnlinePlayers()) {
            Town town = towny.getTownOf(p);
            if (town == null) { refreshHealth(p, null, null); continue; }

            TownInstituteData data = institutes.getCached(town.getUUID());
            if (data == null) { refreshHealth(p, null, null); continue; }

            Religion rel = data.religion();
            if (rel == Religion.ORTHODOX) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,
                        glowTicks, 0, true, false, false));
            }
            if (rel == Religion.PROTESTANT) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                        glowTicks, 1, true, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,
                        glowTicks, 1, true, false, false));
            }
            if (rel == Religion.CONFUCIANISM) applyConfucian(p);

            refreshHealth(p, town, data);
        }
    }

    /**
     * Конфуцианство: KNIGHT+ и уровень опыта >= confucian-resistance-required-level
     * получают постоянное Сопротивление. Всем остальным — снимаем.
     */
    private void applyConfucian(Player p) {
        boolean hasKnight = LuckPermsUtil.hasGroupOrHigher(p,
                plugin.institutesConfig().eliteGroupKnight());
        boolean shouldHave = hasKnight
                && p.getLevel() >= plugin.institutesConfig().confucianResistanceRequiredLevel();
        if (!shouldHave) {
            p.removePotionEffect(PotionEffectType.RESISTANCE);
            return;
        }
        int level = plugin.institutesConfig().confucianResistanceLevel();
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,
                120, level, true, false, false));
    }

    // ============================================================
    //   FORM ECONOMY — раз в минуту
    // ============================================================

    private void tickFormEconomy() {
        VaultEconomyHolder eco = plugin.vaultEconomy();
        if (eco == null) return;

        int interval = plugin.institutesConfig().formCheckIntervalSeconds();
        OfflinePlayer server = Bukkit.getOfflinePlayer("towny-server");

        // --- Per-player: monarchy / dictatorship / oligarchy ---
        for (Player p : Bukkit.getOnlinePlayers()) {
            Town town = towny.getTownOf(p);
            if (town == null) continue;
            TownInstituteData data = institutes.getCached(town.getUUID());
            if (data == null || data.form() == null) continue;

            GovernmentForm form = data.form();
            if (form != GovernmentForm.MONARCHY
                    && form != GovernmentForm.DICTATORSHIP
                    && form != GovernmentForm.OLIGARCHY) continue;

            long total = playtime.addSeconds(p.getUniqueId(), interval, p.getName());
            if (total < 3600) continue;
            long hours = total / 3600;
            playtime.setSeconds(p.getUniqueId(), total % 3600);

            boolean mayor = isMayor(p, town);
            boolean assistant = isAssistant(p, town);

            switch (form) {
                case MONARCHY -> {
                    if (mayor) {
                        depositFromServer(eco, server, p,
                                plugin.institutesConfig().monarchyCoinsPerHour() * hours);
                    }
                    // Каждый горожанин (включая мэра) платит налог
                    withdrawToServer(eco, server, p,
                            plugin.institutesConfig().monarchyCitizenTax() * hours);
                }
                case DICTATORSHIP -> {
                    if (mayor) {
                        depositFromServer(eco, server, p,
                                plugin.institutesConfig().dictatorshipCoinsPerHour() * hours);
                    }
                }
                case OLIGARCHY -> {
                    if (mayor || assistant) {
                        depositFromServer(eco, server, p,
                                plugin.institutesConfig().oligarchyCoinsPerHour() * hours);
                    }
                }
                default -> {}
            }
        }

        // --- Per-town: theocracy ---
        double perMinute = plugin.institutesConfig().theocracyTownIncomePerHour() / 60.0;
        if (perMinute > 0) {
            for (Town town : TownyAPI.getInstance().getTowns()) {
                TownInstituteData data = institutes.getCached(town.getUUID());
                if (data == null || data.form() != GovernmentForm.THEOCRACY) continue;
                if (!hasAnyOnlineCitizen(town)) continue;
                try {
                    if (eco.economy().has(server, perMinute)) {
                        eco.economy().withdrawPlayer(server, perMinute);
                        town.getAccount().deposit(perMinute, "Теократический доход");
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    private void depositFromServer(VaultEconomyHolder eco, OfflinePlayer server,
                                   Player target, double amount) {
        if (amount <= 0) return;
        try {
            if (eco.economy().has(server, amount)) {
                eco.economy().withdrawPlayer(server, amount);
                eco.economy().depositPlayer(target, amount);
            }
        } catch (Throwable ignored) {}
    }

    private void withdrawToServer(VaultEconomyHolder eco, OfflinePlayer server,
                                  Player source, double amount) {
        if (amount <= 0) return;
        try {
            if (eco.economy().has(source, amount)) {
                eco.economy().withdrawPlayer(source, amount);
                eco.economy().depositPlayer(server, amount);
            }
        } catch (Throwable ignored) {}
    }

    private boolean hasAnyOnlineCitizen(Town town) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Resident res = TownyAPI.getInstance().getResident(p.getUniqueId());
            if (res != null && res.hasTown() && town.equals(res.getTownOrNull())) return true;
        }
        return false;
    }

    // ============================================================
    //   HINDU / BUDDHISM — раз в минуту
    // ============================================================

    private void tickHindu() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Town town = towny.getTownOf(p);
            if (town == null) continue;
            TownInstituteData data = institutes.getCached(town.getUUID());
            if (data == null || data.religion() != Religion.HINDUISM) continue;
            p.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION,
                    20, 0, true, false, false));
        }
    }

    private void tickBuddhism() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Town town = towny.getTownOf(p);
            if (town == null) continue;
            TownInstituteData data = institutes.getCached(town.getUUID());
            if (data == null || data.religion() != Religion.BUDDHISM) continue;
            int level = LuckPermsUtil.hasGroupOrHigher(p,
                    plugin.institutesConfig().eliteGroupWarden()) ? 1 : 0;
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,
                    80, level, true, false, false));
        }
    }

    // ============================================================
    //   ECONOMIC PENALTIES — раз в час
    // ============================================================

    private void tickEconomicPenalties() {
        VaultEconomyHolder eco = plugin.vaultEconomy();
        if (eco == null) return;

        for (Player p : Bukkit.getOnlinePlayers()) {
            Town town = towny.getTownOf(p);
            if (town == null) continue;
            TownInstituteData data = institutes.getCached(town.getUUID());
            if (data == null || data.religion() != Religion.CATHOLIC) continue;
            try {
                if (eco.economy().has(p, 2.0)) {
                    eco.economy().withdrawPlayer(p, 2.0);
                    eco.economy().depositPlayer(Bukkit.getOfflinePlayer("towny-server"), 2.0);
                } else {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,
                            20 * 60, 0, true, false, false));
                }
            } catch (Throwable ignored) {}
        }

        for (Town town : TownyAPI.getInstance().getTowns()) {
            TownInstituteData data = institutes.getCached(town.getUUID());
            if (data == null || data.religion() != Religion.PROTESTANT) continue;
            if (town.getAccount().getHoldingBalance() < 1.0) continue;
            try {
                town.getAccount().withdraw(1.0, "Протестантский налог");
                eco.economy().depositPlayer(Bukkit.getOfflinePlayer("towny-server"), 1.0);
            } catch (Throwable ignored) {}
        }
    }

    // ============================================================
    //   HEALTH — единая точка через BonusService
    // ============================================================

    public void refresh(Player p) {
        Town town = towny.getTownOf(p);
        TownInstituteData data = town != null ? institutes.getCached(town.getUUID()) : null;
        refreshHealth(p, town, data);
    }

    /**
     * ★ 5a — hearts теперь считаются в единой точке (BonusService):
     *         buildings + wonders + institutes.
     *
     * Здесь только применяем/снимаем attribute modifier. Параметры town/data
     * нужны только для проверки «игрок на своей территории» и наличия institutes-
     * записи (сохраняем прежний инвариант: без institutes-данных бонус не даётся,
     * иначе BonusService-кэш ещё холодный и значение может быть неконсистентным).
     *
     * Примечание: player.getAttribute(MAX_HEALTH) не сохраняет modifier'ы между
     * сессиями — при join modifier добавляется заново, при quit пропадает сам.
     * Так что дюпов между сессиями нет. В рамках одной сессии modifier
     * заменяется по одному и тому же healthKey — тоже дюпов нет.
     */
    private void refreshHealth(Player p, Town town, TownInstituteData data) {
        double hearts = 0.0;

        if (town != null && data != null && isInOwnTown(p, town)) {
            if (plugin.bonusService() != null) {
                hearts = plugin.bonusService().getRewards(town.getUUID()).healthBonusHearts();
            }
        }

        AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;
        AttributeModifier existing = attr.getModifier(healthKey);

        if (hearts == 0) {
            if (existing != null) attr.removeModifier(healthKey);
            return;
        }
        double amount = hearts * 2.0;
        if (existing == null) {
            attr.addModifier(new AttributeModifier(healthKey, amount,
                    AttributeModifier.Operation.ADD_NUMBER));
        } else if (Math.abs(existing.getAmount() - amount) > 0.0001) {
            attr.removeModifier(healthKey);
            attr.addModifier(new AttributeModifier(healthKey, amount,
                    AttributeModifier.Operation.ADD_NUMBER));
        }
    }

    public void removeHealthBonus(Player p) {
        AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) attr.removeModifier(healthKey);
    }

    // ============================================================
    //   Утилиты
    // ============================================================

    private boolean isMayor(Player p, Town town) {
        Resident res = TownyAPI.getInstance().getResident(p.getUniqueId());
        return res != null && town.isMayor(res);
    }

    private boolean isAssistant(Player p, Town town) {
        if (town == null) return false;
        Resident res = TownyAPI.getInstance().getResident(p.getUniqueId());
        if (res == null) return false;
        try {
            return com.palmergames.bukkit.towny.permissions.TownyPerms
                    .hasAssistantTownRank(res);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isInOwnTown(Player p, Town town) {
        var block = TownyAPI.getInstance().getTownBlock(p.getLocation());
        if (block == null || !block.hasTown()) return false;
        return block.getTownOrNull().equals(town);
    }
}