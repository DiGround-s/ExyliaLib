package net.exylia.lib.ragdoll;

import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.ragdoll.internal.RagdollBuilder;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * One plugin's view of the ragdoll module.
 *
 * <p>Obtained from {@link Ragdolls#of}, typically once and kept.
 *
 * @since 1.120.0
 */
public final class PluginRagdolls {

    private final String pluginName;

    PluginRagdolls(@NotNull String pluginName) {
        this.pluginName = pluginName;
    }

    /**
     * Shows a body coming apart where somebody died.
     *
     * <pre>{@code
     * ragdolls.show(RagdollModel.of(victim).detail(2).light(15),
     *         RagdollMotion.standard(), victim.getLocation(), nearby);
     * }</pre>
     *
     * <p>Returns at once. Every piece's whole flight is solved before the call
     * comes back, and nothing is ticked afterwards.
     *
     * @param model   whose body, and how finely cut
     * @param burst   how it comes apart
     * @param at      where they died, standing on the floor they land on
     * @param viewers who sees it; taken as given and not copied again
     * @return the handle
     */
    public @NotNull RagdollHandle show(@NotNull RagdollModel model, @NotNull RagdollMotion burst,
                                       @NotNull Location at, @NotNull List<Player> viewers) {
        List<DisplayHandle> pieces = RagdollBuilder.show(pluginName, model, burst, at, viewers);
        return new RagdollHandle(pieces);
    }
}
