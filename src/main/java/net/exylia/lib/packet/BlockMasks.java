package net.exylia.lib.packet;

import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Blocks that only players close to them see as they are: everyone farther away is sent another
 * block in their place, and none of the block's entity data.
 *
 * <p>An anti-ESP for spawners, chests or anything else a modified client would look for through the
 * walls. The swap happens on every packet that carries the block: the chunk itself (its block and its
 * block entity are rewritten before it leaves, so the real block is never on the wire), single and
 * multi block changes, block entity data, block actions and world events at the position. Walking
 * within the distance sends the real block and its block entity; walking back out sends the mask again.
 *
 * <pre>{@code
 * BlockMasks masks = Packets.of(this).blockMasks();
 * masks.mask(spawner.getLocation(), Material.STONE.createBlockData(), 24);
 * // the spawner is broken
 * masks.unmask(spawner.getLocation());
 * }</pre>
 *
 * <h2>Limits</h2>
 * Nothing changes on the server. Keep the distance above the reach of a player (8 blocks or more), so
 * nobody can mine or click a block they see as something else. Who is near is checked twice a second,
 * so someone flying fast sees the mask for a moment as they arrive. A position unmasked by the plugin
 * is sent as it really is to everyone in the world who was seeing the mask. Lighting is the client's
 * own: a mask that blocks light where the real block did not leaves its shadow on the client.
 *
 * @since 1.434.0
 */
public interface BlockMasks {

    /**
     * Masks a block, or changes its mask. Players farther than {@code revealDistance} from it see
     * {@code shown} from now on, and players already that far are sent it at once.
     *
     * @param at             the block
     * @param shown          what the far players see
     * @param revealDistance blocks from the block's centre within which a player sees it as it is
     */
    void mask(@NotNull Location at, @NotNull BlockData shown, double revealDistance);

    /** Stops masking a block and sends it as it is to whoever was seeing the mask. */
    void unmask(@NotNull Location at);

    /** Stops masking every block this plugin masked. */
    void unmaskAll();

    /** Whether this plugin masks the block. */
    boolean isMasked(@NotNull Location at);

    /** Whether the block is masked and this player, being close, sees it as it is. */
    boolean isRevealed(@NotNull Player viewer, @NotNull Location at);
}
