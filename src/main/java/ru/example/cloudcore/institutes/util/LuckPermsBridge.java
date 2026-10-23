package ru.example.cloudcore.institutes.util;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.entity.Player;

/** Загружается только если LuckPerms присутствует (см. LuckPermsUtil.AVAILABLE). */
final class LuckPermsBridge {

    private LuckPermsBridge() {}

    static boolean hasGroupOrHigher(Player player, String minGroupName) {
        LuckPerms lp = LuckPermsProvider.get();

        Group minGroup = lp.getGroupManager().getGroup(minGroupName.toLowerCase());
        int minWeight = minGroup != null ? minGroup.getWeight().orElse(0) : 0;

        User user = lp.getUserManager().getUser(player.getUniqueId());
        if (user == null) return false;

        return user.getInheritedGroups(QueryOptions.defaultContextualOptions()).stream()
                .anyMatch(g -> g.getWeight().orElse(0) >= minWeight);
    }
}