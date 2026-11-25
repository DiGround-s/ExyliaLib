package net.exylia.lib.util.crate;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Key;

/**
 * Every line a crate sends a player by itself.
 *
 * <p>Nests inside a plugin's own messages record, which is where {@code %prefix%}
 * comes from: the plugin's prefix, as {@link net.exylia.lib.text.Prefixes}
 * holds it. What an admin command says stays in the plugin, with the command.
 *
 * <pre>{@code
 * public record MyMessages(String prefix, CrateMessages crate) {
 *     public MyMessages() {
 *         this("{primary}&lTRIMS &8•&r ", new CrateMessages());
 *     }
 * }
 * }</pre>
 *
 * @param won            a reel landed on something new
 * @param duplicate      a reel landed on something already owned
 * @param noKeys         an opening costs more keys than the player has
 * @param busy           the player's last opening is still spinning
 * @param empty          the catalogue has nothing a crate could land on
 * @param disabled       the crate is turned off
 * @param keysReceived   key items were handed over
 * @param rewardsClaimed rewards kept while the player was away were handed over on join
 * @param failed         the database did not answer an opening in time (since 1.663.0); nothing is lost
 *                       either way: it is opened or given up on once the database answers
 * @since 1.189.0
 */
public record CrateMessages(

        @Comment("%reward% is what came out, %tier% its rarity.")
        String won,

        @Comment("%reward% and %tier% as above, %refund% the keys handed back; %refund:key|keys% is the word for that many.")
        String duplicate,

        @Key("no-keys")
        @Comment("%amount% is what the opening costs, %keys% what the player holds; %amount:key|keys% the word for that many.")
        String noKeys,

        String busy,

        String empty,

        String disabled,

        @Key("keys-received")
        @Comment("%amount% is how many key items were handed over, %amount:key|keys% the word for that many.")
        String keysReceived,

        @Key("rewards-claimed")
        @Comment("%amount% is how many kept rewards were handed over, %amount:reward was|rewards were% the words for that many.")
        String rewardsClaimed,

        @Comment("The database did not answer an opening in time. Nothing is lost: it opens, or is cancelled with nothing spent.")
        String failed) {

    private static final String FAILED = "%prefix%{error}The crate could not be opened right now. "
            + "{letters}Nothing is lost: if it went through, you will see what came out.";

    public CrateMessages() {
        this("%prefix%{success}You unboxed %tier% {highlight}%reward%{success}.",
                "%prefix%{warning}%tier% {highlight}%reward% {warning}again. {highlight}%refund% {warning}%refund:key|keys% back.",
                "%prefix%{error}That costs {highlight}%amount% {error}%amount:key|keys% and you have {highlight}%keys%{error}.",
                "%prefix%{warning}Your crate is still opening.",
                "%prefix%{error}There is nothing in the crate yet.",
                "%prefix%{error}The crate is off on this server.",
                "%prefix%{success}You received {highlight}%amount% {success}crate %amount:key|keys%.",
                "%prefix%{success}{highlight}%amount% {success}%amount:reward was|rewards were% waiting for you.",
                FAILED);
    }

    /** The lines before {@code failed} existed, which takes its default. */
    public CrateMessages(String won, String duplicate, String noKeys, String busy, String empty, String disabled,
                         String keysReceived, String rewardsClaimed) {
        this(won, duplicate, noKeys, busy, empty, disabled, keysReceived, rewardsClaimed, FAILED);
    }

    public CrateMessages {
        won = won == null ? "" : won;
        duplicate = duplicate == null ? "" : duplicate;
        noKeys = noKeys == null ? "" : noKeys;
        busy = busy == null ? "" : busy;
        empty = empty == null ? "" : empty;
        disabled = disabled == null ? "" : disabled;
        keysReceived = keysReceived == null ? "" : keysReceived;
        rewardsClaimed = rewardsClaimed == null ? "" : rewardsClaimed;
        failed = failed == null ? FAILED : failed;
    }
}
