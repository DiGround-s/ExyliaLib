package net.exylia.lib.item.internal;

import net.exylia.lib.item.internal.InertItems.Use;
import org.bukkit.event.block.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a click does to an item that only looks usable.
 *
 * <p>The item half of the same boundary the click policy guards in menus: a
 * token drawn as an ender pearl must not be a thrown ender pearl.
 */
class InertItemsTest {

    @Test
    @DisplayName("right-clicking air is refused outright")
    void rightClickAir() {
        // Nothing else is happening, and this is the click that throws the
        // pearl, eats the apple and drinks the potion.
        assertEquals(Use.CANCEL, InertItems.useOf(Action.RIGHT_CLICK_AIR));
    }

    @Test
    @DisplayName("right-clicking a block refuses the item and leaves the block alone")
    void rightClickBlock() {
        // Holding a token must not stop a player opening the chest in front of
        // them, so only the item half of the click is denied.
        assertEquals(Use.DENY_ITEM, InertItems.useOf(Action.RIGHT_CLICK_BLOCK));
    }

    @Test
    @DisplayName("left clicks are left alone")
    void leftClicks() {
        // Breaking a block and hitting somebody do not spend the item, and
        // refusing them would stop a player defending themselves because of
        // what happens to be in their hand.
        assertEquals(Use.NOTHING, InertItems.useOf(Action.LEFT_CLICK_AIR));
        assertEquals(Use.NOTHING, InertItems.useOf(Action.LEFT_CLICK_BLOCK));
    }

    @Test
    @DisplayName("stepping on something is not a use")
    void physical() {
        assertEquals(Use.NOTHING, InertItems.useOf(Action.PHYSICAL));
    }
}
