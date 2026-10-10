package ru.example.townbuildings.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;

import java.time.Duration;

/** Централизованные звуки и титулы. Звуки резолвятся из messages.yml. */
public final class SoundService {

    private static final Title.Times TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(200),
            Duration.ofMillis(1500),
            Duration.ofMillis(400)
    );

    private final TownBuildingsPlugin plugin;

    public SoundService(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
    }

    public void playUpgradeSuccess(@NotNull Player player) {
        play(player, "sound.upgrade-success", Sound.ENTITY_PLAYER_LEVELUP, 1.0F, 1.2F);
    }

    public void playError(@NotNull Player player) {
        play(player, "sound.upgrade-fail", Sound.BLOCK_NOTE_BLOCK_BASS, 0.7F, 0.8F);
    }

    public void playMenuOpen(@NotNull Player player) {
        play(player, "sound.menu-open", Sound.BLOCK_CHEST_OPEN, 0.8F, 1.0F);
    }

    public void playMenuClose(@NotNull Player player) {
        play(player, "sound.menu-close", Sound.BLOCK_CHEST_CLOSE, 0.8F, 1.0F);
    }

    public void playInventoryLocked(@NotNull Player player) {
        play(player, "sound.inventory-locked", Sound.BLOCK_NOTE_BLOCK_BASS, 0.7F, 1.5F);
    }

    public void showTitle(@NotNull Player player,
                          @NotNull Component title,
                          @NotNull Component subtitle) {
        player.showTitle(Title.title(title, subtitle, TITLE_TIMES));
    }

    public void showUpgradeTitle(@NotNull Player player,
                                 @NotNull Component title,
                                 @NotNull Component subtitle) {
        showTitle(player, title, subtitle);
    }

    private void play(@NotNull Player player,
                      @NotNull String configKey,
                      @NotNull Sound fallback,
                      float volume, float pitch) {
        Sound sound = plugin.getMessagesConfig().getSound(configKey);
        player.playSound(player.getLocation(), sound != null ? sound : fallback, volume, pitch);
    }
}