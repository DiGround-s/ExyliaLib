package net.exylia.lib.economy;

import net.exylia.lib.config.Comment;

import java.util.List;

/**
 * Which currency answers an economy call, and how long a balance may be
 * remembered.
 *
 * <p>The same idea as the colour palette and the number formats, applied to
 * money: a plugin asks for "money" and this file decides which economy serves
 * it. Generated as {@code plugins/ExyliaLib/economy.yml} on first start.
 *
 * <h2>Why this exists</h2>
 * Without it, the choice of economy is the first provider that happened to load,
 * which changes silently the day a plugin is removed. A shop that quietly moved
 * from Vault to a different currency overnight — taking every balance with it —
 * is the sort of change that should be in a file somebody edited, not in the
 * order plugins happened to enable.
 *
 * <h2>Fallback</h2>
 * When the default currency's provider is not there, the file names the order to
 * try the others. A server running one economy has a one-entry list; the point
 * is not the list's length but that the order is written down and stable.
 *
 * @param defaultCurrency the id of the currency that answers when none is named
 * @param fallback        the order to try other currencies when the default is gone
 * @param balanceCacheMillis how long a read balance may be reused
 * @since 1.26.0
 */
@Comment("Economy for every Exylia plugin.")
@Comment("")
@Comment("Plugins never name an economy plugin themselves: they ask for money")
@Comment("and this file decides which economy serves it. Change the currency")
@Comment("here and every shop, kit and reward follows.")
@Comment("")
@Comment("Run /exylialib reload after editing. No restart is needed.")
public record EconomySettings(

        @Comment("The currency that answers when an operation does not name one.")
        @Comment("The id of a registered provider: 'vault' or 'points' of the")
        @Comment("built-in ones, or the id of any currency a plugin has added.")
        String defaultCurrency,

        @Comment("The order to try other currencies when the default is not")
        @Comment("available. The first available one in this list serves the")
        @Comment("operation, and the switch is announced rather than silent —")
        @Comment("a currency changing on its own is how a balance disappears.")
        List<String> fallback,

        @Comment("How long a balance, once read, may be reused, in milliseconds.")
        @Comment("Balances are shown on scoreboards that refresh every tick,")
        @Comment("and asking the economy on every tick makes our thin wrapper")
        @Comment("the bottleneck it was meant to avoid. A balance a plugin")
        @Comment("changed through the library is refreshed at once; this only")
        @Comment("governs changes made outside it.")
        long balanceCacheMillis
) {

    /**
     * The Exylia defaults: Vault where it exists, a short cache, no guessing.
     *
     * <p>What a fresh {@code economy.yml} contains. The fallback lists the other
     * built-in currency so a server that only ever had Vault still names its
     * behaviour explicitly rather than leaving it to chance.
     */
    public EconomySettings() {
        this("vault", List.of("points"), 500L);
    }
}
