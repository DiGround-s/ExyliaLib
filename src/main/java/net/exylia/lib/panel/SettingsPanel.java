package net.exylia.lib.panel;

import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.panel.internal.SettingsEngine;
import net.exylia.lib.task.Tasks;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * A configuration editor generated from a config record, with no per-record code.
 *
 * <pre>{@code
 * // the whole effects editor
 * Panels.of(this).settings(effectConfigFile).open(player);
 *
 * // with a title and something to do afterwards
 * Panels.of(this).settings(settings)
 *         .title("{primary}&lSERVER SETTINGS")
 *         .onSaved(values -> restartTimer(values.saveIntervalTicks()))
 *         .open(player);
 * }</pre>
 *
 * <h2>What generates it</h2>
 * The record's own {@link net.exylia.lib.config.Schema}. One control per
 * component, chosen from the <em>declared type</em>: whole numbers and decimals
 * get a number control, a {@code boolean} a toggle, an enum a searchable choice
 * over its constants, a {@code String} a text input, a {@code List} a list
 * sub-panel, and a nested record a sub-panel over its own schema. The
 * {@link net.exylia.lib.config.Comment @Comment} lines above each component
 * become its lore, in declaration order — they are already the server owner's
 * manual, and until now only somebody opening the {@code .yml} ever read them.
 *
 * <p>There is nothing to register and nothing to describe. A record this library
 * has never seen edits exactly as well as one it ships, which is why the effects
 * editor is this class pointed at {@code EffectConfig} rather than a screen
 * somebody has to keep in step with the record.
 *
 * <h2>Nothing is written until save</h2>
 * Every edit lands in a working copy. A save rebuilds the whole record first and
 * hands the finished record to {@link ConfigFile#update} — the only write path
 * this panel uses. It never writes YAML and never touches a
 * {@code FileConfiguration}, so migrations, key pruning and comment preservation
 * stay owned by the config module.
 *
 * <p>A save whose {@link PanelSession#diff()} is empty writes nothing at all:
 * opening a panel to look at something must not rewrite the owner's file. A
 * record that refuses its own values — a compact constructor that throws — is
 * refused <em>before</em> the write, so the player is told what the record said
 * and the file is never opened.
 *
 * <h2>Components with no control</h2>
 * A component whose declared type this library cannot edit is drawn read-only
 * with its comment lore, is passed through a save untouched, and is reported
 * <b>once per server</b>. It never prevents opening, editing the other
 * components, or saving: a value nobody can see is a value nobody notices
 * losing, and refusing a whole screen over one field would lose the rest.
 *
 * <h2>Threads</h2>
 * {@link #open(Player)} is safe from <b>any thread</b> and relocates itself onto
 * the thread that owns the player. The write is asynchronous and returns to that
 * thread before anything touches the game. Behaviour is identical on Spigot,
 * Paper, Purpur and Folia.
 *
 * <h2>Lifecycle</h2>
 * A builder is single-use in spirit but reusable in fact: {@link #open(Player)}
 * may be called for several players and each gets their own session. Everything
 * a panel holds is given back when it closes, the viewer leaves, or the owning
 * plugin is disabled.
 *
 * @param <T> the config record being edited
 * @since 1.50.0
 */
public final class SettingsPanel<T extends Record> {

    private final Plugin plugin;
    private final ConfigFile<T> file;

    private @Nullable String title;
    private @Nullable Consumer<T> onSaved;

    @ApiStatus.Internal
    SettingsPanel(@NotNull Plugin plugin, @NotNull ConfigFile<T> file) {
        this.plugin = plugin;
        this.file = file;
    }

    /**
     * Sets the window title.
     *
     * <p>Written in Exylia text notation, so {@code {primary}} rather than a hex
     * value: an owner who retheme the palette rethemes this too. Left unset, the
     * layout's own title is used.
     *
     * @param title the title
     * @return this panel, for chaining
     */
    public @NotNull SettingsPanel<T> title(@NotNull String title) {
        this.title = title;
        return this;
    }

    /**
     * Runs an action after a successful write.
     *
     * <p>Given the record that was persisted, on the thread that owns the
     * viewer — so it is safe to touch the game from it. Use it to rebuild
     * whatever the config feeds, exactly as {@link ConfigFile#onReload} would:
     *
     * <pre>{@code
     * .onSaved(values -> restartTimer(values.saveIntervalTicks()))
     * }</pre>
     *
     * <p>Not run when nothing changed, because nothing was written.
     *
     * @param action what to do with the new values
     * @return this panel, for chaining
     */
    public @NotNull SettingsPanel<T> onSaved(@NotNull Consumer<T> action) {
        this.onSaved = action;
        return this;
    }

    /**
     * Shows the panel to a player.
     *
     * <p>Safe to call from any thread: it relocates itself onto the thread that
     * owns the player, exactly as {@code Menus} does. There is deliberately no
     * {@code openNow} — nothing about a panel needs a session synchronously, and
     * offering one would export a thread precondition into an API whose whole
     * point is that there is nothing to get right.
     *
     * @param viewer who to show it to
     */
    public void open(@NotNull Player viewer) {
        String chosen = title;
        Consumer<T> notify = onSaved;
        Tasks.of(plugin).runAtEntity(viewer,
                () -> SettingsEngine.of(plugin, viewer, file, notify).open(chosen));
    }
}
