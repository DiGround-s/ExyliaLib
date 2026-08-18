package net.exylia.lib.input;

import net.exylia.lib.config.Comment;

/**
 * How the library asks players for things.
 *
 * <p>Generated as {@code plugins/ExyliaLib/input.yml} on first start. One file
 * for every Exylia plugin, so a server owner changes the cancel word once
 * rather than in each plugin that happens to ask a question.
 *
 * <h2>What is not here</h2>
 * The Bedrock username prefix is deliberately <em>not</em> an input setting. It
 * says which players are on Bedrock, which is a fact about the server's players
 * rather than about asking them questions — a scoreboard, a tablist or a name
 * formatter needs the same answer. It lives in {@code config.yml} as
 * {@code bedrock-prefix}, where anything can read it.
 *
 * @param timeoutSeconds how long a request waits before giving up
 * @param cancelWord     what a player types to stop being asked
 * @param preferDialogs  whether to use the client's own dialog windows
 * @since 1.31.0
 */
@Comment("How Exylia plugins ask you for things: a name, a number, a choice.")
@Comment("")
@Comment("Every plugin uses this file, so a change here applies everywhere.")
@Comment("")
@Comment("Run /exylialib reload after editing. No restart is needed.")
public record InputSettings(

        @Comment("How long a question waits for an answer, in seconds.")
        @Comment("A player who walks away should not stay stuck in a")
        @Comment("half-finished form forever, and the plugin that asked")
        @Comment("should not wait for an answer that is never coming.")
        @Comment("Set it high enough for somebody to read and think.")
        int timeoutSeconds,

        @Comment("What a player types in chat to stop being asked.")
        @Comment("Only used when the question was asked in chat: a dialog,")
        @Comment("a form and a menu all have their own cancel button.")
        String cancelWord,

        @Comment("Whether to use the client's own dialog windows when it")
        @Comment("supports them (Minecraft 1.21.6 and above).")
        @Comment("These are proper windows with real text boxes, and they")
        @Comment("can ask several things at once instead of one question")
        @Comment("per chat line. Turn it off to send everything to chat and")
        @Comment("menus, which is how it looked before dialogs existed.")
        boolean preferDialogs
) {

    /** The Exylia defaults: a minute to answer, {@code cancel} to stop, dialogs on. */
    public InputSettings() {
        this(60, "cancel", true);
    }
}
