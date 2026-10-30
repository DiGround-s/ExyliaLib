package net.exylia.lib.packet;

import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Map;

/**
 * Blocks one player sees and the server does not have.
 *
 * <p>Selection outlines, arena previews, a cage around a frozen player: sent
 * as one packet per chunk section, never placed. The module remembers what
 * each viewer was shown so {@link #clear} can put the real blocks back, and
 * forgets it when the viewer leaves or changes world (the client reloads its
 * chunks anyway).
 *
 * <h2>Limits</h2>
 * The server still treats the position as whatever is really there: a fake
 * wall stops nobody. A chunk the server resends — on a block update nearby,
 * or when the player walks far enough away and back — shows the truth again,
 * unless the blocks were shown with {@link #keep}.
 *
 * @since 1.75.0
 */
public interface FakeBlocks {

    /**
     * Shows blocks to one player.
     *
     * @param viewer the player
     * @param blocks the block data to show at each position; positions in a
     *               world the viewer is not in are ignored
     */
    void show(@NotNull Player viewer, @NotNull Map<Location, BlockData> blocks);

    /**
     * Shows blocks to one player and keeps them shown: a block the server sends at one of these
     * positions later (placed, broken or updated by anyone, or a whole chunk sent again) reaches this
     * viewer as the block given here instead, until {@link #clear}. A position may be kept as air to
     * keep it empty whatever is put there.
     *
     * @param viewer the player
     * @param blocks the block data to show and keep at each position
     * @since 1.420.0
     */
    default void keep(@NotNull Player viewer, @NotNull Map<Location, BlockData> blocks) {
        show(viewer, blocks);
    }

    /**
     * Puts every fake block shown to this viewer back to the truth.
     *
     * @param viewer the player
     */
    void clear(@NotNull Player viewer);

    /**
     * Puts some fake blocks back to the truth.
     *
     * @param viewer    the player
     * @param positions positions to restore; ones never faked are ignored
     */
    void clear(@NotNull Player viewer, @NotNull Collection<Location> positions);
}
