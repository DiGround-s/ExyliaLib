package net.exylia.lib.internal;

import net.exylia.lib.web.internal.WebEditorRuntime;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.CommandPlaceholder;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Single;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.annotation.Suggest;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.autocomplete.SuggestionProvider;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;
import revxrsal.commands.node.ExecutionContext;

import java.util.Collection;

/**
 * {@code /exylialib editor}: the browser editor, from in game.
 *
 * <p>Its own permission, {@code exylialib.editor}, rather than {@code exylialib.admin}: editing
 * what the plugins are configured with is a different trust than reloading the library, and a
 * server may hand one without the other. Each subject can narrow it further with a permission
 * of its own.
 */
@Command("exylialib editor")
@CommandPermission("exylialib.editor")
public final class EditorCommand {

    /** The word that applies a change the preview held back. */
    static final String CONFIRM = "confirm";

    /**
     * Opens a session and sends the link.
     *
     * @param plugin one plugin's subjects only; every subject the sender may edit otherwise
     */
    // @Single: a trailing String is greedy and Lamp would route "apply <code> ..." here as one name.
    @CommandPlaceholder
    public void open(@NotNull CommandSender sender, @Optional @Single @SuggestWith(Editable.class) String plugin) {
        WebEditorRuntime.get().open(sender, plugin == null || plugin.isBlank() ? null : plugin.trim());
    }

    /**
     * Brings down what was saved in the browser and writes it.
     *
     * @param code    the session code from the link
     * @param confirm {@code confirm} to apply what the preview held back
     */
    @Subcommand("apply")
    public void apply(@NotNull CommandSender sender, @NotNull String code,
                      @Optional @Suggest(CONFIRM) String confirm) {
        WebEditorRuntime.get().apply(sender, code.trim(), confirm != null && confirm.trim().equalsIgnoreCase(CONFIRM));
    }

    /**
     * Lets a second browser edit the session.
     *
     * @param code      the session code from the link
     * @param challenge the code that browser shows
     */
    @Subcommand("trust")
    public void trust(@NotNull CommandSender sender, @NotNull String code, @NotNull String challenge) {
        WebEditorRuntime.get().trust(sender, code.trim(), challenge);
    }

    /** The plugins with something to edit. */
    public static final class Editable implements SuggestionProvider<BukkitCommandActor> {

        @Override
        public Collection<String> getSuggestions(@NotNull ExecutionContext<BukkitCommandActor> context) {
            return WebEditorRuntime.get().editablePlugins();
        }
    }
}
