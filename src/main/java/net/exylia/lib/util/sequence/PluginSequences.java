package net.exylia.lib.util.sequence;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.sequence.internal.SequenceAccess;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One plugin's view of the sequence module.
 *
 * <pre>{@code
 * private PluginSequences sequences;
 * private Sequence onKill;
 *
 * public void onEnable() {
 *     sequences = Sequences.of(this);
 *     onKill = sequences.compile(config.getStringList("kill.effects"));
 * }
 *
 * // Later, on every kill:
 * sequences.play(onKill, SequenceTarget.at(victim.getLocation()).by(killer));
 * }</pre>
 *
 * <h2>Per plugin, not static</h2>
 * The scheduler that runs a sequence, the console that hears about a bad line,
 * and the runs that must stop when the plugin is disabled all belong to the
 * plugin that asked. A shared static would leave one plugin's effects drawing
 * after its classloader was gone.
 *
 * @since 1.30.0
 */
public final class PluginSequences {

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final Debug debug;
    private final Map<String, Shape> shapes;
    private final Map<SequenceRun, Boolean> playing = new ConcurrentHashMap<>();

    PluginSequences(@NotNull Plugin plugin, @NotNull Map<String, Shape> shapes) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
        this.debug = Debug.of(plugin);
        this.shapes = shapes;
    }

    /** The plugin these belong to. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    // ---------------------------------------------------------------- compiling

    /**
     * Compiles configuration lines into a sequence.
     *
     * <p>Call this when the file is read, not when the effect plays. Every name
     * is resolved and every shape's points computed here, once, and shared by
     * every play afterwards.
     *
     * <p>A line that cannot be understood is reported to the console and left
     * out; the rest of the sequence still plays.
     *
     * @param lines the {@code effects} list
     * @return the compiled sequence, never {@code null}
     */
    public @NotNull Sequence compile(@NotNull List<String> lines) {
        return compile(lines, "sequence");
    }

    /**
     * Compiles configuration lines, naming where they came from in any report.
     *
     * <p>"In sequence \"fire_trail\"" beats "in sequence" when a server has two
     * hundred of them.
     *
     * @param lines the {@code effects} list
     * @param name  what to call it in a console message
     * @return the compiled sequence
     */
    public @NotNull Sequence compile(@NotNull List<String> lines, @NotNull String name) {
        AtomicInteger reported = new AtomicInteger();
        var compiler = SequenceAccess.compiler(shapes, (line, problem) -> {
            reported.incrementAndGet();
            debug.warn("In " + name + ", \"" + line + "\": " + problem);
        });
        Sequence sequence = SequenceAccess.sequence(compiler.compile(lines));
        if (reported.get() > 0) {
            debug.warn("In " + name + ", " + reported.get()
                    + " line(s) were skipped; the rest still plays.");
        }
        return sequence;
    }

    // ------------------------------------------------------------------ playing

    /**
     * Plays a sequence.
     *
     * <p>Safe from any thread: the work moves onto the thread that owns the
     * target's location, which is what makes it correct on Folia.
     *
     * @param sequence what to play
     * @param target   where, and who sees it
     * @return the run, for cancelling
     */
    public @NotNull SequenceRun play(@NotNull Sequence sequence, @NotNull SequenceTarget target) {
        // The holder exists so the finish callback can remove the very run it
        // belongs to: the callback is built before the run it refers to.
        SequenceRun[] holder = new SequenceRun[1];
        SequenceRun run = SequenceAccess.play(sequence, target, tasks, () -> {
            if (holder[0] != null) {
                playing.remove(holder[0]);
            }
        });
        holder[0] = run;
        run.problems((step, failure) -> debug.error(
                "An effect step failed while playing a sequence.", failure));
        if (!run.isFinished()) {
            playing.put(run, Boolean.TRUE);
        }
        return run;
    }

    /**
     * Plays a sequence for one player and nobody else.
     *
     * <p>What a menu preview wants: the player sees their choice, the arena
     * does not.
     *
     * @param sequence what to play
     * @param viewer   the only player who sees it
     * @return the run
     */
    public @NotNull SequenceRun preview(@NotNull Sequence sequence, @NotNull Player viewer) {
        return play(sequence, SequenceTarget.of(viewer).onlyTo(viewer));
    }

    /**
     * Stops every sequence this plugin is playing.
     *
     * <p>Called by the library when the plugin is disabled; a consumer does not
     * need to.
     *
     * @return how many were stopped
     */
    public int stopAll() {
        int stopped = 0;
        for (SequenceRun run : List.copyOf(playing.keySet())) {
            run.cancel();
            stopped++;
        }
        playing.clear();
        return stopped;
    }

    /** How many sequences this plugin is playing right now. */
    public int active() {
        return playing.size();
    }

    // ------------------------------------------------------------------- shapes

    /**
     * Registers a shape of this plugin's own.
     *
     * <pre>{@code
     * sequences.shape("heart", args -> { ... });
     * // then, in configuration:
     * //   - '[HEART] DUST;size:1.5;color:{accent}'
     * }</pre>
     *
     * <p>It becomes a token like any other and inherits everything the built-in
     * shapes have: colour, animation, rotation, scaling and visibility, none of
     * which the author writes.
     *
     * <p>A name already taken by a built-in replaces it, for this plugin only.
     *
     * @param name  the token name, without brackets and case-insensitive
     * @param shape what points it draws
     * @return this
     */
    public @NotNull PluginSequences shape(@NotNull String name, @NotNull Shape shape) {
        shapes.put(name.toLowerCase(java.util.Locale.ROOT), shape);
        return this;
    }

    /** The shape names this plugin can use, built-in and its own. */
    public @NotNull java.util.Set<String> shapeNames() {
        return java.util.Set.copyOf(shapes.keySet());
    }

    @Override
    public String toString() {
        return "PluginSequences[" + plugin.getName() + ", " + playing.size() + " playing]";
    }
}
