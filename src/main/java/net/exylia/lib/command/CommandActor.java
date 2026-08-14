package net.exylia.lib.command;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Who runs a command.
 *
 * <p>Written as a prefix in configuration, which is how every menu, item and
 * reward in the network already expresses this:
 *
 * <pre>{@code
 * commands:
 *   - "player: warp arena"
 *   - "console: give %player% diamond 1"
 * }</pre>
 *
 * @since 1.22.0
 */
public enum CommandActor {

    /** The player, on this server, with the player's own permissions. */
    PLAYER("player"),

    /** The server console, on this server, with no permission checks. */
    CONSOLE("console"),

    /** The player, on the proxy, for commands the proxy owns such as {@code /server}. */
    PLAYER_PROXY("player-proxy"),

    /** The proxy console, for administrative commands across the network. */
    CONSOLE_PROXY("console-proxy");

    private final String prefix;

    CommandActor(String prefix) {
        this.prefix = prefix;
    }

    /** The prefix as written in configuration, without the colon. */
    public @NotNull String prefix() {
        return prefix;
    }

    /** Returns whether this actor runs the command on the proxy. */
    public boolean isProxy() {
        return this == PLAYER_PROXY || this == CONSOLE_PROXY;
    }

    /** Returns whether this actor runs the command as the player. */
    public boolean isPlayer() {
        return this == PLAYER || this == PLAYER_PROXY;
    }

    /**
     * The actor a prefix names, or {@code null} if it names none.
     *
     * <p>Returning {@code null} rather than guessing is deliberate. The old
     * system defaulted an unrecognised prefix to console, which turned three
     * buttons in the live settings menu into silent failures: they run
     * {@code killeffect}, a command whose handler requires a player, so
     * dispatching it from the console does nothing at all. The caller now
     * decides what a missing prefix means, and says so.
     *
     * @param prefix the text before the colon, in any case
     * @return the actor, or {@code null} when unrecognised
     */
    public static CommandActor byPrefix(@NotNull String prefix) {
        String candidate = prefix.trim().toLowerCase(Locale.ROOT);
        for (CommandActor actor : values()) {
            if (actor.prefix.equals(candidate)) {
                return actor;
            }
        }
        return null;
    }
}
