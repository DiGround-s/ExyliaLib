package net.exylia.lib.util.wizard;

import net.exylia.lib.region.SelectionResult;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * One admin setting one thing, named by what it is rather than by the gesture.
 *
 * <pre>{@code
 * wizards.setup(player, () -> ArenaSetupMenu.open(player))
 *        .spawn("LOBBY SPAWN", arena.id(), where -> save(arena.withLobby(where)));
 * }</pre>
 *
 * <h2>Why this exists on top of the ask shortcuts</h2>
 * {@link PluginWizards#askStand} and {@link PluginWizards#askPoint} are named
 * for what the player <em>does</em>, so choosing between them means knowing why
 * it matters: a spawn is somewhere a player is later put, and a clicked block
 * carries no yaw, so a spawn set by clicking faces whatever direction the
 * plugin decides afterwards. That rule was written down and then not followed
 * &mdash; seven spawns across the ecosystem were set with a pick, and the admins
 * who set them had no way to see what they had lost.
 *
 * <p>These are named for what is being set instead. A spawn asked for through
 * {@link #spawn} cannot come back without a facing, because there is no
 * argument that would make it. The rule is not documented here, it is the only
 * thing that compiles.
 *
 * <h2>The title is built, not passed</h2>
 * A caller hands over a name and, if there is one, the id of what it belongs
 * to. The formatting is this class's:
 *
 * <pre>{@code {primary}&lLOBBY SPAWN {muted}arena_1}</pre>
 *
 * <p>The same reason: passing the whole string invited every plugin to invent
 * its own dialect, and four of them did &mdash; {@code {warning}} on a title
 * that warns about nothing, an icon per plugin, a separator per plugin. A name
 * carrying formatting of its own is rejected rather than quietly rendered, so a
 * dialect cannot come back through the argument.
 *
 * <h2>The way back is not optional</h2>
 * An admin who cancels came from a menu and expects to land in it. Passing that
 * menu is how a {@code Setup} is obtained at all, so no step can forget it
 * &mdash; which is what happened everywhere the old {@code abandoned} argument
 * was filled in with {@code null} and the admin was left staring at nothing.
 * A flow genuinely started from a command, with no menu behind it, says so with
 * {@link PluginWizards#setup(Player)}.
 *
 * @see PluginWizards#setup(Player, Runnable)
 * @since 1.71.0
 */
public final class Setup {

    private final PluginWizards wizards;
    private final Player player;
    private final Runnable backTo;

    Setup(@NotNull PluginWizards wizards, @NotNull Player player, @Nullable Runnable backTo) {
        this.wizards = wizards;
        this.player = player;
        this.backTo = backTo;
    }

    /**
     * Somewhere a player is later put: a spawn, a lobby, a warp, a return point.
     *
     * <p>Answered by standing there and sneak-clicking, so the answer carries
     * the admin's facing.
     *
     * @param name     what is being set, such as {@code "LOBBY SPAWN"}
     * @param accepted told where they stood, on the player's thread
     * @return the running flow
     * @throws WizardException when the name is blank or carries formatting
     */
    public @NotNull WizardRun spawn(@NotNull String name, @NotNull Consumer<Location> accepted) {
        return spawn(name, null, accepted);
    }

    /**
     * Somewhere a player is later put, belonging to something with an id.
     *
     * @param name     what is being set, such as {@code "PORTAL RETURN"}
     * @param context  what it belongs to, such as an arena or portal id
     * @param accepted told where they stood, on the player's thread
     * @return the running flow
     * @throws WizardException when the name is blank or carries formatting
     */
    public @NotNull WizardRun spawn(@NotNull String name, @Nullable String context,
                                    @NotNull Consumer<Location> accepted) {
        return wizards.askStand(player, title(name, context), accepted, backTo);
    }

    /**
     * A block that stays a block: a spawner, a core, a pedestal, a trigger.
     *
     * <p>Answered by clicking it. Use {@link #spawn} for anywhere a player is
     * later stood, which a block cannot describe.
     *
     * @param name     what is being set, such as {@code "CORE BLOCK"}
     * @param accepted told which block, on the player's thread
     * @return the running flow
     * @throws WizardException when the name is blank or carries formatting
     */
    public @NotNull WizardRun block(@NotNull String name, @NotNull Consumer<Location> accepted) {
        return block(name, null, accepted);
    }

    /**
     * A block that stays a block, belonging to something with an id.
     *
     * @param name     what is being set
     * @param context  what it belongs to, such as a spawner or zone id
     * @param accepted told which block, on the player's thread
     * @return the running flow
     * @throws WizardException when the name is blank or carries formatting
     */
    public @NotNull WizardRun block(@NotNull String name, @Nullable String context,
                                    @NotNull Consumer<Location> accepted) {
        return wizards.askPoint(player, title(name, context), accepted, backTo);
    }

    /**
     * A volume, selected with the shared block selector.
     *
     * @param name     what is being set, such as {@code "ARENA BOUNDS"}
     * @param accepted told what they selected, on the player's thread
     * @return the running flow
     * @throws WizardException when the name is blank or carries formatting
     */
    public @NotNull WizardRun area(@NotNull String name,
                                   @NotNull Consumer<SelectionResult> accepted) {
        return area(name, null, accepted);
    }

    /**
     * A volume, belonging to something with an id.
     *
     * @param name     what is being set
     * @param context  what it belongs to, such as an arena or zone id
     * @param accepted told what they selected, on the player's thread
     * @return the running flow
     * @throws WizardException when the name is blank or carries formatting
     */
    public @NotNull WizardRun area(@NotNull String name, @Nullable String context,
                                   @NotNull Consumer<SelectionResult> accepted) {
        return wizards.askRegion(player, title(name, context), accepted, backTo);
    }

    /**
     * The one title format, built rather than accepted.
     *
     * <p>Upper-cased here rather than asked for in upper case: a name that is
     * only conventionally shouted is a name that is eventually not, and the
     * point of this class is that the wrong thing cannot be written.
     *
     * @param name    what is being set
     * @param context what it belongs to, or {@code null}
     * @return the title, in library notation
     * @throws WizardException when either carries formatting or the name is blank
     */
    static String title(@NotNull String name, @Nullable String context) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new WizardException("A setup step needs a name, so the progress bar can say "
                    + "what is being set.");
        }
        plain("name", name);
        String title = "{primary}&l" + name.trim().toUpperCase(Locale.ROOT);
        if (context == null || context.isBlank()) {
            return title;
        }
        plain("context", context);
        return title + " {muted}" + context.trim();
    }

    /**
     * Refuses an argument that brought its own colours.
     *
     * <p>Rendering it anyway is how the dialects started: one plugin's
     * {@code {warning}} title, another's icon, a third's separator. There is one
     * format and this class writes it, so an argument that tries to write it
     * too is a wiring mistake and says so.
     */
    private static void plain(String what, String value) {
        if (FORMATTING.matcher(value).find()) {
            throw new WizardException("The " + what + " of a setup step is plain text: '" + value
                    + "' carries formatting. The title is built from it — pass "
                    + "\"LOBBY SPAWN\", not a coloured string.");
        }
    }

    /** Colour tokens, MiniMessage tags and legacy codes. */
    private static final Pattern FORMATTING = Pattern.compile("\\{[^}]*}|<[^>]*>|[&§].");

    @Override
    public String toString() {
        return "Setup[" + player.getName() + ']';
    }
}
