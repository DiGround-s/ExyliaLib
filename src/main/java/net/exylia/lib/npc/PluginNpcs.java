package net.exylia.lib.npc;

import net.exylia.lib.npc.internal.NpcRuntime;
import net.exylia.lib.npc.internal.PlacedRuntime;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

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

    private final Plugin plugin;
    private final String pluginName;

    PluginNpcs(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.pluginName = plugin.getName();
    }

    /**
     * Places an NPC that stays until it is removed, seen by whoever comes within its
     * view distance and clickable by them.
     *
     * <pre>{@code
     * PlacedNpc guide = Npcs.of(this).place("guide", NpcSpec.builder()
     *         .skin(NpcSkin.player("Notch"))
     *         .look(NpcLook.EACH_VIEWER)
     *         .build(), location);
     * guide.onClick((player, click) -> openShop(player));
     * }</pre>
     *
     * @param id   a name unique within this plugin; reusing one replaces the NPC under it
     * @param spec what it looks like
     * @param at   where it stands; the yaw and pitch are which way it faces
     * @return the NPC; an inert, already removed one when the server has no PacketEvents
     * @since 1.291.0
     */
    public @NotNull PlacedNpc place(@NotNull String id, @NotNull NpcSpec spec,
                                    @NotNull Location at) {
        return PlacedRuntime.place(plugin, id, spec, at);
    }

    /**
     * An NPC this plugin placed.
     *
     * @param id the id it was placed under
     * @return the NPC, or empty
     * @since 1.291.0
     */
    public @NotNull Optional<PlacedNpc> placed(@NotNull String id) {
        return PlacedRuntime.get(pluginName, id);
    }

    /**
     * Every NPC this plugin placed.
     *
     * @return a copy
     * @since 1.291.0
     */
    public @NotNull List<PlacedNpc> allPlaced() {
        return PlacedRuntime.all(pluginName);
    }

    /**
     * Removes a placed NPC from every screen.
     *
     * @param id the id it was placed under
     * @return whether there was one
     * @since 1.291.0
     */
    public boolean unplace(@NotNull String id) {
        Optional<PlacedNpc> npc = placed(id);
        npc.ifPresent(PlacedNpc::remove);
        return npc.isPresent();
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

    /** Takes away everything this plugin is showing; placed NPCs stay. */
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
