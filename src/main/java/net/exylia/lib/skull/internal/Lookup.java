package net.exylia.lib.skull.internal;

import java.util.UUID;

/**
 * What the module needs from Mojang.
 *
 * <p>An interface so tests can drive the cache, the request-collapsing and the
 * back-off without a network — the alternative is a module whose most
 * important behaviour is only ever exercised in production.
 *
 * <p>Implementations must not touch the main thread and must answer
 * {@code null} rather than throw: a head that cannot be fetched is a plain
 * head, never an exception in somebody's menu.
 */
public interface Lookup {

    /**
     * Resolves a player name to a unique id.
     *
     * @param name the player name
     * @return the id, or {@code null} when unknown or unavailable
     */
    UUID idOf(String name);

    /**
     * Fetches the texture property of a profile.
     *
     * @param id the player's unique id
     * @return the base64 texture, or {@code null} when unavailable
     */
    String textureOf(UUID id);

    /** Returns whether lookups are currently paused. */
    boolean isBackedOff();

    /** Returns how long the pause has left, in millis. */
    long backoffRemaining();

    /** Clears the pause. */
    void clearBackoff();
}
