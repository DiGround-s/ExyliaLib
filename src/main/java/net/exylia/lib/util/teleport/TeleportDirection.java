package net.exylia.lib.util.teleport;

/**
 * Which way round a teleport request goes.
 *
 * <p>The two commands every server has are the same request with the arrow
 * reversed, and confusing them is the classic bug: a {@code /tpahere} that
 * moves the wrong player looks exactly like a working {@code /tpa} to the code
 * and exactly like a kidnapping to the person it happened to. Naming the
 * direction on the ticket is what makes the accepting side unable to guess.
 *
 * @since 1.34.0
 */
public enum TeleportDirection {

    /**
     * The sender goes to the target — the classic {@code /tpa}.
     *
     * <p>The target is the one who answers, and answering costs them nothing:
     * they stay exactly where they are.
     */
    TO_TARGET,

    /**
     * The target comes to the sender — {@code /tpahere}.
     *
     * <p>The one who answers is the one who moves, which is why it is a
     * separate request rather than a flag on the same one: agreeing to be
     * visited and agreeing to be summoned are different answers.
     */
    TO_SENDER
}
