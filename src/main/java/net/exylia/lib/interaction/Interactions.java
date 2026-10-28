package net.exylia.lib.interaction;

import net.exylia.lib.action.ActionContext;
import net.exylia.lib.action.Actions;
import net.exylia.lib.command.CommandLine;
import net.exylia.lib.command.Commands;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.effect.Ticks;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.placeholder.Template;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Values;
import net.exylia.lib.util.sequence.Sequence;
import net.exylia.lib.util.sequence.SequenceTarget;
import net.exylia.lib.util.sequence.Sequences;
import net.exylia.lib.util.sequence.internal.SequenceCompiler;
import net.exylia.lib.util.teleport.ExyliaLocation;
import net.exylia.lib.util.teleport.Teleports;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Entry point of the interaction module: what clicking something in the world
 * does, written as a list of lines.
 *
 * <pre>
 * click:
 *   - '[SOUND] UI_BUTTON_CLICK;1;1.2'
 *   - 'left: [MESSAGE] {letters}Right-click to join.'
 *   - 'right: arena join %arena%'
 *   - 'shift_right: console: arena reset %arena%'
 *   - '[DELAY] 1s'
 *   - 'right: [SERVER] lobby'
 * </pre>
 *
 * <h2>A line</h2>
 * An optional click filter, then one action:
 * <ul>
 *   <li>Filter: {@code left:}, {@code right:}, {@code shift_left:},
 *       {@code shift_right:}, {@code shift:} (either while sneaking),
 *       {@code any:}, or a list such as {@code left,right:}. No filter is any
 *       click.</li>
 *   <li>{@code [DELAY] 1s} waits before the lines after it.</li>
 *   <li>{@code [SERVER] lobby} sends the player to another server.</li>
 *   <li>{@code [TELEPORT] world;x;y;z[;yaw;pitch]} moves them on this one.</li>
 *   <li>{@code [ACTION] namespace:id args} runs a registered action.</li>
 *   <li>A token a plugin registered with {@link #token}.</li>
 *   <li>Any other {@code [TOKEN]} is a sequence line ({@code [MESSAGE]},
 *       {@code [SOUND]}, {@code [TITLE]}, {@code [PARTICLE]}...), played for the
 *       clicking player.</li>
 *   <li>Anything else is a command, exactly as {@link CommandLine} reads it:
 *       {@code console:}, {@code player:}, {@code player-proxy:},
 *       {@code console-proxy:}, bare runs as the player.</li>
 * </ul>
 *
 * <p>{@code %player_name%} and every registered placeholder resolve for the
 * clicking player, and the {@code data} map given to
 * {@link Interaction#run(Player, Click, Map)} fills {@code %name%} first.
 *
 * @since 1.291.0
 */
public final class Interactions {

    private static final Set<String> BUILT_IN = Set.of("DELAY", "SERVER", "TELEPORT", "ACTION");
    private static final Set<Click> ANY = Set.copyOf(EnumSet.allOf(Click.class));

    private record Registered(String plugin, InteractionToken handler) {
    }

    private static final Map<String, Registered> TOKENS = new ConcurrentHashMap<>();

    /** {@code plugin:TOKEN} already reported as unregistered when clicked. */
    private static final Set<String> UNKNOWN_REPORTED = ConcurrentHashMap.newKeySet();

    private Interactions() {
        throw new AssertionError("No instances.");
    }

    /**
     * Compiles configured lines.
     *
     * @param plugin the plugin the lines belong to: its scheduler, its console
     * @param lines  the lines as written; {@code null} is none
     * @return the interaction, never {@code null}
     */
    public static @NotNull Interaction compile(@NotNull Plugin plugin, @Nullable List<String> lines) {
        return compile(plugin, lines, "interaction");
    }

    /**
     * Compiles configured lines, naming where they came from in any report.
     *
     * <p>A line that cannot be understood is reported once, here, and left out;
     * the rest still run. Nothing throws.
     *
     * @param plugin the plugin the lines belong to
     * @param lines  the lines as written
     * @param name   what to call them in a console message, such as
     *               {@code "hologram \"spawn\""}
     * @return the interaction, never {@code null}
     */
    public static @NotNull Interaction compile(@NotNull Plugin plugin, @Nullable List<String> lines,
                                               @NotNull String name) {
        return compile(plugin, lines, name, Map.of());
    }

    /**
     * Compiles configured lines with tokens only these lines know.
     *
     * <p>For a verb that means something only to the thing being clicked, such
     * as a hologram's {@code [PAGE] next}: it needs no global registration and
     * cannot clash with another plugin's token. A local token wins over a
     * registered one of the same name; the built-in ones cannot be taken.
     *
     * @param plugin the plugin the lines belong to
     * @param lines  the lines as written
     * @param name   what to call them in a console message
     * @param tokens tokens by name, without brackets; case does not matter
     * @return the interaction, never {@code null}
     * @since 1.297.0
     */
    public static @NotNull Interaction compile(@NotNull Plugin plugin, @Nullable List<String> lines,
                                               @NotNull String name,
                                               @NotNull Map<String, InteractionToken> tokens) {
        if (lines == null || lines.isEmpty()) {
            return Interaction.empty();
        }
        Debug debug = Debug.of(plugin);
        List<Interaction.Line> compiled = new ArrayList<>(lines.size());
        for (String raw : lines) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                compiled.add(line(plugin, raw.trim(), name, local(tokens)));
            } catch (IllegalArgumentException problem) {
                debug.warn("In " + name + ", \"" + raw.trim() + "\" was skipped: "
                        + problem.getMessage());
            }
        }
        return compiled.isEmpty() ? Interaction.empty() : new Interaction(plugin, compiled);
    }

    /**
     * Registers a {@code [TOKEN]} of the plugin's own.
     *
     * <pre>{@code
     * Interactions.token(this, "SAY", (player, click, text, data) -> bubble(player, text));
     * }</pre>
     *
     * <p>Lines compiled before it was registered pick it up: a token that is not
     * an effect is looked up when the line runs, and one still unregistered then
     * is reported once and skipped (since 1.392.0; before, it was dropped at
     * compile). Released when the plugin disables.
     *
     * @param plugin  the owner
     * @param name    the token, without brackets; case does not matter
     * @param handler what a line with it does, on the player's thread
     * @throws IllegalArgumentException if the name is built in or another
     *                                  plugin registered it
     */
    public static void token(@NotNull Plugin plugin, @NotNull String name,
                             @NotNull InteractionToken handler) {
        String key = name.trim().toUpperCase(Locale.ROOT);
        if (key.isEmpty() || BUILT_IN.contains(key)) {
            throw new IllegalArgumentException("[" + key + "] cannot be registered: it is built in");
        }
        Registered mine = new Registered(plugin.getName(), handler);
        Registered held = TOKENS.merge(key, mine,
                (old, fresh) -> old.plugin().equals(fresh.plugin()) ? fresh : old);
        if (held != mine) {
            throw new IllegalArgumentException("[" + key + "] is already registered by "
                    + held.plugin());
        }
    }

    /**
     * What a line can start with, for tab completion: the click filters, the
     * {@code [TOKEN]}s (built in, registered and sequence ones) and the
     * command prefixes.
     *
     * @return the starters as typed, filters first
     * @since 1.382.0
     */
    public static @NotNull List<String> starters() {
        List<String> starters = new ArrayList<>(List.of("left:", "right:", "shift_left:", "shift_right:",
                "shift:", "any:"));
        java.util.Set<String> tokens = new java.util.LinkedHashSet<>(List.of("DELAY", "SERVER", "TELEPORT", "ACTION"));
        tokens.addAll(TOKENS.keySet());
        tokens.addAll(Sequences.tokens());
        for (String token : tokens) {
            starters.add("[" + token + "]");
        }
        starters.addAll(List.of("console:", "player:", "player-proxy:", "console-proxy:"));
        return starters;
    }

    /** Whether a sequence line would read the token: a built-in effect or a shape. */
    private static boolean isEffect(Plugin plugin, String token) {
        return SequenceCompiler.isBuiltIn(token)
                || Sequences.of(plugin).shapeNames().contains(token.toLowerCase(Locale.ROOT));
    }

    /** Forgets a plugin's tokens; lifecycle calls this. */
    public static void release(@NotNull String pluginName) {
        TOKENS.values().removeIf(registered -> registered.plugin().equals(pluginName));
        UNKNOWN_REPORTED.removeIf(key -> key.startsWith(pluginName + ':'));
    }

    /** Forgets every token. */
    public static void releaseAll() {
        TOKENS.clear();
        UNKNOWN_REPORTED.clear();
    }

    // ------------------------------------------------------------ compiling

    private static Map<String, InteractionToken> local(Map<String, InteractionToken> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return Map.of();
        }
        Map<String, InteractionToken> keyed = new java.util.HashMap<>();
        tokens.forEach((key, handler) -> keyed.put(key.trim().toUpperCase(Locale.ROOT), handler));
        return keyed;
    }

    private static Interaction.Line line(Plugin plugin, String raw, String name,
                                         Map<String, InteractionToken> local) {
        String body = raw;
        Set<Click> clicks = ANY;
        int colon = raw.indexOf(':');
        if (colon > 0) {
            Set<Click> filter = filter(raw.substring(0, colon));
            if (filter != null) {
                clicks = filter;
                body = raw.substring(colon + 1).trim();
            }
        }
        if (body.isEmpty()) {
            throw new IllegalArgumentException("there is nothing after the click filter");
        }
        return new Interaction.Line(raw, clicks, step(plugin, body, name, local));
    }

    /** The clicks a prefix names, or {@code null} when it is not a click filter. */
    static @Nullable Set<Click> filter(String prefix) {
        Set<Click> clicks = EnumSet.noneOf(Click.class);
        for (String part : prefix.split(",")) {
            switch (part.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
                case "left" -> clicks.add(Click.LEFT);
                case "right" -> clicks.add(Click.RIGHT);
                case "shift_left" -> clicks.add(Click.SHIFT_LEFT);
                case "shift_right" -> clicks.add(Click.SHIFT_RIGHT);
                case "shift" -> {
                    clicks.add(Click.SHIFT_LEFT);
                    clicks.add(Click.SHIFT_RIGHT);
                }
                case "any", "all" -> clicks.addAll(ANY);
                default -> {
                    return null;
                }
            }
        }
        return Set.copyOf(clicks);
    }

    private static Interaction.Step step(Plugin plugin, String body, String name,
                                         Map<String, InteractionToken> local) {
        int close = body.indexOf(']');
        if (!body.startsWith("[") || close < 2) {
            CommandLine command = CommandLine.compile(body);
            return (owner, player, click, data) -> Commands.of(owner).run(command, player, data)
                    .thenApply(result -> result.continues());
        }
        String token = body.substring(1, close).trim().toUpperCase(Locale.ROOT);
        String rest = body.substring(close + 1).trim();
        switch (token) {
            case "DELAY" -> {
                double seconds = Ticks.parseSeconds(rest, Double.NaN);
                if (Double.isNaN(seconds) || seconds < 0) {
                    throw new IllegalArgumentException("\"" + rest + "\" is not a length of time");
                }
                long ticks = Math.round(seconds * 20);
                return (owner, player, click, data) -> {
                    CompletableFuture<Boolean> waited = new CompletableFuture<>();
                    if (ticks <= 0) {
                        waited.complete(true);
                    } else {
                        Tasks.of(owner).runLater(ticks, () -> waited.complete(true));
                    }
                    return waited;
                };
            }
            case "SERVER" -> {
                Arg server = Arg.of(required(rest, "a server name"));
                return (owner, player, click, data) -> Teleports.of(owner)
                        .toServer(player, server.render(player, data))
                        .thenApply(result -> true);
            }
            case "TELEPORT" -> {
                Arg place = Arg.of(required(rest, "world;x;y;z[;yaw;pitch]"));
                if (place.fixed() != null) {
                    place(place.fixed());
                }
                return (owner, player, click, data) -> Teleports.of(owner)
                        .to(player, place(place.render(player, data))).start().future()
                        .thenApply(result -> true);
            }
            case "ACTION" -> {
                Arg action = Arg.of(required(rest, "a namespace:id"));
                // Resolved when it runs, so an action another plugin registers
                // after these lines load still counts.
                return (owner, player, click, data) -> onPlayer(owner, player, done -> {
                    String rendered = action.render(player, data);
                    try {
                        Actions.compile(rendered)
                                .execute(ActionContext.forPlayer(player).origin("interaction").build())
                                .whenComplete((result, failure) ->
                                        done.complete(failure == null && result.continues()));
                    } catch (IllegalArgumentException unknown) {
                        Debug.of(owner).warn("In " + name + ", [ACTION] " + rendered + ": "
                                + unknown.getMessage());
                        done.complete(true);
                    }
                });
            }
            default -> {
                InteractionToken own = local.get(token);
                if (own != null) {
                    Arg argument = Arg.of(rest);
                    return (owner, player, click, data) -> onPlayer(owner, player, done -> {
                        own.run(player, click, argument.render(player, data), data);
                        done.complete(true);
                    });
                }
                if (TOKENS.containsKey(token) || !isEffect(plugin, token)) {
                    // Looked up when it runs, so a token another plugin registers after
                    // these lines compiled still counts. One nobody has registered by the
                    // time it is clicked is reported once and skipped.
                    Arg argument = Arg.of(rest);
                    return (owner, player, click, data) -> onPlayer(owner, player, done -> {
                        Registered registered = TOKENS.get(token);
                        if (registered != null) {
                            registered.handler().run(player, click, argument.render(player, data), data);
                        } else if (UNKNOWN_REPORTED.add(owner.getName() + ':' + token)) {
                            Debug.of(owner).warn("In " + name + ", [" + token + "] was skipped: no plugin"
                                    + " has registered it, and it is not an effect");
                        }
                        done.complete(true);
                    });
                }
                Sequence sequence = Sequences.of(plugin).compile(List.of(body), name);
                if (sequence.isEmpty()) {
                    // The sequence module has said what is wrong with it.
                    throw new IllegalArgumentException("[" + token + "] is not something a click can do");
                }
                return (owner, player, click, data) -> onPlayer(owner, player, done -> {
                    Values values = Values.of();
                    data.forEach(values::put);
                    Sequences.of(owner).play(sequence,
                            SequenceTarget.of(player).onlyTo(player).values(values));
                    done.complete(true);
                });
            }
        }
    }

    private static String required(String rest, String what) {
        if (rest.isEmpty()) {
            throw new IllegalArgumentException("it needs " + what);
        }
        return rest;
    }

    /** {@code world;x;y;z[;yaw;pitch]}, commas accepted too. */
    static ExyliaLocation place(String text) {
        String[] parts = text.trim().split("\\s*[;,]\\s*");
        if (parts.length != 4 && parts.length != 6) {
            throw new IllegalArgumentException("a place is world;x;y;z or world;x;y;z;yaw;pitch: " + text);
        }
        try {
            return new ExyliaLocation(null, parts[0],
                    Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]),
                    parts.length == 6 ? Float.parseFloat(parts[4]) : 0f,
                    parts.length == 6 ? Float.parseFloat(parts[5]) : 0f);
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("a coordinate is not a number: " + text);
        }
    }

    /** Runs on the player's thread; a player who left ends the list. */
    private static CompletableFuture<Boolean> onPlayer(Plugin plugin, Player player,
                                                       Consumer<CompletableFuture<Boolean>> body) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        Tasks.of(plugin).runAtEntity(player, () -> {
            try {
                body.accept(done);
            } catch (RuntimeException failure) {
                done.completeExceptionally(failure);
            }
        }, () -> done.complete(false));
        return done;
    }

    /** Text with placeholders, rendered only when it has any. */
    private record Arg(@Nullable String fixed, @Nullable Template template) {

        static Arg of(String text) {
            return Placeholders.isDynamic(text)
                    ? new Arg(null, Placeholders.compile(text))
                    : new Arg(text, null);
        }

        String render(Player player, Map<String, Object> data) {
            return fixed != null ? fixed : Placeholders.renderValuesFirst(template, player, data);
        }
    }
}
