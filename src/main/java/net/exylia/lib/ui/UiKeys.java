package net.exylia.lib.ui;

import net.exylia.lib.action.ActionKey;
import org.bukkit.inventory.ItemStack;

/**
 * What an action can read when a menu is what triggered it.
 *
 * <p>These live here, not in the action module: the core of actions does not
 * import clicks, slots or inventories, and does not need to. A handler that
 * only makes sense inside a menu asks for these keys; one that works anywhere
 * ignores them.
 *
 * <pre>{@code
 * actions.registerSync("adjust_priority", (context, args) -> {
 *     int slot = context.require(UiKeys.SLOT);
 *     ClickKind click = context.require(UiKeys.CLICK);
 *     ...
 * });
 * }</pre>
 *
 * @since 1.22.0
 */
public final class UiKeys {

    private UiKeys() {
    }

    /** The menu the click happened in. */
    public static final ActionKey<UiSession> SESSION =
            ActionKey.of("ui.session", UiSession.class);

    /** The id of the menu definition, such as {@code practice:queue_kits}. */
    public static final ActionKey<String> MENU =
            ActionKey.of("ui.menu", String.class);

    /** Which slot was clicked. */
    public static final ActionKey<Integer> SLOT =
            ActionKey.of("ui.slot", Integer.class);

    /** How it was clicked. */
    public static final ActionKey<ClickKind> CLICK =
            ActionKey.of("ui.click", ClickKind.class);

    /** The item drawn in that slot, as the player sees it. */
    public static final ActionKey<ItemStack> ITEM =
            ActionKey.of("ui.item", ItemStack.class);

    /** The page being shown, starting at one. */
    public static final ActionKey<Integer> PAGE =
            ActionKey.of("ui.page", Integer.class);

    /**
     * The value behind the clicked entry of a paginated list.
     *
     * <p>What a row is <em>about</em> — the kit, the arena, the party member —
     * so a handler does not have to reconstruct it from the item it was drawn
     * as, which is what menus did before and why they kept static maps keyed by
     * player.
     */
    public static final ActionKey<Object> ENTRY =
            ActionKey.of("ui.entry", Object.class);
}
