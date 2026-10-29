package net.exylia.lib.camera;

import net.exylia.lib.camera.internal.CameraRuntime;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One plugin's view of the camera module.
 *
 * <p>Obtained from {@link Cameras#of(Plugin)} and held for as long as the
 * plugin is loaded.
 *
 * @since 1.172.0
 */
public final class PluginCameras {

    private final Plugin plugin;

    PluginCameras(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Shows a player themselves, from outside.
     *
     * <p>The shot is worked out around where they are standing and which way
     * they are facing at this moment, so the same file reads the same whichever
     * direction they happen to be pointing.
     *
     * @param player who is filmed, and who watches
     * @param shot   the choreography
     * @return the handle, or {@code null} when there is nothing to play
     */
    public @Nullable CameraHandle play(@NotNull Player player, @NotNull CameraShot shot) {
        return CameraRuntime.play(plugin, shot, player.getLocation(), List.of(player));
    }

    /**
     * Shows several players one shot of one spot.
     *
     * <p>The camera is one entity and everybody looks through the same one, so
     * a celebration filmed for four people costs what it costs for one. What
     * that also means is that everybody sees the identical framing: two players
     * who should each see themselves from over their own shoulder are two calls,
     * not one.
     *
     * @param players who watches
     * @param shot    the choreography
     * @param subject where it films, and which way that faces
     * @return the handle, or {@code null} when there is nothing to play
     */
    public @Nullable CameraHandle play(@NotNull List<Player> players, @NotNull CameraShot shot,
                                       @NotNull Location subject) {
        return CameraRuntime.play(plugin, shot, subject, players);
    }

    /**
     * Plays a shot through fixed places to one player.
     *
     * <p>The player stays frozen where they stand, drawn as a spectator, while
     * their view travels the keys; they get it back when the shot ends, is
     * stopped, or they leave, die or change world. Nothing happens for a player
     * in another world than the shot.
     *
     * @param player who watches
     * @param shot   the keys, subtitles, bars and fade
     * @return the handle, or {@code null} when there is nothing to play
     * @since 1.391.0
     */
    public @Nullable CameraHandle play(@NotNull Player player, @NotNull WorldShot shot) {
        return CameraRuntime.play(plugin, shot, List.of(player));
    }

    /**
     * Plays a shot through fixed places to several players at once.
     *
     * <p>One camera entity and one set of panels for all of them: a cinematic
     * at the start of an event costs what it costs for one viewer.
     *
     * @param players who watches; those in another world are left out
     * @param shot    the keys, subtitles, bars and fade
     * @return the handle, or {@code null} when there is nothing to play
     * @since 1.391.0
     */
    public @Nullable CameraHandle play(@NotNull List<Player> players, @NotNull WorldShot shot) {
        return CameraRuntime.play(plugin, shot, players);
    }

    /**
     * Ends whatever this player is looking through, whoever started it.
     *
     * <p>Not only this plugin's: a player is looking through one camera at a
     * time, and the thing a caller has is a player who needs their eyes back.
     *
     * @param player whose view to give back
     */
    public void stop(@NotNull Player player) {
        CameraRuntime.stop(player);
    }

    /**
     * Whether this player is looking through a camera right now.
     *
     * @param player the player
     * @return {@code true} while a shot is playing for them
     */
    public boolean isFilming(@NotNull Player player) {
        return CameraRuntime.isFilming(player);
    }

    /**
     * Clears parts of a player's HUD, for them alone, until {@link #showHud}:
     * boss bars and the sidebar come back exactly as they are by then, chat
     * that arrived meanwhile is delivered in order, action bars and titles
     * from anything else are dropped. Packets drawn by a shot (subtitles,
     * title cards) and sounds are untouched. A second call adds to what is
     * hidden; leaving the server forgets it all. Nothing without PacketEvents.
     *
     * @param player whose HUD
     * @param parts  what to hide
     * @since 1.397.0
     */
    public void hideHud(@NotNull Player player, @NotNull java.util.Set<Hud> parts) {
        CameraRuntime.hideHud(player, parts);
    }

    /**
     * Gives back whatever {@link #hideHud} took. Safe to call when nothing is
     * hidden.
     *
     * @param player whose HUD
     * @since 1.397.0
     */
    public void showHud(@NotNull Player player) {
        CameraRuntime.showHud(player);
    }

    /**
     * Whether a player's HUD is hidden right now.
     *
     * @param player the player
     * @return {@code true} between {@link #hideHud} and {@link #showHud}
     * @since 1.397.0
     */
    public boolean isHudHidden(@NotNull Player player) {
        return CameraRuntime.isHudHidden(player);
    }

    /**
     * Sends a chat line that belongs to what the player is watching, past a
     * hidden chat (a "click to skip" line); plain chat otherwise.
     *
     * @param player who reads it
     * @param line   the line
     * @since 1.397.0
     */
    public void say(@NotNull Player player, @NotNull net.kyori.adventure.text.Component line) {
        CameraRuntime.say(player, line);
    }

    /** Whether a camera can be shown at all on this server. */
    public boolean isSupported() {
        return CameraRuntime.isSupported();
    }
}
