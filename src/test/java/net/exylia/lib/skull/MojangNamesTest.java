package net.exylia.lib.skull;

import net.exylia.lib.skull.internal.MojangApi;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class MojangNamesTest {

    @Test
    void aNameNoAccountCanHaveIsNeverAskedAndPausesNothing() {
        MojangApi api = new MojangApi(Duration.ofSeconds(1), 60_000, 60_000, Logger.getLogger("test"));
        for (String name : new String[]{"%lb_name%", "two words", "seventeen_letters", "", "§aRed"}) {
            assertNull(api.idOf(name), name);
        }
        assertFalse(api.isBackedOff());
    }
}
