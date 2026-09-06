package net.exylia.lib.api.betcore;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/**
 * One way a game can be played, as the server owner configured it.
 *
 * <p>Presentation and timing only. The <em>rules</em> a mode changes are the
 * game's own business and are read from its config by {@link #id}, which is why
 * there is no strategy object here: Tic Tac Toe's {@code infinite} differs from
 * its {@code classic} by one integer, and an interface with two implementations
 * in the same file would duplicate the win detection they share.
 *
 * @param id                 the key in the game's config, e.g. {@code "infinite"}
 * @param displayName        what the mode picker calls it
 * @param icon               what the mode picker draws it as
 * @param description        the lines under that icon
 * @param turnTimeoutSeconds seconds per move, or {@code -1} to use the
 *                           plugin-wide setting
 * @param firstMove          who plays first
 *
 * @since 1.0.0
 */
public record GameMode(
        @NotNull String id,
        @NotNull String displayName,
        @NotNull String icon,
        @NotNull @Unmodifiable List<String> description,
        int turnTimeoutSeconds,
        @NotNull FirstMove firstMove) {

    /** Who moves first in a round. */
    public enum FirstMove {
        /** Whoever created the match. */
        HOST,
        /** Whoever joined it. */
        GUEST,
        /** Decided by the match's seed, so it is the same for both boards. */
        RANDOM;

        /**
         * The setting this name spells, or {@code RANDOM}.
         *
         * <p>An unreadable value is the fairest of the three rather than an
         * error: a typo in one mode's config should not stop the game loading.
         */
        @NotNull
        public static FirstMove parse(@Nullable String written) {
            if (written == null) return RANDOM;
            return switch (written.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "HOST" -> HOST;
                case "GUEST" -> GUEST;
                default -> RANDOM;
            };
        }
    }
}
