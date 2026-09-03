package net.exylia.lib.display;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Key;

/**
 * The ceiling on what display effects may cost a server.
 *
 * <p>Generated as {@code plugins/ExyliaLib/displays.yml} on first start. One
 * file for every Exylia plugin that draws, because what a client can be sent is
 * a fact about the server rather than about the plugin that happened to send
 * it.
 *
 * <h2>Why the budget counts viewers</h2>
 * A display costs one spawn, one state and a handful of pose packets
 * <em>per player who can see it</em>. Two hundred displays in an empty corner
 * of the map are nothing; the same two hundred with thirty players around them
 * are six thousand entities on clients that also have a fight to render. The
 * budget is therefore displays multiplied by their viewers, which is the number
 * that actually reaches the network.
 *
 * @param maxViewerDisplays how many display-viewer pairs may exist at once
 * @param maxPerEffect      how many displays one shape may put on screen
 * @since 1.90.0
 */
@Comment("What display effects are allowed to cost.")
@Comment("")
@Comment("Kill, hit and arrow effects draw solid blocks and items through")
@Comment("packets. They are free for the server to simulate — nothing is")
@Comment("ticked, nothing is saved — but every one of them is sent to every")
@Comment("player close enough to see it, and that is what these limits are")
@Comment("about.")
@Comment("")
@Comment("Run /exylialib reload after editing. No restart is needed.")
public record DisplaySettings(

        @Key("max-viewer-displays")
        @Comment("The ceiling on display-viewer pairs on the whole server.")
        @Comment("One display seen by thirty players counts as thirty.")
        @Comment("Effects that would go over it are dropped, so a crowded")
        @Comment("arena loses the tail of an effect instead of the tick rate.")
        @Comment("Set it to 0 to remove the ceiling entirely.")
        int maxViewerDisplays,

        @Key("max-per-effect")
        @Comment("The ceiling on how many displays a single shape may draw.")
        @Comment("A ring written with points:500 is a mistake in a file, not")
        @Comment("a design; this catches it before it reaches a client.")
        int maxPerEffect
) {

    /**
     * The Exylia defaults.
     *
     * <p>Twenty thousand pairs is roughly six large kill effects playing at
     * once in front of thirty players, or a great many more in front of two.
     * A hundred and twenty-eight per shape is above every effect the ecosystem
     * ships and below anything that could be meant seriously.
     */
    public DisplaySettings() {
        this(20_000, 128);
    }
}
