package net.exylia.lib.util.teleport;

/**
 * What happened to a teleport request.
 *
 * <p>Deliberately more values than a boolean, because every one of these is a
 * different message to a player. "There is no request from that person" and
 * "there was, and it ran out" send them to look at two different things:
 * whether they typed the name right, and how long they left it.
 *
 * @since 1.34.0
 */
public enum TpaOutcome {

    /** The request was created and the target may now answer it. */
    SENT,

    /** That exact request is already waiting; nothing new was created. */
    ALREADY_PENDING,

    /** Somebody asked to be teleported to themselves. */
    SELF,

    /** The target is already sitting on as many requests as they are allowed. */
    TARGET_BUSY,

    /** There is no request between those two players. */
    NO_REQUEST,

    /** There was one, and it ran out before it was answered. */
    EXPIRED,

    /** The request was accepted and the teleport is the caller's to start. */
    ACCEPTED,

    /** The target refused it. */
    DENIED,

    /** The sender withdrew it. */
    CANCELLED
}
