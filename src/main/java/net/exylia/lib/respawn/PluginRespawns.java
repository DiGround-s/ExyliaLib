package net.exylia.lib.respawn;

import net.exylia.lib.respawn.internal.RespawnRuntime;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerRespawnEvent.RespawnReason;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One plugin's respawn rules and callbacks. Get it from {@link Respawns#of}.
 *
 * @since 1.440.0
 */
public final class PluginRespawns {

    private final Plugin plugin;

    PluginRespawns(Plugin plugin) {
        this.plugin = plugin;
    }

    /** The plugin these belong to. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /**
     * Chooses where players respawn.
     *
     * <p>Rules run in priority order, like listeners, each one seeing the
     * previous one's answer as {@link RespawnContext#target()}; the last
     * non-null answer wins. On Paper the rule is a {@code PlayerRespawnEvent}
     * listener at that priority, so it also orders against plain listeners. On
     * Folia the player lands where vanilla put them and is then moved, with no
     * countdown, if the rules chose somewhere else.
     *
     * @param priority when to run, against other rules and respawn listeners
     * @param rule     the rule
     * @param reasons  which respawns it applies to; none means {@code DEATH}
     *                 only. On Folia only {@code DEATH} and {@code END_PORTAL}
     *                 ever happen
     * @return the registration, to cancel it before the plugin is disabled
     */
    public @NotNull RespawnHook location(@NotNull EventPriority priority, @NotNull RespawnRule rule,
                                         @NotNull RespawnReason @NotNull ... reasons) {
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(rule, "rule");
        EnumSet<RespawnReason> applies = reasons.length == 0
                ? EnumSet.of(RespawnReason.DEATH) : EnumSet.copyOf(List.of(reasons));
        RespawnRuntime.Rule added = RespawnRuntime.addRule(plugin, priority, rule, applies);
        return new RespawnHook(() -> RespawnRuntime.remove(added));
    }

    /**
     * Runs code once a player is standing again after any respawn, on their
     * thread, with their final location settled (the rules applied and, on
     * Folia, the move made). Replaces {@code PlayerPostRespawnEvent} and the
     * usual "one tick after the respawn" hop.
     *
     * @param task what to do with the player
     * @return the registration, to cancel it before the plugin is disabled
     */
    public @NotNull RespawnHook after(@NotNull Consumer<Player> task) {
        RespawnRuntime.After added = RespawnRuntime.addAfter(plugin, Objects.requireNonNull(task, "task"));
        return new RespawnHook(() -> RespawnRuntime.remove(added));
    }
}
