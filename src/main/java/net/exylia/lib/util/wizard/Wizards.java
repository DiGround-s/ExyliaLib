package net.exylia.lib.util.wizard;

import net.exylia.lib.util.wizard.internal.WizardRuntime;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Walking a player through several questions, and applying nothing until they
 * say so.
 *
 * <pre>{@code
 * static final WizardKey<String> ID    = WizardKey.text("id");
 * static final WizardKey<Long>   SLOTS = WizardKey.integer("slots");
 *
 * PluginWizards wizards = Wizards.of(this);
 *
 * Wizard arena = wizards.define("arena")
 *         .title("{primary}&lNEW ARENA")
 *         .ask(ID,    step -> step.id("Enter the arena id"))
 *         .ask(SLOTS, step -> step.integer("Slots").range(2L, 64L))
 *         .pick(SPAWN, "Click the spawn block")
 *         .summary()
 *         .onFinish(values -> createArena(values))
 *         .build();
 *
 * wizards.start(player, arena, () -> menu.open(player));
 * }</pre>
 *
 * <h2>What this replaces</h2>
 * ExyliaEvents' {@code EventConfigWizard} chained chat prompts by hand. It kept
 * the half-built event in a {@code static Map<UUID, String>}; it copy-pasted the
 * same cancel branch into every step; and it had no way back, so a player who
 * mistyped the id in step one had to abandon the flow and start again. Every one
 * of those is a consequence of the same missing idea: there was no object that
 * <em>was</em> the flow, only a chain of callbacks that each knew the next one.
 *
 * <p>Here the flow is a {@link Wizard}, compiled once and shared, and one
 * player's pass through it is a {@link WizardRun}, which nobody outside the
 * module can reach into.
 *
 * <h2>What it guarantees</h2>
 * <ul>
 *   <li><b>Nothing happens until they confirm.</b> With a {@code summary()}, the
 *       finish callback runs once, after the review, and never for a run that
 *       ended any other way. A player who disconnects at step five leaves no
 *       trace at all.</li>
 *   <li><b>There is a way back.</b> Denying the summary lists the answers and
 *       lets one be redone, then shows the summary again. It is built from a
 *       confirm and a choice, so it works identically in a dialog, a Bedrock
 *       form, an anvil, a menu and in chat.</li>
 *   <li><b>Cancelling is one path.</b> Not a branch per step: every ending goes
 *       through the same cleanup, which cancels the pending question, releases
 *       the block selector, stops the bar, and runs the reopen callback exactly
 *       once.</li>
 *   <li><b>One per player, across every plugin.</b> A second flow ends the first
 *       as {@link WizardOutcome#REPLACED}, mirroring the one-active-input rule
 *       the {@code input} module already enforces &mdash; two flows asking the
 *       same player at once would each answer the other's questions.</li>
 *   <li><b>Nothing outlives its run.</b> Quitting, the plugin disabling, the
 *       server stopping and a run timeout each end it, and each releases
 *       everything it held.</li>
 * </ul>
 *
 * <h2>Reload</h2>
 * Nothing derived from the palette is cached. Every prompt, summary line and
 * progress bar is built through {@code Text} at the moment it is shown, so a
 * palette reload is picked up by whatever is drawn next with no help from this
 * module. It therefore has no {@code invalidateAll()} and is deliberately absent
 * from the palette-reload chain in {@code ExyliaLib.loadPalette}.
 *
 * @since 1.34.0
 */
public final class Wizards {

    private static final Map<String, PluginWizards> BY_PLUGIN = new ConcurrentHashMap<>();

    private Wizards() {
        throw new AssertionError("No instances.");
    }

    /**
     * This plugin's view of the module.
     *
     * @param plugin the plugin
     * @return its view, the same instance every time
     * @throws WizardException when the plugin or its name is unusable
     */
    public static @NotNull PluginWizards of(@NotNull Plugin plugin) {
        if (plugin == null) {
            throw new WizardException("plugin must not be null");
        }
        String name = plugin.getName();
        if (name == null || name.isBlank()) {
            throw new WizardException("plugin needs a non-blank name");
        }
        return BY_PLUGIN.computeIfAbsent(name, ignored -> new PluginWizards(plugin));
    }

    /**
     * Whether this player is being walked through a flow right now.
     *
     * <p>Worth asking before anything that would fight it: another flow, a menu
     * that takes over the screen, a teleport that moves them away from the block
     * they were told to click.
     *
     * @param player the player
     * @return whether they are in a flow
     */
    public static boolean isRunning(@NotNull Player player) {
        return WizardRuntime.isRunning(player.getUniqueId());
    }

    /**
     * The flow this player is in, if any.
     *
     * <p>For a caller that wants to end it rather than just know about it.
     *
     * @param player the player
     * @return their run, or empty
     */
    public static @NotNull Optional<WizardRun> running(@NotNull Player player) {
        return WizardRuntime.running(player.getUniqueId());
    }

    /**
     * How many flows are running across every plugin.
     *
     * @return the count
     */
    public static int active() {
        return WizardRuntime.active();
    }

    /**
     * Ends one plugin's flows and forgets it.
     *
     * <p>Called by the library when the plugin is disabled, before its scheduler
     * goes away: a run ends by scheduling on it. Each run ends as
     * {@link WizardOutcome#SHUT_DOWN}, so a caller waiting on the result is
     * released rather than left holding a stage nothing will ever complete.
     *
     * @param pluginName the plugin's name
     */
    public static void release(@NotNull String pluginName) {
        WizardRuntime.endAllOf(pluginName);
        BY_PLUGIN.remove(pluginName);
    }

    /** Ends every flow on the server, on shutdown. */
    public static void releaseAll() {
        WizardRuntime.endEverything();
        BY_PLUGIN.clear();
    }
}
