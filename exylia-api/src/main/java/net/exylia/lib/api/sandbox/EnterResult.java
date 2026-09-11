package net.exylia.lib.api.sandbox;

/**
 * What came of asking for a player to be sent into a sandbox world.
 *
 * <p>Every refusal is decided before anything is taken from the player, and
 * none of them is said to the player: the caller has the reason, and is the one
 * who knows how it wants to put it.
 *
 * @since 1.3.0
 */
public enum EnterResult {

    /**
     * On their way in.
     *
     * <p>A request under way rather than an arrival: finding a safe place and
     * landing the teleport end some ticks later and can still fail, in which
     * case the player is given back as they were and told so.
     * {@link net.exylia.lib.api.sandbox.event.SandboxArriveEvent} is the
     * confirmation.
     */
    ACCEPTED,

    /** Something else on the server holds them, or they are in the middle of building a kit. */
    BUSY,

    /** The sandbox already has them; moving between its worlds is not entering one. */
    ALREADY_IN_SANDBOX,

    /** No world is configured under that id. */
    UNKNOWN_WORLD,

    /** The world is still generating, or is unavailable. */
    WORLD_NOT_READY,

    /** The slot holds no saved kit with anything in it. */
    NO_KIT,

    /**
     * A {@link net.exylia.lib.api.sandbox.event.SandboxJoinEvent} handler
     * cancelled it, or another plugin took the player between the checks and
     * the sandbox's claim.
     */
    REFUSED
}
