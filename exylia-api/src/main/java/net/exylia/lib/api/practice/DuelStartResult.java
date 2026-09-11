package net.exylia.lib.api.practice;

/**
 * What {@link PracticeService#startDuel} did.
 *
 * <p>Unlike the rest of the service's actions, a refusal here tells nobody
 * anything: neither player asked for the duel, so explaining why it is not
 * happening is the caller's job, and this is what it explains with.
 *
 * @since 1.3.0
 */
public enum DuelStartResult {

    /**
     * Both players were claimed and the match is being built.
     *
     * <p>Not a promise it will be fought: a listener may still cancel
     * {@link net.exylia.lib.api.practice.event.PracticeMatchCreateEvent}, and a
     * kit with no free arena sends both players back to the lobby with the
     * plugin's own message. {@link net.exylia.lib.api.practice.event.PracticeMatchStartEvent}
     * is what says it began.
     */
    ACCEPTED,
    /** No kit has that id, or it is disabled, or it does not allow duels. */
    UNKNOWN_KIT,
    /** Both arguments are the same player. */
    SAME_PLAYER,
    /**
     * One of the players is offline or not free: queued, fighting, watching,
     * editing a kit, in a party, or held by another plugin.
     */
    UNAVAILABLE
}
