package net.exylia.lib.util.wizard;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.effect.PluginEffects;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.input.PluginInputs;
import net.exylia.lib.region.PluginRegions;
import net.exylia.lib.region.Regions;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.wizard.internal.WizardRuntime;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * One plugin's view of the wizard module.
 *
 * <pre>{@code
 * private PluginWizards wizards;
 * private Wizard arena;
 *
 * public void onEnable() {
 *     wizards = Wizards.of(this).using(config.get().wizard());
 *     arena = wizards.define("arena")
 *             .title("{primary}&lNEW ARENA")
 *             .ask(ID, step -> step.id("Enter the arena id"))
 *             .summary()
 *             .onFinish(this::createArena)
 *             .build();
 * }
 *
 * // From a menu button:
 * wizards.start(player, arena, () -> openMenu(player));
 * }</pre>
 *
 * <h2>Per plugin, not static</h2>
 * The scheduler a run hops onto, the console that hears about a step that threw,
 * the questions it asks and the boss bar it draws all belong to the plugin that
 * declared the flow. A static entry point would have to guess which of them, and
 * would guess wrong the moment a second Exylia plugin defined a wizard &mdash;
 * which is exactly how the old static-map approach behaved on a live server.
 *
 * @since 1.34.0
 */
public final class PluginWizards {

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final Debug debug;
    private final PluginInputs inputs;
    private final PluginEffects effects;

    private volatile WizardSettings settings = new WizardSettings();

    /**
     * Resolved on first use rather than in the constructor: the region module
     * validates the namespace it derives from the plugin's name, and a plugin
     * that never declares a region step should not be able to fail here for a
     * name it is not using.
     */
    private volatile PluginRegions regions;

    PluginWizards(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
        this.debug = Debug.of(plugin);
        this.inputs = Inputs.of(plugin);
        this.effects = Effects.of(plugin);
    }

    /**
     * The plugin these belong to.
     *
     * @return the owning plugin
     */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /**
     * Uses this plugin's own configured limits.
     *
     * <p>Without it, the defaults apply, which suit most flows. A flow whose
     * steps genuinely take a while &mdash; picking blocks across an arena
     * &mdash; wants a longer run timeout than the default five minutes.
     *
     * <p>Applies to runs started after this call. A live run keeps the settings
     * it began with, so a reload cannot move a deadline a player is already
     * racing.
     *
     * @param settings the limits
     * @return this
     */
    public @NotNull PluginWizards using(@NotNull WizardSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        return this;
    }

    /**
     * The limits in force.
     *
     * @return the settings
     */
    public @NotNull WizardSettings settings() {
        return settings;
    }

    /**
     * Starts declaring a flow.
     *
     * <p>Do this once, when the plugin reads its configuration, and keep the
     * {@link Wizard} it produces in a field. Compiling per command would re-run
     * every validation and allocate every lambda for a flow that has not
     * changed since the server started.
     *
     * @param id what to call it, in logs and diagnostics
     * @return the builder
     * @throws WizardException when the id is blank
     */
    public @NotNull WizardBuilder define(@NotNull String id) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new WizardException("A wizard needs an id, so a log line can name it.");
        }
        return new WizardBuilder(id, settings.progress());
    }

    /**
     * Walks a player through a flow.
     *
     * <p>Anything that player already had is ended first, as
     * {@link WizardOutcome#REPLACED}.
     *
     * @param player who to walk through it
     * @param wizard what to walk them through
     * @return the running flow
     */
    public @NotNull WizardRun start(@NotNull Player player, @NotNull Wizard wizard) {
        return start(player, wizard, null);
    }

    /**
     * Walks a player through a flow and does something afterwards.
     *
     * <p>The callback runs however the flow ends &mdash; finished, cancelled,
     * timed out or interrupted &mdash; so a menu that opened one is reopened
     * either way. It never runs for a player who is no longer online.
     *
     * <p>Runs on the thread that owns the player, a tick after the flow ends, so
     * whatever window the last step was asked in has already closed.
     *
     * @param player     who to walk through it
     * @param wizard     what to walk them through
     * @param afterwards what to do when it ends, such as reopening a menu
     * @return the running flow
     */
    public @NotNull WizardRun start(@NotNull Player player, @NotNull Wizard wizard,
                                     @Nullable Runnable afterwards) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(wizard, "wizard");
        return WizardRuntime.start(plugin, player, wizard, tasks, debug, inputs, effects,
                this::regions, settings, afterwards);
    }

    /**
     * Ends every flow this plugin started.
     *
     * @return how many were ended
     */
    public int endAll() {
        return WizardRuntime.endAllOf(plugin.getName());
    }

    /**
     * This plugin's region facade, resolved once.
     *
     * @return the facade a region step selects with
     */
    @ApiStatus.Internal
    public @NotNull PluginRegions regions() {
        PluginRegions current = regions;
        if (current == null) {
            // Racing callers may each build one; they are equivalent and the
            // module's own registry hands back the same underlying runtime, so
            // the extra allocation is cheaper than a lock on a hot path that
            // is not hot at all.
            current = Regions.of(plugin);
            regions = current;
        }
        return current;
    }

    @Override
    public String toString() {
        return "PluginWizards[" + plugin.getName() + ']';
    }
}
