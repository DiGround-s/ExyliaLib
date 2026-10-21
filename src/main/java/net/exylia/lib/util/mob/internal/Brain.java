package net.exylia.lib.util.mob.internal;

import org.jetbrains.annotations.NotNull;

/**
 * What a model mob with a body wants to do this moment, from what it knows.
 *
 * <p>Pure: {@link MobBrain} reads the world into a {@link Sense} and carries the
 * {@link Intent} out, so every decision can be asserted without a server.
 */
final class Brain {

    /** What it is doing. */
    enum Intent {
        /** Nothing to do: stands, or floats, and looks about. */
        IDLE,
        /** Nothing to do, and it wanders. */
        ROAM,
        /** Goes for its target. */
        HUNT,
        /** Stays where it is, facing its target: in reach, or at the distance it keeps. */
        HOLD,
        /** Steps away from a target that came too close for the distance it keeps. */
        BACK_OFF,
        /** Runs from its target, hurt badly. */
        FLEE,
        /** Walks back towards where it spawned, past its roam. */
        HOME
    }

    /**
     * What it knows.
     *
     * @param hasTarget      whether it has somebody to fight
     * @param distance       how far its target is, blocks
     * @param reach          the furthest its attacks strike from, blocks
     * @param keepDistance   the distance it keeps, blocks; {@code 0} closes in
     * @param health         its health as a share, {@code 0-1}
     * @param fleeBelow      the share below which it flees; {@code 0} never
     * @param fromHome       how far it is from where it spawned, blocks
     * @param roam           how far it may go from there; {@code 0} anywhere
     * @param wanders        whether it wanders when it has nothing to do
     * @param moves          whether it can move at all
     */
    record Sense(boolean hasTarget, double distance, double reach, double keepDistance, double health,
                 double fleeBelow, double fromHome, double roam, boolean wanders, boolean moves) {
    }

    /** How much closer than the distance it keeps a target may come before it backs off. */
    static final double TOO_CLOSE = 0.65;
    /** How much further than the distance it keeps a target may go before it follows. */
    static final double TOO_FAR = 1.25;
    /** The share of its reach it closes to, so a step back by the target does not leave it short. */
    static final double CLOSE_IN = 0.85;

    private Brain() {
    }

    static @NotNull Intent decide(@NotNull Sense sense) {
        if (!sense.moves()) {
            return sense.hasTarget() ? Intent.HOLD : Intent.IDLE;
        }
        if (sense.roam() > 0 && sense.fromHome() > sense.roam()) {
            return Intent.HOME;
        }
        if (!sense.hasTarget()) {
            return sense.wanders() ? Intent.ROAM : Intent.IDLE;
        }
        if (sense.fleeBelow() > 0 && sense.health() < sense.fleeBelow()) {
            return Intent.FLEE;
        }
        if (sense.keepDistance() > 0) {
            if (sense.distance() < sense.keepDistance() * TOO_CLOSE) {
                return Intent.BACK_OFF;
            }
            // Never so far that its attacks cannot reach.
            double far = Math.min(sense.keepDistance() * TOO_FAR, Math.max(sense.reach(), sense.keepDistance()));
            return sense.distance() > far ? Intent.HUNT : Intent.HOLD;
        }
        return sense.distance() > Math.max(0.5, sense.reach() * CLOSE_IN) ? Intent.HUNT : Intent.HOLD;
    }
}
