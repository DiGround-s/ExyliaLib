package net.exylia.lib.session;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * One plugin's exclusive hold on one player.
 *
 * <p>A claim is handed out by {@link PluginSessions#claim} and is the only
 * proof that a plugin is still the one in charge of a player. It carries a
 * token that is unique for the whole life of the server, which is what makes
 * {@link #isCurrent()} answer a question no boolean flag can: not "is this
 * player in FFA" but "is this player in <em>the same</em> FFA visit I started".
 *
 * <h2>Why a token and not a flag</h2>
 * Nearly everything a plugin does to a player finishes later than it starts: a
 * teleport resolves on a future tick, an arena is copied on another thread, a
 * kit is given after the world has loaded. The code that runs at the end used
 * to check a flag — "is this player still in FFA?" — and a player who left and
 * came straight back read as still in FFA, so a callback belonging to the first
 * visit applied itself to the second. Worse, a player who left FFA for the
 * practice lobby had a lobby callback land on them while an event was already
 * setting them up, which is how a lobby's flight permission followed a player
 * into an arena that forbids flying.
 *
 * <p>The token has no such blind spot. It changes on every claim, so a
 * continuation fenced with {@link #ifCurrent(Runnable)} runs for the visit that
 * started it and for nothing else.
 *
 * <h2>Giving the player back</h2>
 * There are two ways a claim ends and they are deliberately different methods.
 * {@link #release()} is the owner saying it has finished — it has already put
 * the player back where they belong. {@link #evict()} is somebody else asking
 * for the player, which runs the handler the owner registered so the owner does
 * the putting back. An owner must never call {@code evict} on its own claim
 * from inside its own leave routine; that is what {@code release} is for.
 *
 * @since 1.50.0
 */
public final class Claim {

    private final UUID player;
    private final String plugin;
    private final long token;
    private final long since;
    private final BooleanSupplier onEvict;
    private volatile String kind;
    private final AtomicBoolean evicting = new AtomicBoolean();

    Claim(UUID player, String plugin, String kind, long token, @Nullable BooleanSupplier onEvict) {
        this.player = player;
        this.plugin = plugin;
        this.kind = kind;
        this.token = token;
        this.since = System.currentTimeMillis();
        this.onEvict = onEvict;
    }

    /**
     * The player this claim is on.
     *
     * @return their unique id
     */
    public @NotNull UUID player() {
        return player;
    }

    /**
     * The plugin holding the claim.
     *
     * @return its name, as the server knows it
     */
    public @NotNull String plugin() {
        return plugin;
    }

    /**
     * What the player is doing, in the owner's own words.
     *
     * <p>The library never interprets this. It exists so another plugin can
     * tell a player <em>why</em> they cannot do something, and so a single
     * plugin with several activities — a queue and a match, an arena and its
     * kit editor — can tell its own holds apart.
     *
     * @return the activity name
     */
    public @NotNull String kind() {
        return kind;
    }

    /**
     * Renames the activity without ending the claim.
     *
     * <p>Only the owner should call this, and only for a change that is one
     * continuous hold: a player moving from a queue into the match that queue
     * found is still the same visit, and re-claiming would hand out a new token
     * that silently invalidates every callback already in flight.
     *
     * @param kind the new activity name
     * @return this claim
     */
    public @NotNull Claim as(@NotNull String kind) {
        this.kind = kind;
        return this;
    }

    /**
     * The token that identifies this claim among all claims ever made.
     *
     * @return the token
     */
    public long token() {
        return token;
    }

    /**
     * When the claim was taken.
     *
     * @return epoch milliseconds
     */
    public long since() {
        return since;
    }

    /**
     * Whether this claim is still the one in force.
     *
     * <p>False once the claim has been released, evicted, or replaced — which
     * includes the case where the same plugin claimed the same player again.
     *
     * @return true while this exact claim owns the player
     */
    public boolean isCurrent() {
        return Sessions.current(player) == this;
    }

    /**
     * Runs something only if this claim still owns the player.
     *
     * <p>The fence for every callback that finishes later than it started: a
     * teleport's continuation, a delayed task, the tail of an asynchronous
     * load. Without it those land on whoever happens to be in that player's
     * body by the time they run.
     *
     * @param action what to do while the claim holds
     */
    public void ifCurrent(@NotNull Runnable action) {
        if (isCurrent()) action.run();
    }

    /**
     * Ends the claim because the owner has finished with the player.
     *
     * <p>Does not run the eviction handler: the owner calling this has already
     * done whatever the handler would have asked it to do. Safe to call twice —
     * the second call finds the claim gone and reports false.
     *
     * @return true if this call is what ended the claim
     */
    public boolean release() {
        return Sessions.drop(this);
    }

    /**
     * Asks the owner to give the player back.
     *
     * <p>Runs the handler the owner registered and reports whether the request
     * was <em>accepted</em>, not whether it has finished. Handing a player back
     * is almost never instant — it is a teleport, an inventory restore, a
     * snapshot read — so a handler that accepts releases the claim from its own
     * callback some ticks later. Reading this as "the player is free now" was
     * wrong for every mode on the server: each one accepted, went away to do
     * the work, and was reported as having refused.
     *
     * <p>False means refused, and refusing is a real answer: a player tagged in
     * combat, or halfway through building a kit, is not available to be taken
     * and the asker needs to know that rather than assume it worked.
     *
     * <p>If the owner registered no handler the claim is dropped outright,
     * which is the honest outcome: a plugin that did not say how to undo its
     * hold cannot be asked to undo it gracefully.
     *
     * <p>Once a handler has accepted, further calls are answered yes without
     * running it again. A player already on their way out must not be sent out
     * twice, and a handler that reaches this method again through some other
     * path cannot loop.
     *
     * @return true if the player is free, or on their way to being free
     */
    public boolean evict() {
        if (!isCurrent()) return true;
        if (onEvict == null) return release();
        if (!evicting.compareAndSet(false, true)) return true;
        boolean accepted;
        try {
            accepted = onEvict.getAsBoolean();
        } catch (Throwable failed) {
            evicting.set(false);
            throw failed;
        }
        // Only a refusal is retryable. An acceptance leaves the flag set until
        // the claim itself goes away, which is what stops a second ask from
        // starting a second departure.
        if (!accepted) evicting.set(false);
        return accepted || !isCurrent();
    }

    @Override
    public String toString() {
        return "Claim{" + plugin + ":" + kind + " player=" + player + " token=" + token + "}";
    }
}
