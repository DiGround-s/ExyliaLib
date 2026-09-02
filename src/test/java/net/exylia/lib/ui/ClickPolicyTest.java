package net.exylia.lib.ui;

import net.exylia.lib.ui.ClickPolicy.Decision;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a click is allowed to do.
 *
 * <p>The security boundary. Every case in here is a way a player gets an item
 * out of a menu that was never meant to give them one, and each has been a real
 * duplication bug in some plugin or other.
 */
class ClickPolicyTest {

    private static final Set<Integer> NO_INPUTS = Set.of();
    private static final Set<Integer> INPUTS = Set.of(11, 13, 15);

    @Test
    @DisplayName("a click on a button is cancelled and handled")
    void buttonClick() {
        assertEquals(Decision.BUTTON,
                ClickPolicy.decide(true, true, false, 22, NO_INPUTS));
    }

    @Test
    @DisplayName("a click in the player's own inventory is left alone")
    void ownInventory() {
        assertEquals(Decision.ALLOW,
                ClickPolicy.decide(true, false, false, 40, NO_INPUTS));
    }

    @Test
    @DisplayName("shift-clicking from below into a menu that takes nothing is refused")
    void shiftClickIntoAButtonMenu() {
        // The one every plugin forgets. The clicked inventory is the player's,
        // so a naive check waves it through — and the item lands in a menu that
        // is about to close and give it back, which is how items duplicate.
        assertEquals(Decision.CANCEL,
                ClickPolicy.decide(true, false, true, 40, NO_INPUTS));
    }

    @Test
    @DisplayName("shift-clicking into a menu that does take items is allowed")
    void shiftClickIntoAnInputMenu() {
        assertEquals(Decision.ALLOW,
                ClickPolicy.decide(true, false, true, 40, INPUTS));
    }

    @Test
    @DisplayName("an input slot stays the player's")
    void inputSlot() {
        assertEquals(Decision.ALLOW,
                ClickPolicy.decide(true, true, false, 13, INPUTS));
    }

    @Test
    @DisplayName("a button in a menu that has input slots is still a button")
    void buttonBesideInputs() {
        // A kit editor has both. Being allowed to place an item in slot 13 must
        // not make slot 22's save button collectable.
        assertEquals(Decision.BUTTON,
                ClickPolicy.decide(true, true, false, 22, INPUTS));
    }

    @Test
    @DisplayName("somebody else's window is not ours to touch")
    void notOurWindow() {
        assertEquals(Decision.IGNORE,
                ClickPolicy.decide(false, true, false, 22, NO_INPUTS));
        assertEquals(Decision.IGNORE,
                ClickPolicy.decide(false, false, true, 40, NO_INPUTS));
    }

    @Test
    @DisplayName("a drag across buttons is refused")
    void dragAcrossButtons() {
        assertTrue(ClickPolicy.refuseDrag(54, List.of(10, 11, 12), NO_INPUTS));
    }

    @Test
    @DisplayName("a drag entirely in the player's own inventory is fine")
    void dragBelow() {
        // Raw slots at or past the top inventory's size belong to the player.
        assertFalse(ClickPolicy.refuseDrag(54, List.of(54, 55, 56), NO_INPUTS));
    }

    @Test
    @DisplayName("a drag across input slots is fine")
    void dragAcrossInputs() {
        assertFalse(ClickPolicy.refuseDrag(54, List.of(11, 13, 15), INPUTS));
    }

    @Test
    @DisplayName("a drag that touches one button is refused entirely")
    void dragPartlyOverAButton() {
        // Half-allowing a drag is not a thing Bukkit offers, and the safe half
        // is refusing it: the alternative paints an item over a button.
        assertTrue(ClickPolicy.refuseDrag(54, List.of(11, 13, 22), INPUTS));
    }

    @Test
    @DisplayName("a drag from a menu slot down into the player's inventory is refused")
    void dragOutOfAMenu() {
        assertTrue(ClickPolicy.refuseDrag(54, List.of(22, 60), NO_INPUTS));
    }

    @Test
    @DisplayName("a double-click on a button does nothing")
    void doubleClickOnAButton() {
        assertEquals(ClickPolicy.Decision.CANCEL,
                ClickPolicy.decide(true, true, false, true, 22, NO_INPUTS));
    }

    @Test
    @DisplayName("a double-click in the player's own inventory is refused too")
    void doubleClickBelow() {
        // This is the one that matters. Collect-to-cursor sweeps both
        // inventories, so a player holding one copy of a button's material and
        // double-clicking it down here would pull every matching button out of
        // the menu above. Allowing the slot because it belongs to them is
        // exactly how that duplication happens.
        assertEquals(ClickPolicy.Decision.CANCEL,
                ClickPolicy.decide(true, false, false, true, 60, NO_INPUTS));
    }

    @Test
    @DisplayName("a double-click on an input slot is refused as well")
    void doubleClickOnInput() {
        // An input slot is the player's to use, but a double-click there still
        // collects from the whole window.
        assertEquals(ClickPolicy.Decision.CANCEL,
                ClickPolicy.decide(true, true, false, true, 11, INPUTS));
    }

    @Test
    @DisplayName("a double-click in somebody else's window is still none of our business")
    void doubleClickElsewhere() {
        assertEquals(ClickPolicy.Decision.IGNORE,
                ClickPolicy.decide(false, true, false, true, 22, NO_INPUTS));
    }

    @Test
    @DisplayName("the older five-argument decision still reads as an ordinary click")
    void withoutTheDoubleClickArgument() {
        assertEquals(ClickPolicy.decide(true, true, false, false, 22, NO_INPUTS),
                ClickPolicy.decide(true, true, false, 22, NO_INPUTS));
    }

    @Test
    @DisplayName("menus never report a double-click as a kind they could bind")
    void doubleClickIsNotAKind() {
        assertNull(ClickKind.of(ClickType.DOUBLE_CLICK));
        // The name still parses, so a menu written against an older version
        // loads rather than reporting a broken action.
        assertEquals(ClickKind.LEFT, ClickKind.byName("left"));
    }
}
