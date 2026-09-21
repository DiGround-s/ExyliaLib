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
 * @since 1.189.0
 */
public record CrateMessages(

        @Comment("%reward% is what came out, %tier% its rarity.")
        String won,

        @Comment("%reward% and %tier% as above, %refund% the keys handed back.")
        String duplicate,

        @Key("no-keys")
        @Comment("%amount% is what the opening costs, %keys% what the player holds.")
        String noKeys,

        String busy,

        String empty,

        String disabled,

        @Key("keys-received")
        @Comment("%amount% is how many key items were handed over.")
        String keysReceived,

        @Key("rewards-claimed")
        @Comment("%amount% is how many kept rewards were handed over.")
        String rewardsClaimed) {

    public CrateMessages() {
        this("%prefix%{success}You unboxed %tier% {highlight}%reward%{success}.",
                "%prefix%{warning}%tier% {highlight}%reward% {warning}again. {highlight}%refund% {warning}key back.",
                "%prefix%{error}That costs {highlight}%amount% {error}keys and you have {highlight}%keys%{error}.",
                "%prefix%{warning}Your crate is still opening.",
                "%prefix%{error}There is nothing in the crate yet.",
                "%prefix%{error}The crate is off on this server.",
                "%prefix%{success}You received {highlight}%amount% {success}crate keys.",
                "%prefix%{success}{highlight}%amount% {success}rewards were waiting for you.");
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
    }
}
