package net.exylia.lib.util.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.exylia.lib.util.editor.Editors;
import net.exylia.lib.util.editor.ListEditor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Lists of named commands: stored, edited and run.
 *
 * <pre>{@code
 * List<NamedCommand> setup = NamedCommands.decode(arena.commandsJson());
 *
 * NamedCommands.editor(this, setup)
 *         .title("{primary}&lARENA COMMANDS")
 *         .onSave(edited -> arenas.save(arena, NamedCommands.encode(edited)))
 *         .open(player);
 *
 * NamedCommands.run(player, setup);
 * }</pre>
 *
 * <h2>The stored form is not ours to choose</h2>
 * ExyliaCommons wrote these with a bare {@code new Gson()} over a Lombok bean,
 * so the field names are that bean's and the nulls are omitted:
 *
 * <pre>{@code
 * [{"id":"…","name":"Welcome kit","command":"give %player_name% bread 3"}]
 * }</pre>
 *
 * <p>An empty list stores as {@code NULL} rather than {@code []}, for the same
 * reason every other codec in the library does: a column that suddenly held
 * {@code []} would read back the same and would not compare the same.
 *
 * @since 1.56.0
 */
public final class NamedCommands {

    private static final String ID = "id";
    private static final String NAME = "name";
    private static final String COMMAND = "command";

    private NamedCommands() {
        throw new AssertionError("No instances.");
    }

    /**
     * A screen for editing a list of commands.
     *
     * @param plugin   the plugin the screen belongs to
     * @param commands what is being edited; copied, never held
     * @return the editor, ready to open
     */
    public static @NotNull ListEditor<NamedCommand> editor(@NotNull Plugin plugin,
                                                           @NotNull List<NamedCommand> commands) {
        return Editors.of(plugin).list(new NamedCommandDescriptor(plugin),
                NamedCommand.class, commands);
    }

    /**
     * Runs every command in order, as the console.
     *
     * <p>{@code %player%} and {@code %player_name%} are replaced with the
     * player's name before dispatch, which is what every stored command in the
     * ecosystem already writes. Anything else is left to whatever placeholder
     * support the command's own plugin has.
     *
     * <p>Must be called from the thread that owns the player: dispatching a
     * command is running somebody else's plugin, and none of them expect to be
     * called from a scheduler thread.
     *
     * @param player   who the commands are about
     * @param commands the list
     * @return how many were dispatched
     */
    public static int run(@NotNull Player player, @NotNull List<NamedCommand> commands) {
        Objects.requireNonNull(player, "player");
        int dispatched = 0;
        for (String body : NamedCommand.bodies(commands)) {
            String resolved = body
                    .replace("%player_name%", player.getName())
                    .replace("%player%", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
            dispatched++;
        }
        return dispatched;
    }

    /**
     * Writes a list the way the column expects it.
     *
     * @param commands the list
     * @return the JSON array, or {@code null} for an empty list
     */
    public static @Nullable String encode(@NotNull List<NamedCommand> commands) {
        if (commands.isEmpty()) {
            return null;
        }
        JsonArray array = new JsonArray();
        for (NamedCommand command : commands) {
            JsonObject json = new JsonObject();
            json.addProperty(ID, command.id());
            if (command.name() != null) {
                json.addProperty(NAME, command.name());
            }
            if (command.command() != null) {
                json.addProperty(COMMAND, command.command());
            }
            array.add(json);
        }
        return array.toString();
    }

    /**
     * Reads a stored list, ignoring whatever it cannot understand.
     *
     * @param stored the column value, possibly {@code null}
     * @return the commands, never {@code null}
     */
    public static @NotNull List<NamedCommand> decode(@Nullable String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(stored);
        } catch (RuntimeException malformed) {
            return List.of();
        }
        if (!root.isJsonArray()) {
            return List.of();
        }
        List<NamedCommand> commands = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject json = element.getAsJsonObject();
            commands.add(new NamedCommand(
                    string(json, ID) != null ? string(json, ID) : UUID.randomUUID().toString(),
                    string(json, NAME),
                    string(json, COMMAND)));
        }
        return List.copyOf(commands);
    }

    /**
     * Reads the plain {@code List<String>} form that came before names.
     *
     * <p>Every plugin started with one of these, and rows from that era are
     * still out there. Each command becomes an unnamed entry, which reads as
     * itself.
     *
     * @param bodies the commands as written
     * @return one entry per command
     */
    public static @NotNull List<NamedCommand> fromBodies(@Nullable List<String> bodies) {
        if (bodies == null || bodies.isEmpty()) {
            return List.of();
        }
        List<NamedCommand> commands = new ArrayList<>(bodies.size());
        for (String body : bodies) {
            commands.add(NamedCommand.of(null, body));
        }
        return List.copyOf(commands);
    }

    private static @Nullable String string(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element == null || !element.isJsonPrimitive() ? null : element.getAsString();
    }
}
