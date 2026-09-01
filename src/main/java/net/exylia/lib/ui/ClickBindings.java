package net.exylia.lib.ui;

import net.exylia.lib.action.ActionCall;
import net.exylia.lib.action.ActionTemplate;
import net.exylia.lib.command.CommandLine;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a button does, per kind of click.
 *
 * <p>Compiled when the menu loads. A click then looks up an already-resolved
 * action in an {@link EnumMap} — no string is parsed, no prefix is stripped and
 * no registry is searched while somebody is waiting.
 *
 * @since 1.22.0
 */
public final class ClickBindings {

    private static final ClickBindings EMPTY =
            new ClickBindings(new EnumMap<>(ClickKind.class), new EnumMap<>(ClickKind.class), List.of());

    private final Map<ClickKind, List<ActionTemplate>> byKind;

    /** Commands are a separate list in the file, and stay separate here. */
    private final Map<ClickKind, List<CommandLine>> commandsByKind;

    /** Kept so a rebuilt menu can report what it was configured with. */
    private final List<String> raw;

    private ClickBindings(Map<ClickKind, List<ActionTemplate>> byKind,
                          Map<ClickKind, List<CommandLine>> commandsByKind,
                          List<String> raw) {
        this.byKind = byKind;
        this.commandsByKind = commandsByKind;
        this.raw = raw;
    }

    /** A button that does nothing. */
    public static @NotNull ClickBindings none() {
        return EMPTY;
    }

    /** Returns whether any click is bound. */
    public boolean isEmpty() {
        return byKind.isEmpty() && commandsByKind.isEmpty();
    }

    /** The strings this was compiled from. */
    public @NotNull List<String> raw() {
        return raw;
    }

    /**
     * The actions bound to a kind of click.
     *
     * @param kind how the button was clicked
     * @return the templates, in the order they were written; never {@code null}
     */
    public @NotNull List<ActionTemplate> forClick(@NotNull ClickKind kind) {
        List<ActionTemplate> bound = byKind.get(kind);
        return bound == null ? List.of() : bound;
    }

    /**
     * The commands bound to a kind of click.
     *
     * <p>Separate from actions on purpose. An action is a registered handler
     * inside a plugin; a command is a line the server dispatches. Guessing
     * which one a string meant would make a typo in either look like the
     * other.
     *
     * @param kind how the button was clicked
     * @return the commands, in the order they were written; never {@code null}
     */
    public @NotNull List<CommandLine> commandsForClick(@NotNull ClickKind kind) {
        List<CommandLine> bound = commandsByKind.get(kind);
        return bound == null ? List.of() : bound;
    }

    /**
     * Returns whether this kind of click does anything.
     *
     * @param kind how the button was pressed
     * @return whether an action or a command is bound to it
     * @since 1.82.1
     */
    public boolean bound(@NotNull ClickKind kind) {
        return !forClick(kind).isEmpty() || !commandsForClick(kind).isEmpty();
    }

    /** Builds bindings from configuration. */
    public static final class Builder {

        private final Map<ClickKind, List<ActionTemplate>> byKind = new EnumMap<>(ClickKind.class);
        private final Map<ClickKind, List<CommandLine>> commandsByKind = new EnumMap<>(ClickKind.class);
        private final List<String> raw = new ArrayList<>();

        /**
         * Adds one line of an {@code actions} list.
         *
         * <p>Understands the prefix syntax existing menus are written in:
         * {@code "left: ..."}, {@code "shift_left: ..."} and
         * {@code "left,right: ..."}. A line with no recognised prefix is bound
         * to every click, which is what a plain action means today.
         *
         * @param line     the line as written
         * @param compiler how to turn the action part into a template
         * @return this builder
         */
        public @NotNull Builder add(@NotNull String line,
                                    @NotNull java.util.function.Function<String, ActionTemplate> compiler) {
            raw.add(line);
            String trimmed = line.trim();
            Split split = split(trimmed);
            ActionTemplate template = compiler.apply(split.action());
            for (ClickKind kind : split.kinds()) {
                byKind.computeIfAbsent(kind, ignored -> new ArrayList<>()).add(template);
            }
            return this;
        }

        /**
         * Adds one line of a {@code commands} list.
         *
         * <p>Takes the same click prefix as an action, so
         * {@code "any: player: queue"} means what it looks like: on any click,
         * make the player run {@code /queue}. The remainder is parsed by the
         * command module, which owns that syntax for every consumer, not only
         * menus.
         *
         * @param line the line as written
         * @return this builder
         * @throws IllegalArgumentException if there is no command in it
         */
        public @NotNull Builder addCommand(@NotNull String line) {
            raw.add(line);
            Split split = split(line.trim());
            CommandLine command = CommandLine.compile(split.action());
            for (ClickKind kind : split.kinds()) {
                commandsByKind.computeIfAbsent(kind, ignored -> new ArrayList<>()).add(command);
            }
            return this;
        }

        /**
         * Separates an optional click prefix from the action.
         *
         * <p>The tricky part, and the reason this is not a regex: an action id
         * is itself namespaced with a colon, so {@code "practice:join"} has a
         * colon in exactly the same place a prefix would. The prefix is only a
         * prefix when every name before the colon is a click kind.
         */
        private static Split split(String line) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                return new Split(ClickKind.ANY, line);
            }
            String head = line.substring(0, colon).trim();
            if (head.isEmpty()) {
                return new Split(ClickKind.ANY, line);
            }
            java.util.Set<ClickKind> kinds = java.util.EnumSet.noneOf(ClickKind.class);
            boolean any = false;
            for (String name : head.split(",")) {
                String candidate = name.trim();
                if (candidate.equalsIgnoreCase("any") || candidate.equalsIgnoreCase("all")) {
                    any = true;
                    continue;
                }
                ClickKind kind = ClickKind.byName(candidate);
                if (kind == null) {
                    // Not a click prefix; this colon belongs to the action id.
                    return new Split(ClickKind.ANY, line);
                }
                kinds.add(kind);
            }
            String action = line.substring(colon + 1).trim();
            if (any || kinds.isEmpty()) {
                return new Split(ClickKind.ANY, action);
            }
            return new Split(Set.copyOf(kinds), action);
        }

        private record Split(Set<ClickKind> kinds, String action) {
        }

        /** Binds an already-compiled action to specific clicks. */
        public @NotNull Builder bind(@NotNull Set<ClickKind> kinds, @NotNull ActionTemplate action) {
            for (ClickKind kind : kinds) {
                byKind.computeIfAbsent(kind, ignored -> new ArrayList<>()).add(action);
            }
            return this;
        }

        public @NotNull ClickBindings build() {
            if (byKind.isEmpty() && commandsByKind.isEmpty()) {
                return EMPTY;
            }
            Map<ClickKind, List<ActionTemplate>> actions = new EnumMap<>(ClickKind.class);
            byKind.forEach((kind, bound) -> actions.put(kind, List.copyOf(bound)));
            Map<ClickKind, List<CommandLine>> commands = new EnumMap<>(ClickKind.class);
            commandsByKind.forEach((kind, bound) -> commands.put(kind, List.copyOf(bound)));
            return new ClickBindings(actions, commands, List.copyOf(raw));
        }
    }
}
