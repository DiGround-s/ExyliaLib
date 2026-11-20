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
 * Since 1.632.0.
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
