package net.exylia.lib.util.preview;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Key;

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
 * @param height       how far above the world the stage sits
 * @param separation   how far apart two simultaneous stages are placed
 * @param distance     how far in front of the player the effect plays
 * @param settleTicks  how long to wait after the teleport before playing
 * @param lingerTicks  how long to keep the stage after the effect ends
 * @param maxTicks     the longest a preview may last, whatever happens
 *
 * @since 1.31.0
 */
@Comment("Where preview effects are shown.")
@Comment("")
@Comment("A preview lifts the player to an empty part of the sky so the effect")
@Comment("is seen against nothing, then puts them back exactly where they were.")
public record PreviewSettings(

        @Comment("How far above the world the stage sits. Must be well clear of")
        @Comment("anything built: at this height the player sees only sky.")
        int height,

        @Comment("How far apart two players previewing at the same time are put,")
        @Comment("so neither sees the other's effect.")
        int separation,

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

    /** Safe defaults, used when a plugin declares no section of its own. */
    public PreviewSettings() {
        this(1000, 64, 5.0, 4, 20, 20 * 30);
    }

    public PreviewSettings {
        // A stage below the world would put the player inside terrain, and a
        // separation of zero would let two previews share one patch of sky.
        height = Math.max(320, height);
        separation = Math.max(16, separation);
        distance = Math.max(1.0, distance);
        settleTicks = Math.max(1, settleTicks);
        lingerTicks = Math.max(0, lingerTicks);
        // The safety net cannot be shorter than the wait plus the linger, or it
        // would fire before the effect it is meant to outlast.
        maxTicks = Math.max(settleTicks + lingerTicks + 20, maxTicks);
    }
}
