package net.exylia.lib.overlay.internal;

import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import net.exylia.lib.FakePlayer;
import net.exylia.lib.item.Item;
import net.exylia.lib.overlay.OverlayDefinition;
import net.exylia.lib.overlay.OverlaySlots;
import net.exylia.lib.ui.UiItem;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The state the packet listener reads on a Netty thread instead of asking the
 * player: the held slot, the sneak, and whether a real item hides under an
 * owned slot.
 */
class OverlayTrackingTest {

    private static OverlayView view(int held, OverlayDefinition definition) {
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(
                OverlayTrackingTest.class.getClassLoader(),
                new Class<?>[] {PlayerInventory.class},
                (self, method, args) -> method.getName().equals("getHeldItemSlot") ? held : null);
        FakePlayer player = new FakePlayer("staff").inventory(inventory);
        return new OverlayView(null, null, player.player(), definition);
    }

    private static OverlayDefinition owning(int... slots) {
        OverlayDefinition.Builder builder = OverlayDefinition.of("test:tools");
        UiItem tool = UiItem.of(Item.of("COMPASS").build()).build();
        for (int slot : slots) {
            builder.slot(slot, tool);
        }
        return builder.build();
    }

    @Test
    @DisplayName("the held slot starts where the player is and follows what it is told")
    void heldSlot() {
        OverlayView view = view(3, owning(0));
        assertEquals(3, view.heldSlot());
        view.held(7);
        assertEquals(7, view.heldSlot());
    }

    @Test
    @DisplayName("an owned slot hides a real item until the server says it is empty")
    void realEmptiness() {
        OverlayView view = view(0, owning(0, OverlaySlots.OFFHAND));
        // Unknown is treated as filled, so a press is refused, not let through.
        assertFalse(view.isRealEmpty(0));
        assertFalse(view.isRealEmpty(OverlaySlots.OFFHAND));

        view.realItem(0, true);
        assertTrue(view.isRealEmpty(0));
        assertFalse(view.isRealEmpty(OverlaySlots.OFFHAND), "one slot's news is not another's");

        view.realItem(OverlaySlots.OFFHAND, true);
        view.realItem(0, false);
        assertFalse(view.isRealEmpty(0));
        assertTrue(view.isRealEmpty(OverlaySlots.OFFHAND));
    }

    @Test
    @DisplayName("the main hand is whichever slot the player was last told to hold")
    void mainHand() {
        OverlayView view = view(0, owning(0, OverlaySlots.HELMET));
        assertEquals(0, OverlayPackets.indexOf(view, EquipmentSlot.MAIN_HAND));
        view.held(5);
        assertEquals(5, OverlayPackets.indexOf(view, EquipmentSlot.MAIN_HAND));
        assertEquals(OverlaySlots.HELMET, OverlayPackets.indexOf(view, EquipmentSlot.HELMET));
    }
}
