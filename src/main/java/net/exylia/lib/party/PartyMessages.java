package net.exylia.lib.party;

import net.exylia.lib.config.Comment;

import java.util.List;

/**
 * What {@code /party} says. Kept in ExyliaLib's {@code messages.yml} under {@code parties:}, so every
 * plugin that uses parties reads the same lines in the server's language.
 *
 * <p>{@code %prefix%} is {@link #prefix()}; {@code %command%} is the command's name; {@code %player%}
 * is whoever the line is about, {@code %leader%} the party's leader, {@code %size%} and {@code %max%}
 * its members and room, {@code %time%} a length of time.
 *
 * @since 1.621.0
 */
public record PartyMessages(
        @Comment("Opens every party line below.")
        String prefix,

        @Comment("Inviting someone. %time% is how long the invitation waits.")
        String inviteSent,
        String self,
        String notLeader,
        String alreadyMember,
        String alreadyInvited,
        String full,

        @Comment("The invitation, a centred block. %accept% and %decline% are the buttons.")
        List<String> invite,
        String acceptButton,
        String acceptHover,
        String declineButton,
        String declineHover,

        @Comment("Answering it. 'joined' goes to the whole party, the new member included.")
        String noInvite,
        String inviteFull,
        String joined,
        String declined,
        String youDeclined,
        String inviteExpired,
        String inviteExpiredTarget,

        @Comment("Members going. 'quit' is told when one leaves the server and keeps their place for %time%.")
        String left,
        String youLeft,
        String kicked,
        String youWereKicked,
        String timedOut,
        String quit,
        String rejoined,
        String leader,
        String disbanded,
        String ended,
        String noParty,
        String notMember,

        @Comment("/party with nothing after it: the header, one line per member, then the commands.")
        @Comment("%state% is 'online' or 'offline'; %role% is 'leader-tag' on the leader, empty otherwise.")
        List<String> infoHeader,
        String infoMember,
        String online,
        String offline,
        String leaderTag,
        List<String> infoFooter,

        @Comment("Party chat. %message% is what they typed.")
        String chat,
        String chatOn,
        String chatOff,

        @Comment("A ready check, sent by a plugin before it starts something. %what% is what starts.")
        List<String> ready,
        String readyButton,
        String readyHover,
        String notReadyButton,
        String notReadyHover,
        @Comment("%ready% is how many said yes.")
        String readyVote,
        String readyDeclined,
        String readyAll,
        String readyTimedOut,
        String readyOffline,
        String noCheck) {

    private static final String NO = "[sound:ENTITY_VILLAGER_NO|0.7|1]%prefix% ";
    private static final String YES = "[sound:ENTITY_EXPERIENCE_ORB_PICKUP|0.6|1.2]%prefix% ";
    private static final String TICK = "[sound:BLOCK_NOTE_BLOCK_HAT|0.5|1.4]%prefix% ";

    /** The library's English lines. */
    public PartyMessages() {
        this("{primary}&lPARTY {letters_black}•<reset>",
                YES + "{letters}Invited {highlight}%player%{letters}. They have {info}%time% {letters}to answer.",
                NO + "{error}You cannot invite yourself.",
                NO + "{error}Only the leader, {highlight}%leader%{error}, can do that.",
                NO + "{highlight}%player% {error}is already in your party.",
                NO + "{highlight}%player% {error}already has your invitation.",
                NO + "{error}Your party is full {letters_black}» {info}%size%{letters_black}/{info}%max%",
                List.of("[center; sound:BLOCK_NOTE_BLOCK_PLING|0.7|1.6]&f",
                        "[center]{primary}&lPARTY INVITATION",
                        "[center]{highlight}%player% {letters}invites you to their party.",
                        "[center]{muted}%size% in it · it waits {info}%time% ⌚",
                        "[center]&f",
                        "[center]%accept% {letters_black}· %decline%",
                        "[center]&f"),
                "{success}&l[✔ ACCEPT]",
                "{letters}Click to join the party",
                "{error}&l[✘ DECLINE]",
                "{letters}Click to decline",
                NO + "{error}You have no party invitation.",
                NO + "{error}That party is full.",
                YES + "{highlight}%player% {letters}joined the party {letters_black}» {info}%size%{letters_black}/{info}%max%",
                NO + "{highlight}%player% {error}declined your invitation.",
                "%prefix% {letters}You declined {highlight}%player%{letters}'s invitation.",
                NO + "{error}Your invitation to {highlight}%player% {error}expired.",
                NO + "{error}The party invitation from {highlight}%player% {error}expired.",
                "%prefix% {highlight}%player% {letters}left the party.",
                "%prefix% {letters}You left the party.",
                "%prefix% {highlight}%player% {letters}was removed from the party.",
                NO + "{error}You were removed from {highlight}%leader%{error}'s party.",
                "%prefix% {highlight}%player% {letters}was away too long and left the party.",
                "%prefix% {muted}%player% left the server and keeps their place for %time%.",
                YES + "{highlight}%player% {letters}is back.",
                YES + "{highlight}%player% {letters}now leads the party.",
                NO + "{highlight}%player% {error}disbanded the party.",
                "%prefix% {muted}The party ended.",
                NO + "{error}You are not in a party. {muted}Start one with {highlight}%command% invite <player>{muted}.",
                NO + "{highlight}%player% {error}is not in your party.",
                List.of("&f", "[center]{primary}&lPARTY {letters_black}» {info}%size%{letters_black}/{info}%max%", "&f"),
                "  {letters_black}· %state% {letters}%player%%role%",
                "{success}●",
                "{muted}●",
                " {highlight}★",
                List.of("&f", "[center]{muted}%command% invite · leave · chat · kick · leader · disband", "&f"),
                "{primary}✦ {secondary}%player% {letters_black}» {letters}%message%",
                "%prefix% {letters}Your chat now goes to the party. {muted}%command% chat again to stop.",
                "%prefix% {letters}Your chat goes to everyone again.",
                List.of("[center; sound:BLOCK_NOTE_BLOCK_PLING|0.7|1.8]&f",
                        "[center]{primary}&lREADY CHECK",
                        "[center]{highlight}%player% {letters}wants to start {highlight}%what%{letters}.",
                        "[center]{muted}Answer within {info}%time% ⌚",
                        "[center]&f",
                        "[center]%accept% {letters_black}· %decline%",
                        "[center]&f"),
                "{success}&l[✔ READY]",
                "{letters}Click when you are ready",
                "{error}&l[✘ NOT READY]",
                "{letters}Click if you need a moment",
                TICK + "{highlight}%player% {letters}is ready {letters_black}» {info}%ready%{letters_black}/{info}%size%",
                NO + "{highlight}%player% {error}is not ready.",
                YES + "{success}Everyone is ready.",
                NO + "{error}Not everyone answered in time.",
                NO + "{error}Every member must be online to start.",
                NO + "{error}There is no ready check to answer.");
    }

    public PartyMessages {
        invite = invite == null ? List.of() : List.copyOf(invite);
        infoHeader = infoHeader == null ? List.of() : List.copyOf(infoHeader);
        infoFooter = infoFooter == null ? List.of() : List.copyOf(infoFooter);
        ready = ready == null ? List.of() : List.copyOf(ready);
    }
}
