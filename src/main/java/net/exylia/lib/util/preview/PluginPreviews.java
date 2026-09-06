package net.exylia.lib.util.preview;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.preview.internal.PreviewRuntime;
import net.exylia.lib.util.sequence.Sequence;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * One plugin's view of the preview module.
 *
 * <pre>{@code
 * private PluginPreviews previews;
 *
 * public void onEnable() {
 *     previews = Previews.of(this).using(() -> config.get().preview());
 * }
 *
 * // From a menu button:
 * if (previews.available()) {
 *     previews.show(player, effect.sequence(), () -> openMenu(player));
 * } else {
 *     tellThemToSetTheStage(player);
 * }
 * }</pre>
 *
 * @since 1.30.0
 */
public final class PluginPreviews {

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final Debug debug;
    private volatile Supplier<PreviewSettings> settings = PreviewSettings::new;

    PluginPreviews(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
        this.debug = Debug.of(plugin);
    }

    /** The plugin these belong to. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /**
     * Uses this plugin's own configured stage.
     *
     * <p>A fixed snapshot. Prefer {@link #using(Supplier)} for settings that
     * come from a config file, or a reload leaves previews on the old stage.
     *
     * @param settings where the stage sits
     * @return this
     */
    public @NotNull PluginPreviews using(@NotNull PreviewSettings settings) {
        this.settings = () -> settings;
        return this;
    }

    /**
     * Reads this plugin's stage settings afresh every time one is needed.
     *
     * <p>What a config-backed plugin wants: a reload, or an admin moving the
     * stage, is picked up without anyone remembering to re-register.
     *
     * @param settings where the stage sits, read on each preview
     * @return this
     */
    public @NotNull PluginPreviews using(@NotNull Supplier<PreviewSettings> settings) {
        this.settings = settings;
        return this;
    }

    /** The stage settings in force. */
    public @NotNull PreviewSettings settings() {
        return settings.get();
    }

    /**
     * Whether this plugin has a stage to preview on.
     *
     * <p>False until a location is configured, and false when the configured
     * world is not loaded. Ask before offering a preview, so the player is told
     * why rather than clicking a button that does nothing.
     *
     * @return whether {@link #show} would work
     */
    public boolean available() {
        return settings().stage() != null;
    }

    /**
     * Shows a player an effect, against nothing.
     *
     * <p>Anything that player already had is ended first. The effect is visible
     * to them alone.
     *
     * @param viewer   who to show it to
     * @param sequence what to show
     * @return the running preview, or {@code null} when there is no stage
     */
    public @Nullable Preview show(@NotNull Player viewer, @NotNull Sequence sequence) {
        return show(viewer, sequence, null);
    }

    /**
     * Shows a player an effect and does something afterwards.
     *
     * <p>The callback runs however the preview ends &mdash; finished, cancelled
     * or interrupted &mdash; so a menu that opened one is reopened either way.
     * It never runs for a player who is no longer online, and it never runs at
     * all when there was no stage to show them: nothing happened, so there is
     * nothing to come back from.
     *
     * @param viewer     who to show it to
     * @param sequence   what to show
     * @param afterwards what to do when it ends, such as reopening a menu
     * @return the running preview, or {@code null} when there is no stage
     */
    public @Nullable Preview show(@NotNull Player viewer, @NotNull Sequence sequence,
                                  @Nullable Runnable afterwards) {
        PreviewSettings current = settings();
        Location stage = current.stage();
        if (stage == null) {
            if (current.location().isBlank()) {
                // Nothing is broken: the server owner has not set a stage yet,
                // and the caller tells the player so. Warning on every click
                // would fill the console with a setting somebody chose.
                debug.debug("A preview was asked for before a preview location was set.");
            } else {
                debug.warn("The preview location is set to '" + current.location()
                        + "', which is not a place on this server. Previews are off"
                        + " until it is set again.");
            }
            return null;
        }
        return PreviewRuntime.start(plugin, viewer, sequence, tasks, debug, current, stage, afterwards);
    }

    /**
     * Ends every preview this plugin started.
     *
     * @return how many were ended
     */
    public int endAll() {
        return PreviewRuntime.endAllOf(plugin.getName());
    }

    @Override
    public String toString() {
        return "PluginPreviews[" + plugin.getName() + ']';
    }
}
