package ru.example.cloudcore.institutes.util;

import org.bukkit.entity.Player;

/**
 * Проверка членства в группе LuckPerms (или группе с весом >= данной).
 *
 * Если LuckPerms нет — все проверки false, элитные бонусы не применяются.
 */
public final class LuckPermsUtil {

    private static final boolean AVAILABLE;

    static {
        boolean avail;
        try {
            Class.forName("net.luckperms.api.LuckPermsProvider");
            avail = true;
        } catch (Throwable t) {
            avail = false;
        }
        AVAILABLE = avail;
    }

    private LuckPermsUtil() {}

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static boolean hasGroupOrHigher(Player player, String minGroupName) {
        if (!AVAILABLE || minGroupName == null || minGroupName.isBlank()) return false;
        try {
            return LuckPermsBridge.hasGroupOrHigher(player, minGroupName);
        } catch (Throwable t) {
            return false;
        }
    }
}