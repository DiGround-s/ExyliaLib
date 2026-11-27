package net.exylia.lib.clientmob;

import net.exylia.lib.clientmob.internal.MobRuntime;
import net.exylia.lib.interaction.Click;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Mobs of any vanilla type that one player sees and clicks, sent as packets:
 * the helpers that walk between a player's crops, a pet that follows them.
 * Since 1.634.0.
 *
 * <pre>{@code
 * ClientMobs mobs = ClientMobs.of(this)
 *         .range(32)
 *         .onClick((player, mob, click) -> pet(player, mob));
 *
 * ClientMob golem = mobs.spawn(player, EntityType.IRON_GOLEM, spot);
 * golem.moveTo(crop, 2.5).thenRun(golem::swing);
 * }</pre>
 *
 * <p>Needs PacketEvents. Without it every mob is created and does nothing, and
 * {@link #isAvailable()} says so.
 */
public final class ClientMobs {

    private final MobRuntime.Owner owner;

    private ClientMobs(MobRuntime.Owner owner) {
        this.owner = owner;
    }

    /**
     * This plugin's mobs. Every call shares one handler and one range; a
     * plugin's mobs go away when it is disabled.
     */
    public static @NotNull ClientMobs of(@NotNull Plugin plugin) {
        return new ClientMobs(MobRuntime.owner(plugin));
    }

    /**
     * What happens when a viewer clicks one of their mobs. Runs on the viewer's
     * own thread, only for clicks from within reach.
     *
     * @param handler told about each click, or {@code null} to stop listening
     * @return this
     */
    public @NotNull ClientMobs onClick(@Nullable Handler handler) {
        owner.handler(handler);
        return this;
    }

    /**
     * How close a viewer must stand to be sent a mob. Defaults to 32 blocks.
     *
     * @return this
     */
    public @NotNull ClientMobs range(double blocks) {
        owner.range(blocks);
        return this;
    }

    /**
     * A mob only {@code viewer} sees.
     *
     * @param viewer who sees it
     * @param type   any spawnable type except {@link EntityType#PLAYER}
     * @param at     where it stands, facing the location's yaw and pitch
     * @throws IllegalArgumentException for a type that cannot be spawned or a location without a world
     */
    public @NotNull ClientMob spawn(@NotNull Player viewer, @NotNull EntityType type, @NotNull Location at) {
        return MobRuntime.create(owner, viewer, type, at);
    }

    /**
     * A real server entity that only {@code viewer} sees, driven through the same {@link ClientMob} calls. Its
     * vanilla AI is off; {@link ClientMob#moveTo} walks a path the server finds, with gravity and collisions, so it
     * steps up blocks and goes round walls, and a walk that gets stuck ends with a teleport to its target. Flying
     * types (allay, bee, parrot, vex, bat, blaze, ghast, phantom) fly a straight line with no gravity. It is
     * invulnerable, silent, never saved, and tramples nothing. Clicks and the outline colour still go through
     * packets, so {@link #onClick} and {@link ClientMob#clickThrough} work the same.
     *
     * <p>For companions that walk among blocks (pets, helpers): the packet mob of {@link #spawn} floats through
     * whatever is in its way. Since 1.679.0.
     *
     * @param viewer who sees it
     * @param type   a mob type (a {@link org.bukkit.entity.Mob})
     * @param at     where it appears
     * @throws IllegalArgumentException for a type that is not a spawnable mob or a location without a world
     */
    public @NotNull ClientMob spawnReal(@NotNull Player viewer, @NotNull EntityType type, @NotNull Location at) {
        return MobRuntime.createReal(owner, viewer, type, at);
    }

    /**
     * Removes every mob this plugin made for one player.
     *
     * @return how many were removed
     */
    public int clear(@NotNull Player viewer) {
        return MobRuntime.clear(owner, viewer);
    }

    /** Whether mobs reach players here: PacketEvents is installed and loaded. */
    public static boolean isAvailable() {
        return MobRuntime.isAvailable();
    }

    /** Removes every mob a plugin made. ExyliaLib calls this when the plugin is disabled. */
    public static void release(@NotNull Plugin plugin) {
        MobRuntime.release(plugin.getName());
    }

    /** Told about a click on a mob. */
    @FunctionalInterface
    public interface Handler {

        /**
         * @param viewer the player who clicked, on their own thread
         * @param mob    the mob they clicked
         * @param click  which button, and whether they were sneaking
         */
        void clicked(@NotNull Player viewer, @NotNull ClientMob mob, @NotNull Click click);
    }
}
