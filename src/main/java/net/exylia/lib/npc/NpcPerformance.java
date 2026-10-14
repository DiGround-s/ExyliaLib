package net.exylia.lib.npc;

import net.exylia.lib.util.sequence.Sequence;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A whole-body animation a placed NPC performs: the ragdoll body ExyliaEmotes dances
 * with, wearing the NPC's skin, standing in its place while it plays.
 *
 * <pre>{@code
 * NpcPerformance.parse("anim:wave");                         // plugins/<plugin>/animations/wave
 * NpcPerformance.parse("follow:2;keys:0.3 arm_r=90 | 0.3 stand");
 * NpcPerformance.parse("emote:bow");                         // asked of Npcs.of(plugin).emotes(...)
 * NpcPerformance.lines(List.of("[RAGDOLL] {victim};intact:0;anim:wave",
 *         "[SOUND] ENTITY_PLAYER_LEVELUP;0.5;1.6"));
 * }</pre>
 *
 * <h2>The notation</h2>
 * A value with a colon is a performance; a value without one is
 * {@link NpcAnimation}'s vanilla moves, whose words never have one.
 * <ul>
 *   <li>{@code emote:<id>} names a performance the plugin's resolver knows
 *       ({@link PluginNpcs#emotes}); it is looked up each time it plays.</li>
 *   <li>Anything else is a {@code [RAGDOLL]} line's parameters without the
 *       {@code {victim};} head: {@code anim:<name>} for an animation file, or
 *       {@code keys:<frames>} written on the line, with any of the line's other
 *       options before it ({@code follow:}, {@code breathe:}, {@code intact:},
 *       {@code tempo:}, {@code loop:}...). {@code keys:} goes last, because its
 *       frames are full of spaces and bars.</li>
 * </ul>
 * Left unwritten, {@code intact:0}, {@code follow:1} and {@code breathe:1} are what a
 * performance plays with: it starts on the first tick, its loose joints lag a little,
 * and a held pose breathes.
 *
 * <p>Immutable; compiled the first time it plays and kept.
 *
 * @since 1.302.0
 */
public final class NpcPerformance {

    /** How long a looping performance plays when nothing says otherwise. */
    public static final Duration DEFAULT_LOOP_LIMIT = Duration.ofSeconds(8);

    /** What a performance plays with unless its notation says otherwise. */
    private static final List<String> DEFAULTS = List.of("intact:0", "follow:1", "breathe:1");

    private static final String EMOTE = "emote:";

    private static final NpcPerformance NONE = new NpcPerformance(List.of(), null, DEFAULT_LOOP_LIMIT);

    private final List<String> lines;
    private final @Nullable String emote;
    private final Duration loopLimit;

    /** The lines compiled for the plugin that first played it. */
    private volatile Compiled compiled;

    private record Compiled(String owner, Sequence sequence) {
    }

    private NpcPerformance(List<String> lines, @Nullable String emote, Duration loopLimit) {
        this.lines = List.copyOf(lines);
        this.emote = emote;
        this.loopLimit = loopLimit;
    }

    /** Nothing at all; performing it does nothing. */
    public static @NotNull NpcPerformance none() {
        return NONE;
    }

    /**
     * Whether a written value is a performance rather than vanilla moves.
     *
     * @param value what a file says
     * @return whether it has a colon
     */
    public static boolean isPerformance(@Nullable String value) {
        return value != null && value.indexOf(':') >= 0;
    }

    /**
     * Reads a performance from its notation.
     *
     * @param notation {@code emote:<id>}, {@code anim:<name>}, or {@code [RAGDOLL]}
     *                 parameters ending in {@code keys:<frames>}
     * @return the performance; {@link #none()} for a blank value
     */
    public static @NotNull NpcPerformance parse(@Nullable String notation) {
        String text = notation == null ? "" : notation.trim();
        if (text.isEmpty()) {
            return NONE;
        }
        if (text.toLowerCase(Locale.ROOT).startsWith(EMOTE)) {
            String id = text.substring(EMOTE.length()).trim();
            return id.isEmpty() ? NONE : new NpcPerformance(List.of(), id, DEFAULT_LOOP_LIMIT);
        }
        return new NpcPerformance(List.of("[RAGDOLL] {victim};" + withDefaults(text)), null,
                DEFAULT_LOOP_LIMIT);
    }

    /**
     * A performance from sequence lines as a file writes them, such as an emote's
     * {@code effects}.
     *
     * <p>Every {@code [RAGDOLL]} line wears the NPC whatever its head says. Lines that
     * need a player to happen to ({@code [CAMERA]}, {@code [TITLE]}, {@code [POTION]})
     * find nobody and do nothing; sounds and particles play at the NPC.
     *
     * @param lines the lines
     * @return the performance
     */
    public static @NotNull NpcPerformance lines(@NotNull List<String> lines) {
        List<String> kept = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                kept.add(line);
            }
        }
        return kept.isEmpty() ? NONE : new NpcPerformance(kept, null, DEFAULT_LOOP_LIMIT);
    }

    /**
     * How long it plays when it loops, since a loop has no end of its own; it then stands
     * back up and the NPC returns. A performance that does not loop ignores it.
     *
     * @param limit how long; at least a second
     * @return a new performance
     */
    public @NotNull NpcPerformance loopLimit(@NotNull Duration limit) {
        Duration kept = limit.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : limit;
        return new NpcPerformance(lines, emote, kept);
    }

    /** The sequence lines it plays; empty for an {@code emote:} reference. */
    public @NotNull List<String> lines() {
        return lines;
    }

    /** The emote it names, or {@code null} when it is not a reference. */
    public @Nullable String emote() {
        return emote;
    }

    /** How long it plays when it loops. */
    public @NotNull Duration loopLimit() {
        return loopLimit;
    }

    /** Whether it plays nothing. */
    public boolean isEmpty() {
        return lines.isEmpty() && emote == null;
    }

    /**
     * The lines compiled for a plugin, compiled the first time and kept.
     *
     * @param owner   the plugin that plays it, whose animations folder {@code anim:} reads
     * @param compile how to compile them
     * @return the sequence
     */
    @ApiStatus.Internal
    public @NotNull Sequence compiled(@NotNull String owner,
                                      @NotNull java.util.function.Function<List<String>, Sequence> compile) {
        Compiled known = compiled;
        if (known == null || !known.owner.equals(owner)) {
            known = new Compiled(owner, compile.apply(lines));
            compiled = known;
        }
        return known.sequence;
    }

    /** The parameters with the performance defaults put in front of any it does not set. */
    static String withDefaults(String parameters) {
        Set<String> written = new LinkedHashSet<>();
        for (String parameter : parameters.split(";")) {
            int colon = parameter.indexOf(':');
            if (colon > 0) {
                written.add(parameter.substring(0, colon).trim().toLowerCase(Locale.ROOT));
            }
        }
        StringBuilder line = new StringBuilder();
        for (String fallback : DEFAULTS) {
            if (!written.contains(fallback.substring(0, fallback.indexOf(':')))) {
                line.append(fallback).append(';');
            }
        }
        return line.append(parameters).toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NpcPerformance that && lines.equals(that.lines)
                && Objects.equals(emote, that.emote) && loopLimit.equals(that.loopLimit);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lines, emote, loopLimit);
    }

    @Override
    public String toString() {
        return emote != null ? EMOTE + emote : "NpcPerformance" + lines;
    }
}
