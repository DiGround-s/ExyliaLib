package net.exylia.lib.packet;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientVersionsTest {

    private static final ClientVersion MIN = ClientVersion.V_1_21_4;

    @Test
    void olderClientIsRefused() {
        assertFalse(ClientVersions.atLeast(ClientVersion.V_1_21_2, ClientVersion.V_1_21_11, MIN));
        assertFalse(ClientVersions.atLeast(ClientVersion.V_1_20_3, ClientVersion.V_1_21_11, MIN));
    }

    @Test
    void sameOrNewerClientIsAllowed() {
        assertTrue(ClientVersions.atLeast(ClientVersion.V_1_21_4, ClientVersion.V_1_21_11, MIN));
        assertTrue(ClientVersions.atLeast(ClientVersion.V_1_21_11, ClientVersion.V_1_21_11, MIN));
    }

    @Test
    void unknownClientCountsAsTheServer() {
        assertTrue(ClientVersions.atLeast(null, ClientVersion.V_1_21_11, MIN));
        assertTrue(ClientVersions.atLeast(ClientVersion.UNKNOWN, ClientVersion.V_1_21_4, MIN));
        assertFalse(ClientVersions.atLeast(null, ClientVersion.V_1_21_2, MIN));
    }
}
