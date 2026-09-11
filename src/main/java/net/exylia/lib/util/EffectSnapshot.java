package net.exylia.lib.util;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The potion effects a player had on, to put back once something else is done
 * with them.
 *
 * <pre>{@code
 * EffectSnapshot own = EffectSnapshot.of(player, List.of("SPEED", "STRENGTH"));
 * Effects.remove(player, arenaEffects);
 * Effects.apply(player, arenaEffects);   // the arena's Speed and Strength
 * // ... the round ends ...
 * own.restoreTo(player);                  // the arena's are gone, theirs are back
 * }</pre>
 *
 * <h2>Scope</h2>
 * {@link #of(Player)} holds every active effect, and restoring clears every
 * effect first. {@link #of(Player, Collection)} holds only the named types, and
 * restoring touches only those: a type the caller never named keeps running
 * exactly as it is, which is what a plugin that lends a player two effects for
 * a fight wants &mdash; the Night Vision they came in with is not the fight's
 * to reset.
 *
 * <p>Restoring removes whatever of the scope is on now, including anything the
 * caller or the player added in between, and then puts back what was held. A
 * type that was not on at capture is simply gone afterwards.
 *
 * <h2>Durations</h2>
 * Put back as captured. A snapshot is taken because something is about to
 * replace those effects, so the time in between is time the player did not
 * have them, and charging it against their potion would be taking it twice.
 * An effect that does not end comes back not ending.
 *
 * <h2>Why not {@code Snapshot}</h2>
 * {@code Snapshot.of(player)} with {@code SnapshotPart.POTION_EFFECTS} captures
 * the whole player and its restore clears every effect, which is right for a
 * minigame that takes the player over and wrong for anything that only lends a
 * few effects. This is the narrow tool; that one stays the wide one.
 *
 * <h2>Threads</h2>
 * {@link #of} and {@link #restoreTo} read and write live player state: call
 * them from the thread that owns the player. The snapshot itself is immutable
 * and safe anywhere.
 *
 * @since 1.140.0
 */
public final class EffectSnapshot {

    /**
     * One effect as it was.
     *
     * @param type      the effect's name, as {@link Effects#parse} reads it
     * @param duration  ticks left; {@link Effects#INFINITE} when it does not end
     * @param amplifier the level, zero-based
     * @param ambient   whether it came from a beacon
     * @param particles whether particles were shown
     * @param icon      whether the icon was shown
     */
    public record Entry(@NotNull String type, int duration, int amplifier,
                        boolean ambient, boolean particles, boolean icon) {
    }

    private final @Nullable Set<Object> scope;
    private final List<Held> held;

    private EffectSnapshot(@Nullable Set<Object> scope, List<Held> held) {
        this.scope = scope;
        this.held = List.copyOf(held);
    }

    /**
     * Every effect the player has on.
     *
     * @param player the player, on their own thread
     * @return the snapshot; restoring it clears every effect first
     */
    public static @NotNull EffectSnapshot of(@NotNull Player player) {
        return new EffectSnapshot(null, reader.read(player));
    }

    /**
     * Only the named effect types the player has on.
     *
     * <p>Names are the ones {@link Effects#parse} reads, in any case. A name
     * this server has no effect for is skipped, so one list serves several
     * versions.
     *
     * @param player the player, on their own thread
     * @param types  the effect names this snapshot is about
     * @return the snapshot; restoring it touches only these types
     */
    public static @NotNull EffectSnapshot of(@NotNull Player player, @NotNull Collection<String> types) {
        Set<Object> scope = new HashSet<>();
        for (String name : types) {
            Object type = resolve(name);
            if (type != null) scope.add(type);
        }
        List<Held> kept = new ArrayList<>();
        for (Held active : reader.read(player)) {
            if (scope.contains(active.type())) kept.add(active);
        }
        return new EffectSnapshot(Set.copyOf(scope), kept);
    }

    /**
     * Removes what is on now within the scope, and puts back what was held.
     *
     * @param player the player, on their own thread
     */
    public void restoreTo(@NotNull Player player) {
        Effects.EffectRemover remover = Effects.getRemover();
        for (Held active : reader.read(player)) {
            if (scope == null || scope.contains(active.type())) {
                remover.remove(player, active.type());
            }
        }
        for (Held saved : held) {
            restorer.restore(player, saved.type(), saved.entry());
        }
    }

    /** What was held, in the order the player had them. */
    public @NotNull List<Entry> effects() {
        return held.stream().map(Held::entry).toList();
    }

    /** Whether nothing in the scope was on at capture. */
    public boolean isEmpty() {
        return held.isEmpty();
    }

    private static @Nullable Object resolve(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return null;
        try {
            return Effects.getResolver().resolve(trimmed.toUpperCase(Locale.ROOT));
        } catch (RuntimeException unknown) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Injectables for tests
    // ------------------------------------------------------------------

    /** An active effect with the type object it resolved to, which is what scopes compare. */
    record Held(@NotNull Object type, @NotNull Entry entry) {
    }

    @FunctionalInterface
    interface EffectReader {
        @NotNull List<Held> read(Player player);
    }

    @FunctionalInterface
    interface EffectRestorer {
        void restore(Player player, Object type, Entry entry);
    }

    private static volatile EffectReader reader = EffectSnapshot::readActive;
    private static volatile EffectRestorer restorer = (player, type, entry) ->
            player.addPotionEffect(new PotionEffect((PotionEffectType) type, entry.duration(),
                    entry.amplifier(), entry.ambient(), entry.particles(), entry.icon()));

    static void setReader(@NotNull EffectReader replacement) { reader = replacement; }
    static void setRestorer(@NotNull EffectRestorer replacement) { restorer = replacement; }
    static @NotNull EffectReader getReader() { return reader; }
    static @NotNull EffectRestorer getRestorer() { return restorer; }

    /**
     * The player's effects by the name {@link Effects#parse} resolves.
     *
     * <p>{@code getName} is deprecated and still the right call: the default
     * resolver is {@code getByName}, so the name round-trips through it on every
     * version this library supports.
     */
    @SuppressWarnings("deprecation")
    private static List<Held> readActive(Player player) {
        List<Held> active = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            active.add(new Held(effect.getType(), new Entry(effect.getType().getName(),
                    effect.getDuration(), effect.getAmplifier(), effect.isAmbient(),
                    effect.hasParticles(), effect.hasIcon())));
        }
        return active;
    }
}
