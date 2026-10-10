package net.exylia.lib.command;

import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.placeholder.Template;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * One configured command, parsed once and ready to run.
 *
 * <p>Compiled when the file loads rather than when somebody clicks, so a click
 * costs no parsing and no placeholder compilation. A command with no
 * placeholders is finished at compile time and reuses the same string forever.
 *
 * <pre>{@code
 * CommandLine line = CommandLine.compile("console: give %player_name% diamond 1");
 * line.actor();     // CONSOLE
 * line.render(player);  // "give Steve diamond 1"
 * }</pre>
 *
 * @since 1.22.0
 */
public final class CommandLine {

    private final CommandActor actor;
    private final String raw;

    /** Set when the command has no placeholders, so nothing is rendered later. */
    private final String fixed;

    private final Template template;

    private CommandLine(CommandActor actor, String raw, String command) {
        this.actor = actor;
        this.raw = raw;
        if (Placeholders.isDynamic(command)) {
            this.fixed = null;
            this.template = Placeholders.compile(command);
        } else {
            this.fixed = command;
            this.template = null;
        }
    }

    /**
     * Parses a configured line.
     *
     * <p>A line with no actor prefix runs as the player. That is a considered
     * departure from the old behaviour, which defaulted to console: three
     * buttons in the live settings menu are written as a bare
     * {@code "killeffect"}, and running those from the console does nothing,
     * because the command requires a player. Every bare command in the network
     * today is a player command that a player pressed, so this makes those
     * buttons work rather than fail quietly.
     *
     * @param line the line as written, with or without a prefix
     * @return the compiled command
     * @throws IllegalArgumentException if there is no command left to run
     */
    public static @NotNull CommandLine compile(@NotNull String line) {
        return compile(line, CommandActor.PLAYER);
    }

    /**
     * Parses a configured line whose bare form runs as someone else.
     *
     * <p>A bare line in a menu is a button a player pressed, so {@link #compile(String)}
     * runs it as the player. A bare line in a loot table or a reward list is a
     * payment written as {@code eco give %player% 100}: run as the player it
     * fails on permissions and pays nothing. Those lists compile with
     * {@link CommandActor#CONSOLE} here; a line that names its actor still gets
     * the one it names.
     *
     * <pre>{@code
     * CommandLine line = CommandLine.compile("eco give %player% 100", CommandActor.CONSOLE);
     * }</pre>
     *
     * @param line the line as written, with or without a prefix
     * @param bare who runs a line that names nobody
     * @return the compiled command
     * @throws IllegalArgumentException if there is no command left to run
     * @since 1.266.0
     */
    public static @NotNull CommandLine compile(@NotNull String line, @NotNull CommandActor bare) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Command line is empty");
        }
        CommandActor actor = bare;
        String command = trimmed;

        int colon = trimmed.indexOf(':');
        if (colon > 0) {
            CommandActor named = CommandActor.byPrefix(trimmed.substring(0, colon));
            if (named != null) {
                actor = named;
                command = trimmed.substring(colon + 1).trim();
            }
        }
        // A leading slash is how people write commands everywhere else, and
        // dispatching one with the slash still attached silently finds nothing.
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.isEmpty()) {
            throw new IllegalArgumentException(
                    "Command line has a \"" + actor.prefix() + ":\" prefix but no command: " + line);
        }
        return new CommandLine(actor, trimmed, command);
    }

    /** Who runs this command. */
    public @NotNull CommandActor actor() {
        return actor;
    }

    /** The line as written in configuration. */
    public @NotNull String raw() {
        return raw;
    }

    /** Returns whether this command has to be rendered before it can run. */
    public boolean isDynamic() {
        return fixed == null;
    }

    /**
     * The command to run, with placeholders resolved.
     *
     * @param viewer who the placeholders are rendered for
     * @return the command, without a leading slash
     */
    public @NotNull String render(Player viewer) {
        return render(viewer, Map.of());
    }

    /**
     * The command to run, with extra values the placeholders can read.
     *
     * <p>The values win over a registered placeholder of the same name: the row
     * a button was drawn from is what the command it runs is about.
     *
     * @param viewer who the placeholders are rendered for
     * @param data   values for the resolvers, such as the row being drawn
     * @return the command, without a leading slash
     */
    public @NotNull String render(Player viewer, @NotNull Map<String, Object> data) {
        return fixed != null ? fixed : Placeholders.renderValuesFirst(template, viewer, data,
                actor.isPlayer() ? CommandLine::asText : CommandLine::asArgument);
    }

    /**
     * A value as it may stand in a command the player runs: one line.
     *
     * <p>The player runs it with their own permissions, so a space in a value
     * gives them nothing they could not type; a line break or a control
     * character is never part of a command.
     */
    static String asText(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!Character.isISOControl(character)) {
                out.append(character);
            }
        }
        return out.toString();
    }

    /**
     * A value as it may stand in a command the console runs: one argument.
     *
     * <p>The console runs with every permission, and a value is often a name a
     * player chose. Whitespace becomes {@code _} so a clan called
     * {@code x op me} cannot add arguments, control characters are dropped,
     * and a leading slash goes, since no argument needs one.
     */
    static String asArgument(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isWhitespace(character) || Character.isSpaceChar(character)) {
                out.append('_');
            } else if (!Character.isISOControl(character) && !(character == '/' && out.isEmpty())) {
                out.append(character);
            }
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return "CommandLine[" + actor.prefix() + ": " + raw + "]";
    }
}
