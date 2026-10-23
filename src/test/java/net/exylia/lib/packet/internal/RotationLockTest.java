package net.exylia.lib.packet.internal;

import org.bukkit.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotationLockTest {

    private static final Location ANCHOR = new Location(null, 0, 64, 0, 10f, 5f);

    @Test
    @DisplayName("a yaw a full turn away is the same rotation")
    void wrapsYaw() {
        assertFalse(PacketHooks.turned(ANCHOR, 370f, 5f));
        assertFalse(PacketHooks.turned(ANCHOR, -350f, 5f));
        assertFalse(PacketHooks.turned(ANCHOR, 10f, 5f));
    }

    @Test
    @DisplayName("any real turn of yaw or pitch is a turn")
    void detectsTurns() {
        assertTrue(PacketHooks.turned(ANCHOR, 11f, 5f));
        assertTrue(PacketHooks.turned(ANCHOR, 190f, 5f));
        assertTrue(PacketHooks.turned(ANCHOR, 10f, -5f));
    }
}
