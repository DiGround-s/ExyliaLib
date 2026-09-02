package net.exylia.lib.npc;

import net.exylia.lib.npc.internal.NpcRuntime;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One plugin's view of the NPC module.
 *
 * <pre>{@code
 * PluginNpcs npcs = Npcs.of(this);
 *
 * npcs.show(NpcModel.of(victim).wearing(victim).pose(NpcPose.LYING),
 *         victim.getLocation(), 4000, nearbyPlayers);
 * }</pre>
 *
 * @since 1.88.2
 */
public final class PluginNpcs {

    private final String pluginName;

    PluginNpcs(@NotNull String pluginName) {
        this.pluginName = pluginName;
    }

    /**
     * Shows one NPC to a list of players.
     *
     * <p>It takes itself away when its life is up, so the handle is only worth
     * keeping if it needs to move, turn or go sooner.
     *
     * @param model      who it looks like
     * @param at         where it stands; the location's yaw is which way it faces
     * @param lifeMillis how long before it goes, from a fifth of a second to two
     *                   minutes
     * @param viewers    who sees it; the list is kept, so hand over one nobody
     *                   else is going to change
     * @return the handle, or {@code null} when nobody can see it or the server
     *         has no PacketEvents
     */
    public @Nullable NpcHandle show(@NotNull NpcModel model, @NotNull Location at,
                                    long lifeMillis, @NotNull List<Player> viewers) {
        return show(model, NpcMotion.still(), at, lifeMillis, viewers);
    }

    /**
     * Shows one NPC that does something once it is there.
     *
     * @param model      who it looks like
     * @param motion     what it does: thrown, slumping, turning, sinking
     * @param at         where it appears; the location's yaw is which way it faces
     * @param lifeMillis how long before it goes
     * @param viewers    who sees it
     * @return the handle, or {@code null}
     */
    public @Nullable NpcHandle show(@NotNull NpcModel model, @NotNull NpcMotion motion,
                                    @NotNull Location at, long lifeMillis,
                                    @NotNull List<Player> viewers) {
        return NpcRuntime.show(pluginName, model, motion, at, lifeMillis, viewers);
    }

    /**
     * The same, for as long as an NPC lasts by default.
     *
     * @param model   who it looks like
     * @param at      where it stands
     * @param viewers who sees it
     * @return the handle, or {@code null}
     */
    public @Nullable NpcHandle show(@NotNull NpcModel model, @NotNull Location at,
                                    @NotNull List<Player> viewers) {
        return show(model, at, NpcRuntime.DEFAULT_LIFE_MILLIS, viewers);
    }

    /** Takes away everything this plugin is showing. */
    public void removeAll() {
        NpcRuntime.release(pluginName);
    }

    /** How many NPCs this plugin has on screen. */
    public int active() {
        return NpcRuntime.active(pluginName);
    }

    /** Whether NPCs can be shown at all on this server. */
    public boolean isSupported() {
        return NpcRuntime.isSupported();
    }
}
