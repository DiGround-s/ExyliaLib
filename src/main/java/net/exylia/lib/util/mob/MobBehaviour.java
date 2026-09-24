package net.exylia.lib.util.mob;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * How a custom mob lives: whether hits or health break it, how long it
 * stays, and how far it may go.
 *
 * <pre>{@code
 * MobTemplate pinata = MobTemplate.of("pinata", EntityType.LLAMA)
 *         .withBehaviour(new MobBehaviour(40, Duration.ofMillis(500), Duration.ofMinutes(5), 12));
 * }</pre>
 *
 * <h2>Hits mode</h2>
 * With {@code hits} above zero nothing hurts the mob: every damage event on it
 * is cancelled but {@code VOID} and {@code KILL}. A player's melee hit counts
 * as one hit instead, at most once per {@code hitCooldown} per player, even
 * when a protection plugin cancelled it; the last hit breaks it
 * ({@link MobDeath.Cause#BROKEN}). {@code %health%} in its name shows the hits
 * left and {@code %max_health%} the hits it spawned with.
 *
 * @param hits        hits that break it; {@code 0} lets its health decide, as a vanilla mob
 * @param hitCooldown the shortest gap between two counted hits of one player
 * @param lifetime    how long it stays before it leaves ({@link MobDeath.Cause#EXPIRED});
 *                    zero stays until something kills it
 * @param roam        blocks it may stray from where it spawned: past that it walks back,
 *                    eight blocks further it is teleported back; {@code 0} goes anywhere
 * @since 1.195.0
 */
public record MobBehaviour(int hits, @NotNull Duration hitCooldown, @NotNull Duration lifetime, double roam) {

    /** A vanilla mob's behaviour: health, no lifetime, no leash. */
    public static final MobBehaviour NONE = new MobBehaviour(0, Duration.ZERO, Duration.ZERO, 0);

    public MobBehaviour {
        hits = Math.max(0, hits);
        hitCooldown = hitCooldown == null || hitCooldown.isNegative() ? Duration.ZERO : hitCooldown;
        lifetime = lifetime == null || lifetime.isNegative() ? Duration.ZERO : lifetime;
        roam = Double.isFinite(roam) ? Math.max(0, roam) : 0;
    }

    /** Whether hits break it rather than its health. */
    public boolean usesHits() {
        return hits > 0;
    }

    public @NotNull MobBehaviour withHits(int hits) {
        return new MobBehaviour(hits, hitCooldown, lifetime, roam);
    }

    public @NotNull MobBehaviour withHitCooldown(@NotNull Duration hitCooldown) {
        return new MobBehaviour(hits, hitCooldown, lifetime, roam);
    }

    public @NotNull MobBehaviour withLifetime(@NotNull Duration lifetime) {
        return new MobBehaviour(hits, hitCooldown, lifetime, roam);
    }

    public @NotNull MobBehaviour withRoam(double roam) {
        return new MobBehaviour(hits, hitCooldown, lifetime, roam);
    }
}
