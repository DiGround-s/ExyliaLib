package net.exylia.lib.packet;

import org.jetbrains.annotations.NotNull;

/**
 * One plugin's packet helpers.
 *
 * <p>Each helper remembers what it did on behalf of this plugin, so disabling
 * the plugin puts every player back the way they were.
 *
 * @since 1.75.0
 */
public interface PluginPackets {

    /** Hiding players from some viewers. */
    @NotNull Visibility visibility();

    /** Blocks one player sees and the server does not have. */
    @NotNull FakeBlocks fakeBlocks();

    /** Blocks outlined for one player, seen through everything in the way. */
    @NotNull GlowingBlocks glowingBlocks();

    /** Pinning a player in place. */
    @NotNull Movement movement();

    /** Making one client believe it is a spectator. */
    @NotNull FakeGameMode fakeGameMode();

    /** Watching a container without opening it. */
    @NotNull SilentContainer silentContainer();
}
