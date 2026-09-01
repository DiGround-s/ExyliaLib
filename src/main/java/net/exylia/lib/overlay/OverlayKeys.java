package net.exylia.lib.overlay;

import net.exylia.lib.action.ActionKey;
import net.exylia.lib.ui.ClickKind;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

/**
 * What an overlay's actions are told about the press that ran them.
 *
 * <p>The menu module has {@link net.exylia.lib.ui.UiKeys} for the same
 * purpose; these are separate because an overlay press happens in the world
 * rather than on a screen, and two of them carry things a menu click never
 * has: the entity that was clicked and the block it was used on.
 *
 * @since 1.79.0
 */
public final class OverlayKeys {

    private OverlayKeys() {
        throw new AssertionError("No instances.");
    }

    /** The id of the overlay the item belongs to. */
    public static final ActionKey<String> OVERLAY =
            ActionKey.of("overlay.id", String.class);

    /** The inventory index that was pressed, as {@link OverlaySlots} numbers it. */
    public static final ActionKey<Integer> SLOT =
            ActionKey.of("overlay.slot", Integer.class);

    /** How it was pressed. */
    public static final ActionKey<ClickKind> CLICK =
            ActionKey.of("overlay.click", ClickKind.class);

    /**
     * The entity the item was used on, when it was used on one.
     *
     * <p>The reason a staff tool can inspect the player it was pointed at
     * without the plugin having to guess from a ray trace.
     */
    public static final ActionKey<Entity> TARGET =
            ActionKey.of("overlay.target", Entity.class);

    /** The block the item was used on, when it was used on one. */
    public static final ActionKey<Block> BLOCK =
            ActionKey.of("overlay.block", Block.class);
}
