package net.exylia.lib.replay;

/**
 * How a replay puts the arena back.
 *
 * <p>A recording holds every block that changed during the match. There are two
 * ways to show them again, and which one is right depends on one question:
 * does the caller own the place the replay is being watched in?
 *
 * @since 1.179.0
 */
public enum ReplayWorld {

    /**
     * Drawn for the viewer, never placed.
     *
     * <p>Costs the server nothing, needs no cleanup, and lets two people watch
     * two different matches in the same room. The catch is real and worth
     * knowing: a client predicts its own movement against its own copy of the
     * world, so a viewer who walks into a block the server does not have is
     * pushed back out of it by the next correction. Fine for a camera that
     * stays in the air and looks; not fine for one that lands on the bridge
     * somebody built.
     */
    PACKET,

    /**
     * Written into the world, and taken back out afterwards.
     *
     * <p>For a caller that owns the arena outright. The server and the client
     * agree about every block, so nothing is corrected and nothing rubber-bands
     * &mdash; a viewer can stand on the bridge, walk into the crater and look
     * around inside it.
     *
     * <p>Every position is restored to what the recording says was there before
     * it, when the playback stops. That is exact rather than a guess, but it is
     * still the caller's arena: give it to a replay only when nobody else is in
     * it.
     */
    SOLID
}
