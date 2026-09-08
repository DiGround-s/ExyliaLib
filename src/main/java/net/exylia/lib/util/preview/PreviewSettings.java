package net.exylia.lib.util.preview;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Key;
import net.exylia.lib.util.teleport.ExyliaLocation;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Where and how a preview stage is built.
 *
 * <p>Nests inside a plugin's own configuration record like any other section,
 * so a server owner can move the stage without a code change:
 *
 * <pre>{@code
 * public record MySettings(PreviewSettings preview) {
 *     public MySettings() {
 *         this(new PreviewSettings());
 *     }
 * }
 * }</pre>
 *
 * <h2>Why the location has no default</h2>
 * A preview is shown wherever the server owner built the room for it. The
 * library cannot guess that, and guessing wrong puts the player inside terrain
 * or in an unbuilt corner of the map. So an unset {@link #location()} means the
 * plugin has no stage yet and must not preview at all; each plugin gives its
 * admins a {@code setpreviewlocation} command to fill it in.
 *
 * @param location     where the player stands during a preview, in the stored
 *                     {@link ExyliaLocation} form, or empty for none
 * @param distance     how far in front of the player the effect plays
 * @param settleTicks  how long to wait after the teleport before playing
 * @param lingerTicks  how long to keep the stage after the effect ends
 * @param maxTicks     the longest a preview may last, whatever happens
 *
 * @since 1.30.0
 */
@Comment("Where preview effects are shown.")
@Comment("")
@Comment("A preview moves the player to the stage below, plays the effect in")
@Comment("front of them where only they can see it, then puts them back exactly")
@Comment("where they were. Until a stage is set, previews are unavailable.")
public record PreviewSettings(

        @Comment("Where the player stands while a preview plays, as")
        @Comment("server,world,x,y,z,yaw,pitch. Leave empty and set it in game")
        @Comment("with this plugin's setpreviewlocation admin command.")
        @Comment("Only the position is used: a preview always faces north, so")
        @Comment("every effect is seen from the side it was drawn for. Build")
        @Comment("the room around that, and leave the north side open.")
        @Comment("Two players previewing at once may share it: everyone is")
        @Comment("hidden from everyone, and each effect is sent to one player.")
        String location,

        @Comment("How far in front of the player the effect plays, in blocks.")
        double distance,

        @Key("settle-ticks")
        @Comment("How long to wait after the teleport before playing, so the")
        @Comment("client has the new position before the first particle.")
        int settleTicks,

        @Key("linger-ticks")
        @Comment("How long the stage is held after the effect finishes.")
        int lingerTicks,

        @Key("max-ticks")
        @Comment("The longest a preview may last. A safety net: whatever goes")
        @Comment("wrong, the player is returned after this.")
        int maxTicks
) {

    /** Safe defaults with no stage, so a fresh install previews nothing. */
    public PreviewSettings() {
        this("", 5.0, 4, 20, 20 * 30);
    }

    public PreviewSettings {
        location = location == null ? "" : location.trim();
        distance = Math.max(1.0, distance);
        settleTicks = Math.max(1, settleTicks);
        lingerTicks = Math.max(0, lingerTicks);
        // The safety net cannot be shorter than the wait plus the linger, or it
        // would fire before the effect it is meant to outlast.
        maxTicks = Math.max(settleTicks + lingerTicks + 20, maxTicks);
    }

    /** The same settings with a different stage, for a command that moves it. */
    public @NotNull PreviewSettings at(@NotNull Location stage) {
        return new PreviewSettings(ExyliaLocation.of(stage).toString(),
                distance, settleTicks, lingerTicks, maxTicks);
    }

    /**
     * The stage as a live location.
     *
     * @return where the player is put, or {@code null} when none is configured,
     *         the stored text is not a location, or its world is not loaded
     */
    public @Nullable Location stage() {
        if (location.isBlank()) {
            return null;
        }
        try {
            return ExyliaLocation.fromString(location).toBukkitLocation();
        } catch (IllegalArgumentException notALocation) {
            return null;
        }
    }
}
