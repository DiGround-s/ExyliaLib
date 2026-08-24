package net.exylia.lib.util.sequence.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.util.internal.Conditions;
import net.exylia.lib.util.sequence.EffectEntry;
import net.exylia.lib.util.sequence.PluginSequences;
import net.exylia.lib.util.sequence.Sequence;
import net.exylia.lib.util.sequence.SequenceRun;
import net.exylia.lib.util.sequence.SequenceTarget;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Deciding whether an effect plays, and then playing it.
 *
 * <h2>Compiled once, like everything else in this module</h2>
 * The lines of an entry are compiled the first time it plays and kept, keyed by
 * the lines themselves. An entry is immutable, so the same list of lines is the
 * same sequence forever; an entry that is edited is a different list and misses
 * the cache, which is exactly right. ExyliaCommons re-derived its effect on
 * every play, on the region thread — the thing this module exists to not do.
 *
 * <h2>The gating runs before the dice</h2>
 * Permission and condition first, chance last. Who <em>may</em> see something
 * does not depend on luck, and asking in the other order is what made a rare
 * effect report "it did not come up" when the truth was a misspelled permission.
 */
@ApiStatus.Internal
public final class EffectPlayer {

    /**
     * Compiled sequences, keyed by the lines that produced them.
     *
     * <p>Bounded by size and by idle time rather than growing with the server:
     * an entry edited a hundred times leaves a hundred keys, and none of them is
     * ever asked for again.
     */
    private static final Cache<List<String>, Sequence> COMPILED = Caffeine.newBuilder()
            .maximumSize(512)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    private EffectPlayer() {
        throw new AssertionError("No instances.");
    }

    /**
     * Plays every effect that passes its gating.
     *
     * @param sequences  the plugin's sequences, which compile and play
     * @param scheduler  the plugin's scheduler, for the entries that wait
     * @param debug      where a problem is reported
     * @param effects    the effects, in any order
     * @param target     where it happens and who sees it
     * @return the runs that started now; a delayed effect is not among them
     */
    public static @NotNull List<SequenceRun> play(@NotNull PluginSequences sequences,
                                                  @NotNull TaskScheduler scheduler,
                                                  @NotNull Debug debug,
                                                  @NotNull List<EffectEntry> effects,
                                                  @NotNull SequenceTarget target) {
        List<SequenceRun> started = new ArrayList<>();
        for (EffectEntry entry : ordered(effects)) {
            if (!passes(entry, target.source(), debug)) {
                continue;
            }
            Sequence sequence = compiled(sequences, entry, debug);
            if (sequence == null) {
                continue;
            }
            SequenceTarget audience = audience(target, entry);
            if (entry.delayTicks() <= 0L) {
                started.add(sequences.play(sequence, audience));
                continue;
            }
            // A delayed effect holds up itself and nothing else, which is the
            // difference between this and a [DELAY] line inside the sequence.
            scheduler.runAtLocationLater(audience.location(), entry.delayTicks(),
                    () -> sequences.play(sequence, audience));
        }
        return List.copyOf(started);
    }

    /**
     * Whether one effect should play for this player.
     *
     * @param entry  the effect
     * @param player who it is about, possibly nobody
     * @param debug  where an unreadable condition is reported
     * @return whether to play it
     */
    public static boolean passes(@NotNull EffectEntry entry, Player player, @NotNull Debug debug) {
        if (!entry.isPlayable()) {
            return false;
        }
        if (entry.permission() != null && (player == null || !player.hasPermission(entry.permission()))) {
            return false;
        }
        if (entry.condition() != null
                && !Conditions.holds(entry.condition(), player, (subject, message) ->
                        reportOnce(debug, subject, message))) {
            return false;
        }
        if (entry.isGuaranteed()) {
            return true;
        }
        if (entry.chance() <= 0.0) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble(100.0) < entry.chance();
    }

    /** Highest priority first; equal priorities keep the order they were written in. */
    private static List<EffectEntry> ordered(List<EffectEntry> effects) {
        if (effects.size() < 2) {
            return effects;
        }
        List<EffectEntry> ordered = new ArrayList<>(effects);
        // List.sort is stable, which is what makes "keeps its written order" true
        // rather than merely likely.
        ordered.sort(Comparator.comparingInt(EffectEntry::priority).reversed());
        return ordered;
    }

    private static SequenceTarget audience(SequenceTarget target, EffectEntry entry) {
        if (entry.isPrivate()) {
            Player source = target.source();
            return source == null ? target.within(0.0) : target.onlyTo(source);
        }
        return target.within(entry.radius());
    }

    private static Sequence compiled(PluginSequences sequences, EffectEntry entry, Debug debug) {
        try {
            return COMPILED.get(entry.lines(), lines -> sequences.compile(lines, entry.displayName()));
        } catch (RuntimeException uncompilable) {
            debug.error("An effect could not be compiled: " + entry.displayName(), uncompilable);
            return null;
        }
    }

    /**
     * Reports a broken condition once, not once per play.
     *
     * <p>An effect fires on every block break in a mine. A condition with a typo
     * in it would otherwise write a console line per break, which is how a log
     * becomes unreadable in the minute somebody most needs to read it.
     */
    private static void reportOnce(Debug debug, String subject, String message) {
        if (REPORTED.putIfAbsent(subject, Boolean.TRUE) == null) {
            debug.warn(message);
        }
    }

    private static final ConcurrentHashMap<String, Boolean> REPORTED = new ConcurrentHashMap<>();

    /** Test seam: the warnings already given, which "once" can only be asserted twice with. */
    public static void forgetReportedForTests() {
        REPORTED.clear();
        COMPILED.invalidateAll();
    }
}
