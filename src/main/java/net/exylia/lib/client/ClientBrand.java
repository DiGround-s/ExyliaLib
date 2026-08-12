package net.exylia.lib.client;

import org.jetbrains.annotations.NotNull;

/**
 * Which modified client a player is running.
 *
 * <p>A plugin rarely needs this: everything in {@link Clients} already sends
 * only what a given client understands. It is here for the cases where the
 * server genuinely wants to know, such as a join message or a statistic.
 *
 * @since 1.7.0
 */
public enum ClientBrand {

    /** Vanilla, or a client that announces nothing. */
    VANILLA("Vanilla"),

    /** Lunar Client, reached through Apollo. */
    LUNAR("Lunar"),

    /** Feather Client. */
    FEATHER("Feather");

    private final String display;

    ClientBrand(String display) {
        this.display = display;
    }

    /**
     * Returns the name to show a human.
     *
     * @return the display name
     */
    public @NotNull String display() {
        return display;
    }

    /**
     * Returns whether this is a modified client rather than vanilla.
     *
     * @return {@code true} for anything but {@link #VANILLA}
     */
    public boolean isModified() {
        return this != VANILLA;
    }
}
