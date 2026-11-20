package net.exylia.lib.clientmob;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * A mob that only one player sees: it walks, looks around and animates on that
 * player's client and does not exist on the server.
 *
 * <p>The server never ticks it. Movement is one shared async ticker that runs
 * only while some mob is walking. Every method may be called from any thread.
 */
public interface ClientMob {

    /** The one player who sees it. */
    @NotNull Player viewer();

    /**
     * Where it stands now, also while it walks.
     *
     * @return a copy
     */
    @NotNull Location location();

    /**
     * Walks it in a straight line to {@code target}, turning body and head the
     * way it goes. The mob does no pathfinding: the caller picks a line that is
     * clear.
     *
     * @param target         where to, in the mob's world
     * @param blocksPerSecond how fast, above 0
     * @return completes when it arrives, or when a newer move, a teleport or a
     *         removal replaces this one
     */
    @NotNull CompletableFuture<Void> moveTo(@NotNull Location target, double blocksPerSecond);

    /** Puts it at {@code to} at once, cancelling a walk in progress. */
    void teleport(@NotNull Location to);

    /** Turns body and head towards a point. A walk keeps turning it the way it goes. */
    void lookAt(@NotNull Location point);

    /**
     * The attack arm swing: the golem's, ravager's, hoglin's and warden's own
     * attack animation, the main-arm swing for every other living mob.
     */
    void swing();

    /** A raw entity status (hurt, death, attack, love hearts...), as the client defines them for the type. */
    void status(byte status);

    /**
     * A name floating above its head.
     *
     * @param miniMessage the name, or {@code null} for none
     */
    void name(@Nullable String miniMessage);

    /**
     * Outlines it in the nearest of the sixteen chat colours, or takes the outline off.
     *
     * @param color the outline, or {@code null} for none
     */
    void glow(@Nullable Color color);

    /**
     * A waiting pose. Wolves, cats and foxes sit; camels lie down; every other
     * type crouches.
     */
    void sitting(boolean sitting);

    /** Draws it as a baby, for the types that have one. Ignored by the others. */
    void baby(boolean baby);

    /** Takes it off its viewer's screen for good. */
    void remove();

    /** Whether {@link #remove()} was called, or its viewer or plugin went away. */
    boolean isRemoved();
}
