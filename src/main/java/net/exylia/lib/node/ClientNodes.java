package net.exylia.lib.node;

import net.exylia.lib.interaction.Click;
import net.exylia.lib.node.internal.NodeRuntime;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Blocks and items that one player sees and clicks, sent as packets: the crops
 * of a farm only its owner works, ores a player mines alone, a block that pops
 * when hit. Since 1.628.0.
 *
 * <pre>{@code
 * ClientNodes nodes = ClientNodes.of(this)
 *         .range(32)
 *         .onClick((player, node, click) -> harvest(player, node));
 *
 * ClientNode wheat = nodes.block(player, slot, Material.WHEAT.createBlockData());
 * wheat.glow(Color.YELLOW);
 * wheat.pop();
 * }</pre>
 *
 * <p>Needs PacketEvents. Without it every node is created and does nothing,
 * and {@link #isAvailable()} says so.
 */
public final class ClientNodes {

    private final NodeRuntime.Owner owner;

    private ClientNodes(NodeRuntime.Owner owner) {
        this.owner = owner;
    }

    /**
     * This plugin's nodes. Every call shares one handler and one range; a
     * plugin's nodes go away when it is disabled.
     *
     * @param plugin the plugin that owns them
     * @return its nodes
     */
    public static @NotNull ClientNodes of(@NotNull Plugin plugin) {
        return new ClientNodes(NodeRuntime.owner(plugin));
    }

    /**
     * What happens when a viewer clicks one of their nodes. Runs on the
     * viewer's own thread, only for clicks from within reach of the node.
     *
     * @param handler told about each click, or {@code null} to stop listening
     * @return this
     */
    public @NotNull ClientNodes onClick(@Nullable Handler handler) {
        owner.handler(handler);
        return this;
    }

    /**
     * How close a viewer must stand to be sent a node. Defaults to 32 blocks.
     *
     * @param blocks the range in blocks
     * @return this
     */
    public @NotNull ClientNodes range(double blocks) {
        owner.range(blocks);
        return this;
    }

    /**
     * A block only {@code viewer} sees, filling the block at {@code at}.
     *
     * @param viewer who sees it
     * @param at     the block it fills; only the block coordinates count
     * @param look   what it looks like
     * @return the node
     */
    public @NotNull ClientNode block(@NotNull Player viewer, @NotNull Location at, @NotNull BlockData look) {
        Location corner = new Location(at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
        return NodeRuntime.create(owner, viewer, corner, look);
    }

    /**
     * An item only {@code viewer} sees, floating with its centre at {@code at}.
     *
     * @param viewer who sees it
     * @param at     its centre
     * @param look   what it looks like
     * @return the node
     */
    public @NotNull ClientNode item(@NotNull Player viewer, @NotNull Location at, @NotNull ItemStack look) {
        return NodeRuntime.create(owner, viewer, at.clone(), look.clone());
    }

    /**
     * Removes every node this plugin made for one player.
     *
     * @param viewer the player
     * @return how many were removed
     */
    public int clear(@NotNull Player viewer) {
        return NodeRuntime.clear(owner, viewer);
    }

    /** Whether nodes reach players here: PacketEvents is installed and loaded. */
    public static boolean isAvailable() {
        return NodeRuntime.isAvailable();
    }

    /**
     * Removes every node a plugin made. ExyliaLib calls this when the plugin is
     * disabled.
     *
     * @param plugin the plugin
     */
    public static void release(@NotNull Plugin plugin) {
        NodeRuntime.release(plugin.getName());
    }

    /** Told about a click on a node. */
    @FunctionalInterface
    public interface Handler {

        /**
         * @param viewer the player who clicked, on their own thread
         * @param node   the node they clicked
         * @param click  which button, and whether they were sneaking
         */
        void clicked(@NotNull Player viewer, @NotNull ClientNode node, @NotNull Click click);
    }
}
