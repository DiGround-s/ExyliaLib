package net.exylia.lib.util.wizard.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.effect.PluginEffects;
import net.exylia.lib.input.PluginInputs;
import net.exylia.lib.region.PluginRegions;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.util.wizard.Wizard;
import net.exylia.lib.util.wizard.WizardOutcome;
import net.exylia.lib.util.wizard.WizardRun;
import net.exylia.lib.util.wizard.WizardSettings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Every wizard run on the server, and everything that can end one.
 *
 * <h2>One per player, across every plugin</h2>
 * A second run for the same player ends the first as
 * {@link WizardOutcome#REPLACED} rather than stacking. Two live flows would each
 * be waiting on that player's one active input, so the second question asked
 * would answer the first flow's step and the first flow would then answer the
 * second's &mdash; the two would trade answers and neither would produce
 * anything a plugin could use.
 *
 * <p>This is deliberately the same rule as {@code InputRuntime}'s single active
 * request, for the same reason and with the same outcome name. A wizard is a
 * chain of inputs; a second chain competing for the same slot is exactly the
 * situation the input module already refuses.
 *
 * <h2>Everything that can end one</h2>
 * The player confirming, cancelling, walking away until the run timeout fires,
 * quitting, the owning plugin disabling, the server stopping, another run
 * replacing it, or a callback in the definition throwing. Each releases
 * everything the run held: the pending question, the block selector, the
 * progress bar, and the slot in this map.
 */
@ApiStatus.Internal
public final class WizardRuntime {

    private static final ConcurrentMap<UUID, WizardSession> ACTIVE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, String> OWNERS = new ConcurrentHashMap<>();

    /**
     * What a hand step reads. A seam rather than a direct call so a test can
     * answer a hand step without standing up an inventory: everything else in
     * a run can be driven through the scheduler and the input module, and this
     * was the one place that could not.
     */
    private static volatile Function<Player, ItemStack> hands =
            player -> player.getInventory().getItemInMainHand();

    private static volatile boolean listening;

    private WizardRuntime() {
        throw new AssertionError("No instances.");
    }

    /**
     * Registers the module's single listener, once, against the library itself.
     *
     * <p>Against the library rather than against each consumer: a listener per
     * plugin would mean several handlers racing for the same block click, and
     * the run that owns the click is found by player, not by plugin.
     *
     * @param library ExyliaLib's own plugin instance
     */
    public static synchronized void init(@NotNull Plugin library) {
        java.util.Objects.requireNonNull(library, "library");
        if (listening) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(new WizardListener(), library);
        listening = true;
    }

    /**
     * Starts a run, ending whatever that player already had.
     *
     * @param plugin     the plugin that declared the flow
     * @param player     who to walk through it
     * @param wizard     the compiled definition
     * @param tasks      the owning plugin's scheduler
     * @param debug      the owning plugin's console
     * @param inputs     the owning plugin's request factory
     * @param effects    the owning plugin's effect factory, for the progress bar
     * @param regions    the owning plugin's region facade, resolved only if a
     *                   region step is actually reached
     * @param settings   the limits in force when the run started
     * @param afterwards what to do when it ends, however it ends
     * @return the running flow
     */
    public static @NotNull WizardRun start(@NotNull Plugin plugin, @NotNull Player player,
                                            @NotNull Wizard wizard, @NotNull TaskScheduler tasks,
                                            @NotNull Debug debug, @NotNull PluginInputs inputs,
                                            @NotNull PluginEffects effects,
                                            @NotNull Supplier<PluginRegions> regions,
                                            @NotNull WizardSettings settings,
                                            @Nullable Runnable afterwards) {
        UUID id = player.getUniqueId();

        // The session needs a release callback that names the session, and the
        // session does not exist until the callback does. The holder is what
        // ties that knot; it is written once, before anything can read it.
        AtomicReference<WizardSession> self = new AtomicReference<>();
        WizardSession session = new WizardSession(plugin, player, wizard, tasks, debug, inputs,
                effects, regions, settings, afterwards, () -> {
            // Conditional on purpose. A run that ends because a newer one
            // replaced it must not remove the newer one's entry: an
            // unconditional remove would clear the slot a live flow depends on
            // and let a third flow start alongside it.
            if (ACTIVE.remove(id, self.get())) {
                OWNERS.remove(id);
            }
        });
        self.set(session);

        // Claimed before the old run is told, so the displaced run's own
        // cleanup already sees the new session in the map and leaves it alone.
        WizardSession previous = ACTIVE.put(id, session);
        OWNERS.put(id, plugin.getName());
        if (previous != null && previous != session) {
            previous.end(WizardOutcome.REPLACED);
        }

        session.start();
        return session;
    }

    /**
     * Whether this player is in a flow.
     *
     * @param player the player's id
     * @return whether a run is active
     */
    public static boolean isRunning(@NotNull UUID player) {
        return ACTIVE.containsKey(player);
    }

    /**
     * This player's run, if any.
     *
     * @param player the player's id
     * @return the run, or empty
     */
    public static @NotNull Optional<WizardRun> running(@NotNull UUID player) {
        return Optional.ofNullable(ACTIVE.get(player));
    }

    /**
     * How many runs are active.
     *
     * @return the count
     */
    public static int active() {
        return ACTIVE.size();
    }

    /**
     * Ends a leaving player's run.
     *
     * @param player the player's id
     */
    public static void forget(@NotNull UUID player) {
        WizardSession session = ACTIVE.get(player);
        if (session != null) {
            session.end(WizardOutcome.DISCONNECTED);
        }
    }

    /**
     * Ends every run one plugin started.
     *
     * <p>Called when that plugin is disabled, before its scheduler goes away:
     * ending a run schedules the reopen callback on it, and a run left alive
     * would hold a future the dying plugin is waiting on.
     *
     * @param pluginName the plugin's name
     * @return how many were ended
     */
    public static int endAllOf(@NotNull String pluginName) {
        int ended = 0;
        for (Map.Entry<UUID, String> entry : Map.copyOf(OWNERS).entrySet()) {
            if (!entry.getValue().equals(pluginName)) {
                continue;
            }
            WizardSession session = ACTIVE.get(entry.getKey());
            if (session != null && session.end(WizardOutcome.SHUT_DOWN)) {
                ended++;
            }
        }
        return ended;
    }

    /** Ends every run on the server, on shutdown. */
    public static void endEverything() {
        for (WizardSession session : List.copyOf(ACTIVE.values())) {
            session.end(WizardOutcome.SHUT_DOWN);
        }
        ACTIVE.clear();
        OWNERS.clear();
    }

    /**
     * Restores an empty runtime for isolated tests.
     *
     * <p>Public only because the tests that need it live in the package next
     * door; nothing outside the library should call it.
     */
    @ApiStatus.Internal
    public static synchronized void resetForTests() {
        for (WizardSession session : List.copyOf(ACTIVE.values())) {
            session.end(WizardOutcome.SHUT_DOWN);
        }
        ACTIVE.clear();
        OWNERS.clear();
        hands = player -> player.getInventory().getItemInMainHand();
        listening = false;
    }

    /** The session a listener or a test should route an event to. */
    static @Nullable WizardSession sessionOf(@NotNull UUID player) {
        return ACTIVE.get(player);
    }

    /**
     * Test seam: answers a pick step without a Bukkit event.
     *
     * @return {@code true} when a run was waiting for a block
     */
    static boolean pick(@NotNull UUID player, @NotNull Location where) {
        WizardSession session = ACTIVE.get(player);
        return session != null && session.locationPicked(where);
    }

    /** Test seam: replaces what a hand step reads. */
    static void installHands(@NotNull Function<Player, ItemStack> replacement) {
        hands = java.util.Objects.requireNonNull(replacement, "replacement");
    }

    /** What a hand step reads, through whatever seam is installed. */
    static @Nullable ItemStack heldBy(@NotNull Player player) {
        return hands.apply(player);
    }
}
