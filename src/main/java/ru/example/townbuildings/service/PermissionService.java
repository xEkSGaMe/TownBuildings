package ru.example.townbuildings.service;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.Context;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Выдача/снятие пермишенов через LuckPerms.
 *
 * 11-B3 — гранулярность через LP-контекст tb-source=townbuildings:
 * при revoke снимаем ТОЛЬКО наши ноды, чужие гранты не трогаем.
 * 18.2  — +university-пермишены (essentials.workbench/anvil/enderchest/repair).
 * TW    — Штаб L1 выдаёт мэру townywar.declare (объявление войны).
 * D2    — кэш LuckPerms: LuckPermsProvider.get() резолвится один раз за
 *         весь срок жизни плагина, а не на каждый вызов grant/revoke.
 *         Раньше при активной работе мэров/горожан это дёргало сервис-
 *         локатор десятки раз в секунду.
 */
public final class PermissionService {

    // Mayor
    private static final String PERM_RAID = "towny.command.town.raid";
    // ★ Штаб L1 → право /twar
    private static final String PERM_WAR  = "townywar.declare";

    // University (EssentialsX)
    private static final String PERM_WORKBENCH  = "essentials.workbench";   // /craft
    private static final String PERM_ANVIL      = "essentials.anvil";       // /anvil
    private static final String PERM_ENDERCHEST = "essentials.enderchest";  // /ec
    private static final String PERM_REPAIR     = "essentials.repair";      // /fix

    private static final List<String> UNIVERSITY_PERMS = List.of(
            PERM_WORKBENCH, PERM_ANVIL, PERM_ENDERCHEST, PERM_REPAIR);

    private static final String SRC_KEY = "tb-source";
    private static final String SRC_VAL = "townbuildings";

    private final JavaPlugin plugin;

    /** ★ D2 — кэш LuckPerms: 1 вызов LuckPermsProvider.get() на весь срок жизни плагина. */
    private volatile LuckPerms cachedApi;
    private volatile boolean apiResolved = false;

    public PermissionService(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ============================================================
    //   Mayor
    // ============================================================

    public void grantMayorPermissions(@NotNull UUID mayorUuid,
                                      boolean hasWatchtower, boolean hasHeadquarters) {
        LuckPerms api = getApi();
        if (api == null) return;

        User user = api.getUserManager().getUser(mayorUuid);
        if (user == null) {
            api.getUserManager().loadUser(mayorUuid).thenAccept(u ->
                    applyMayor(u, hasWatchtower, hasHeadquarters, api));
            return;
        }
        applyMayor(user, hasWatchtower, hasHeadquarters, api);
    }

    public void revokeMayorPermissions(@NotNull UUID mayorUuid) {
        LuckPerms api = getApi();
        if (api == null) return;

        User user = api.getUserManager().getUser(mayorUuid);
        if (user == null) {
            api.getUserManager().loadUser(mayorUuid).thenAccept(u -> {
                removeOurNodes(u, List.of(PERM_RAID, PERM_WAR));
                api.getUserManager().saveUser(u);
            });
            return;
        }
        removeOurNodes(user, List.of(PERM_RAID, PERM_WAR));
        api.getUserManager().saveUser(user);
    }

    private void applyMayor(@NotNull User user,
                            boolean watchtower, boolean headquarters,
                            @NotNull LuckPerms api) {
        removeOurNodes(user, List.of(PERM_RAID, PERM_WAR));

        if (watchtower)   user.data().add(node(PERM_RAID));
        if (headquarters) user.data().add(node(PERM_WAR));

        api.getUserManager().saveUser(user);
    }

    // ============================================================
    //   18.2 — University
    // ============================================================

    /**
     * Выдать горожанину пермишены университета по уровню здания.
     * level — уровень university (0..5).
     * Идемпотентно: лишние снимаются, недостающие выдаются.
     */
    public void grantUniversityPermissions(@NotNull UUID playerUuid, int level) {
        LuckPerms api = getApi();
        if (api == null) return;

        User user = api.getUserManager().getUser(playerUuid);
        if (user == null) {
            api.getUserManager().loadUser(playerUuid).thenAccept(u ->
                    applyUniversity(u, level, api));
            return;
        }
        applyUniversity(user, level, api);
    }

    /** Снять все наши пермишены университета (при выходе из города). */
    public void revokeUniversityPermissions(@NotNull UUID playerUuid) {
        LuckPerms api = getApi();
        if (api == null) return;

        User user = api.getUserManager().getUser(playerUuid);
        if (user == null) {
            api.getUserManager().loadUser(playerUuid).thenAccept(u -> {
                removeOurNodes(u, UNIVERSITY_PERMS);
                api.getUserManager().saveUser(u);
            });
            return;
        }
        removeOurNodes(user, UNIVERSITY_PERMS);
        api.getUserManager().saveUser(user);
    }

    private void applyUniversity(@NotNull User user, int level, @NotNull LuckPerms api) {
        removeOurNodes(user, UNIVERSITY_PERMS);

        if (level >= 2) user.data().add(node(PERM_WORKBENCH));
        if (level >= 3) user.data().add(node(PERM_ANVIL));
        if (level >= 4) user.data().add(node(PERM_ENDERCHEST));
        if (level >= 5) user.data().add(node(PERM_REPAIR));

        api.getUserManager().saveUser(user);
    }

    // ============================================================
    //   Helpers
    // ============================================================

    private static @NotNull Node node(@NotNull String key) {
        return Node.builder(key).withContext(SRC_KEY, SRC_VAL).build();
    }

    private static void removeOurNodes(@NotNull User user, @NotNull List<String> keys) {
        List<Node> toRemove = new ArrayList<>();
        for (Node n : user.data().toCollection()) {
            if (!keys.contains(n.getKey())) continue;
            boolean ours = false;
            for (Context c : n.getContexts().toSet()) {
                if (SRC_KEY.equals(c.getKey()) && SRC_VAL.equals(c.getValue())) {
                    ours = true;
                    break;
                }
            }
            if (ours) toRemove.add(n);
        }
        for (Node n : toRemove) user.data().remove(n);
    }

    /**
     * ★ D2 — кэш: LuckPermsProvider.get() вызывается один раз. Повторные
     * обращения (grant/revoke на каждое событие мэра / sync университета)
     * читают volatile-поле.
     */
    private @Nullable LuckPerms getApi() {
        if (apiResolved) return cachedApi;
        synchronized (this) {
            if (apiResolved) return cachedApi;
            try {
                cachedApi = LuckPermsProvider.get();
            } catch (IllegalStateException e) {
                plugin.getLogger().warning("[Perms] LuckPerms не найден.");
                cachedApi = null;
            }
            apiResolved = true;
            return cachedApi;
        }
    }

    /**
     * Сбросить кэш — вызывать при выключении плагина, если планируется
     * /reload (на случай, если LuckPerms перезагружался).
     */
    public void resetApiCache() {
        synchronized (this) {
            this.cachedApi = null;
            this.apiResolved = false;
        }
    }
}