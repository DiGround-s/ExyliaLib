package net.exylia.lib.interaction;

import net.exylia.lib.debug.Debug;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * What happens when a player clicks something in the world, compiled once.
 *
 * <pre>{@code
 * Interaction onClick = Interactions.compile(this, config.getStringList("click"));
 *
 * // when somebody clicks it, from any thread
 * onClick.run(player, Click.RIGHT, Map.of("npc_id", npc.id()));
 * }</pre>
 *
 * <p>The lines run in the order they are written, each on the thread it needs,
 * and a {@code [DELAY]} holds back every line after it. The syntax is in
 * {@link Interactions}.
 *
 * @since 1.291.0
 */
public final class Interaction {

    private static final Interaction EMPTY = new Interaction(null, List.of());

    /** One compiled line: which clicks it answers and what it does. */
    record Line(String raw, Set<Click> clicks, Step step) {
    }

    /** What a line does. Completes with whether the lines after it still run. */
    interface Step {
        CompletableFuture<Boolean> run(Plugin plugin, Player player, Click click,
                                       Map<String, Object> data);
    }

    private final Plugin plugin;
    private final List<Line> lines;

    Interaction(Plugin plugin, List<Line> lines) {
        this.plugin = plugin;
        this.lines = List.copyOf(lines);
    }

    /** An interaction that does nothing. */
    public static @NotNull Interaction empty() {
        return EMPTY;
    }

    /** Whether no line survived compiling. */
    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /**
     * Whether any line answers this click.
     *
     * @param click how the player clicked
     * @return {@code false} when running it with this click would do nothing
     */
    public boolean handles(@NotNull Click click) {
        for (Line line : lines) {
            if (line.clicks().contains(click)) {
                return true;
            }
        }
        return false;
    }

    /** The lines that compiled, as written; a line that was skipped is not here. */
    public @NotNull List<String> lines() {
        return lines.stream().map(Line::raw).toList();
    }

    /**
     * Runs the lines that answer this click.
     *
     * @param player who clicked
     * @param click  how
     * @return completes once the last line has run
     */
    public @NotNull CompletableFuture<Void> run(@NotNull Player player, @NotNull Click click) {
        return run(player, click, Map.of());
    }

    /**
     * Runs the lines that answer this click, with values for their placeholders.
     *
     * <p><b>Threading:</b> safe from any thread, the network thread included.
     * A command or a line that stops the list (a command the server refuses, an
     * action that does not succeed) stops the lines after it, as a command list
     * always has; a player who leaves stops it too.
     *
     * @param player who clicked
     * @param click  how
     * @param data   values {@code %name%} stands for, which win over a
     *               registered placeholder of the same name
     * @return completes once the last line has run, or the list stopped
     */
    public @NotNull CompletableFuture<Void> run(@NotNull Player player, @NotNull Click click,
                                                @NotNull Map<String, Object> data) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        advance(0, player, click, data == null ? Map.of() : data, done);
        return done;
    }

    private void advance(int from, Player player, Click click, Map<String, Object> data,
                         CompletableFuture<Void> done) {
        int index = from;
        while (index < lines.size() && !lines.get(index).clicks().contains(click)) {
            index++;
        }
        if (index >= lines.size() || !player.isOnline()) {
            done.complete(null);
            return;
        }
        Line line = lines.get(index);
        int next = index + 1;
        CompletableFuture<Boolean> step;
        try {
            step = line.step().run(plugin, player, click, data);
        } catch (RuntimeException failure) {
            step = CompletableFuture.failedFuture(failure);
        }
        step.whenComplete((goOn, failure) -> {
            if (failure != null) {
                Debug.of(plugin).error("The interaction line \"" + line.raw() + "\" failed for "
                        + player.getName() + "; the lines after it still run.", failure);
                advance(next, player, click, data, done);
            } else if (Boolean.TRUE.equals(goOn)) {
                advance(next, player, click, data, done);
            } else {
                done.complete(null);
            }
        });
    }

    @Override
    public String toString() {
        return "Interaction" + lines();
    }
}
