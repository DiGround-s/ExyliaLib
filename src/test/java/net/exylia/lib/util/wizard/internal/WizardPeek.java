package net.exylia.lib.util.wizard.internal;

import net.exylia.lib.util.wizard.WizardResult;
import net.exylia.lib.util.wizard.WizardRun;
import org.bukkit.Location;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Lets the module's tests read a live run without the module growing an API.
 *
 * <p>The seams themselves are package-private on purpose: a run's result stage,
 * the answers it holds and the way a block click is delivered are the module's
 * business, and widening them so a test can look would be widening them for
 * every plugin too. This sits in the package instead, exactly as
 * {@code DebugCapture} sits beside {@code Debug}.
 */
public final class WizardPeek {

    private WizardPeek() {
    }

    /** The stage a caller of {@code start} would have waited on. */
    public static CompletionStage<WizardResult> result(WizardRun run) {
        return ((WizardSession) run).result();
    }

    /** Every answer collected so far, in the order they were collected. */
    public static List<String> answered(WizardRun run) {
        return ((WizardSession) run).answeredNames();
    }

    /** Which plugin's flow this is. */
    public static String pluginName(WizardRun run) {
        return ((WizardSession) run).pluginName();
    }

    /** Answers a pick step without a Bukkit event. */
    public static boolean pick(UUID player, Location where) {
        return WizardRuntime.pick(player, where);
    }

    /** Routes an interaction exactly as the module's listener would. */
    public static boolean interact(UUID player, org.bukkit.block.Block block, boolean sneaking) {
        return WizardRuntime.interact(player, block, sneaking);
    }

    /** Whether a run is registered for this player. */
    public static boolean hasSession(UUID player) {
        return WizardRuntime.sessionOf(player) != null;
    }

    /** The run registered for this player, as a handle. */
    public static WizardRun sessionOf(UUID player) {
        return WizardRuntime.sessionOf(player);
    }

    /** Replaces what a hand step reads, so a test needs no inventory. */
    public static void installHands(java.util.function.Function<org.bukkit.entity.Player,
            org.bukkit.inventory.ItemStack> replacement) {
        WizardRuntime.installHands(replacement);
    }
}
