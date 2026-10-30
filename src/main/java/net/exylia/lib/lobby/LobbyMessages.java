package net.exylia.lib.lobby;

import net.exylia.lib.config.Comment;

import java.util.List;

/**
 * Everything a {@link Lobby} tells players, as lines a plugin owns.
 *
 * <p>The library writes none of it to a file: a plugin embeds this record in its own messages
 * record, so its translations and its server owner's edits live next to the rest of its text, and
 * passes it to {@link Lobby#of}. Lines go through the plugin's {@code %prefix%}; a blank line sends
 * nothing. Every line can use the table's values ({@link LobbyHooks#values}) and the lobby's own:
 * {@code %seated%}, {@code %seats%}, {@code %min%}, {@code %missing%}, {@code %command%}, and
 * {@code %player%} where a line is about one.
 *
 * <pre>{@code
 * public record MyMessages(String prefix, @Key("tables") LobbyMessages tables) {
 *     public MyMessages() {
 *         this("{primary}&lCHECKERS {letters_black}•<reset>", new LobbyMessages());
 *     }
 * }
 * }</pre>
 *
 * @since 1.356.0
 */
public record LobbyMessages(
        @Comment("Refusals before a seat is taken. %plugin% is the plugin that has the player, %reason% what")
        @Comment("another plugin says about them.")
        String busy,
        String busyElsewhere,
        String busyAdvice,
        String failed,
        @Comment("Opening a table, and inviting one player to it.")
        String created,
        String self,
        String targetBusy,
        String inviteSent,
        @Comment("The invitation, a centred block. %accept% and %decline% are the buttons below; %time% is how")
        @Comment("long it waits.")
        List<String> invite,
        String acceptButton,
        String acceptHover,
        String declineButton,
        String declineHover,
        @Comment("Answering an invitation. %player% is whoever sent it, or whoever answered it.")
        String noInvite,
        String declined,
        String youDeclined,
        @Comment("Joining an open table, and filling it. %start% is the host's start button.")
        String gone,
        String full,
        String waiting,
        String startable,
        String startButton,
        String startHover,
        String noTable,
        String cannotStart,
        @Comment("Leaving, closing and running out of time.")
        String left,
        String abandoned,
        String cancelled,
        String closed,
        String openExpired,
        String tableExpired,
        String inviteExpired,
        String inviteExpiredTarget) {

    private static final String NO = "[sound:ENTITY_VILLAGER_NO|0.7|1]%prefix% ";
    private static final String YES = "[sound:ENTITY_EXPERIENCE_ORB_PICKUP|0.7|1]%prefix% ";

    /** The library's English lines. */
    public LobbyMessages() {
        this(NO + "{error}You are already at a table.",
                NO + "{error}You are busy in {highlight}%plugin%{error} right now.",
                NO + "{error}%reason%",
                NO + "{error}Something went wrong taking your seat. Try again.",
                YES + "{letters}Your table is open. {muted}Close it with {highlight}%command% cancel{muted}.",
                NO + "{error}You cannot invite yourself.",
                NO + "{highlight}%player% {error}is busy right now.",
                YES + "{letters}Invitation sent to {highlight}%player%{letters}.",
                List.of("[center; sound:BLOCK_NOTE_BLOCK_PLING|0.7|1.6]&f",
                        "[center]{primary}&lINVITATION",
                        "[center]{highlight}%player% {letters}invites you to their table.",
                        "[center]{muted}It waits {info}%time% ⌚",
                        "[center]&f",
                        "[center]%accept% {letters_black}· %decline%",
                        "[center]&f"),
                "{success}&l[✔ ACCEPT]",
                "{letters}Click to accept",
                "{error}&l[✘ DECLINE]",
                "{letters}Click to decline",
                NO + "{error}You have no invitation from {highlight}%player%{error}.",
                NO + "{highlight}%player% {error}declined your invitation.",
                "%prefix% {letters}You declined {highlight}%player%{letters}'s invitation.",
                NO + "{error}That table is no longer open.",
                NO + "{error}That table is full.",
                YES + "{highlight}%player% {letters}sat down {letters_black}» {info}%seated%{letters_black}/{info}%seats% "
                        + "{muted}(%missing% to go)",
                "%prefix% {letters}Enough players are seated. %start%",
                "{success}&l[▶ START]",
                "{letters}Click to start with the players seated",
                NO + "{error}You have no open table.",
                NO + "{error}You need at least {highlight}%min% {error}players to start {letters_black}» "
                        + "{info}%seated%{letters_black}/{info}%seats%",
                "%prefix% {highlight}%player% {letters}left the table {letters_black}» {info}%seated%{letters_black}/{info}%seats%",
                NO + "{highlight}%player% {error}left before the start, so the table was called off.",
                "%prefix% {letters}You closed your table.",
                NO + "{highlight}%player% {error}closed the table.",
                NO + "{error}Nobody took your table in time.",
                NO + "{error}The table did not fill in time.",
                NO + "{error}Your invitation to {highlight}%player% {error}expired.",
                NO + "{error}The invitation from {highlight}%player% {error}expired.");
    }

    public LobbyMessages {
        invite = invite == null ? List.of() : List.copyOf(invite);
    }

    /** The library's English lines; the same as {@code new LobbyMessages()}. */
    public static LobbyMessages defaults() {
        return new LobbyMessages();
    }
}
