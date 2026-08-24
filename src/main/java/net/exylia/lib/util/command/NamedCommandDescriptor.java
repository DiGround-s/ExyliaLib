package net.exylia.lib.util.command;

import net.exylia.lib.input.FormKey;
import net.exylia.lib.util.editor.EditorDescriptor;
import net.exylia.lib.util.editor.EditorForm;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * How a named command draws and edits itself on screen.
 *
 * <p>Handed to the list editor by {@link NamedCommands#editor}.
 *
 * @since 1.56.0
 */
final class NamedCommandDescriptor implements EditorDescriptor<NamedCommand> {

    /** The clipboard bucket named commands share. */
    static final String TYPE_KEY = "exylia:commands";

    private static final FormKey<String> NAME = FormKey.text("name");
    private static final FormKey<String> COMMAND = FormKey.text("command");

    private final Plugin plugin;

    NamedCommandDescriptor(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public @NotNull String label(@NotNull NamedCommand entry) {
        return "{primary}&l" + entry.displayName().toUpperCase(Locale.ROOT);
    }

    @Override
    public @NotNull String icon(@NotNull NamedCommand entry) {
        return entry.isRunnable() ? "COMMAND_BLOCK" : "BARRIER";
    }

    @Override
    public @NotNull List<String> lore(@NotNull NamedCommand entry) {
        if (!entry.isRunnable()) {
            return List.of("{secondary}Runs:", " {letters_black}▎ {muted}nothing yet");
        }
        return List.of("{secondary}Runs:",
                " {letters_black}▎ {letters}/{info}" + entry.command());
    }

    @Override
    public @NotNull NamedCommand create() {
        return NamedCommand.blank();
    }

    @Override
    public @NotNull NamedCommand copy(@NotNull NamedCommand entry) {
        return entry.copy();
    }

    @Override
    public @NotNull String typeKey() {
        return TYPE_KEY;
    }

    @Override
    public boolean isComplete(@NotNull NamedCommand entry) {
        return entry.isRunnable();
    }

    @Override
    public @NotNull CompletionStage<Optional<NamedCommand>> edit(@NotNull Player viewer,
                                                                 @NotNull NamedCommand entry) {
        // Three lines for the command itself: a stored command is usually a
        // permission node or a give with an item's whole NBT after it, and a
        // one-line box shows about twenty characters of that.
        return EditorForm.of(plugin, viewer, "{primary}&lEDIT COMMAND")
                .text(NAME, "Name (blank to show the command)", entry.name(), 2)
                .text(COMMAND, "Command, without the slash", entry.command(), 3)
                .ask(values -> new NamedCommand(entry.id(),
                        blankToNull(values.getText(NAME)),
                        blankToNull(values.getText(COMMAND))));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
