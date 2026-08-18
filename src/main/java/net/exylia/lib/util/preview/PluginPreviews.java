package net.exylia.lib.util.preview;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.preview.internal.PreviewRuntime;
import net.exylia.lib.util.sequence.Sequence;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One plugin's view of the preview module.
 *
 * <pre>{@code
 * private PluginPreviews previews;
 *
 * public void onEnable() {
 *     previews = Previews.of(this).using(config.get().preview());
 * }
 *
 * // From a menu button:
 * previews.show(player, effect.sequence(), () -> openMenu(player));
 * }</pre>
 *
 * @since 1.31.0
 */
public final class PluginPreviews {

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final Debug debug;
    private volatile PreviewSettings settings = new PreviewSettings();

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
     * <p>Without it, the defaults apply, which are fine for most servers. A
     * server that builds above y=320 needs its own.
     *
     * @param settings where the stage sits
     * @return this
     */
    public @NotNull PluginPreviews using(@NotNull PreviewSettings settings) {
        this.settings = settings;
        return this;
    }

    /** The stage settings in force. */
    public @NotNull PreviewSettings settings() {
        return settings;
    }

    /**
     * Shows a player an effect, against nothing.
     *
     * <p>Anything that player already had is ended first. The effect is visible
     * to them alone.
     *
     * @param viewer   who to show it to
     * @param sequence what to show
     * @return the running preview
     */
    public @NotNull Preview show(@NotNull Player viewer, @NotNull Sequence sequence) {
        return show(viewer, sequence, null);
    }

    /**
     * Shows a player an effect and does something afterwards.
     *
     * <p>The callback runs however the preview ends &mdash; finished, cancelled
     * or interrupted &mdash; so a menu that opened one is reopened either way.
     * It never runs for a player who is no longer online.
     *
     * @param viewer     who to show it to
     * @param sequence   what to show
     * @param afterwards what to do when it ends, such as reopening a menu
     * @return the running preview
     */
    public @NotNull Preview show(@NotNull Player viewer, @NotNull Sequence sequence,
                                 @Nullable Runnable afterwards) {
        return PreviewRuntime.start(plugin, viewer, sequence, tasks, debug, settings, afterwards);
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
