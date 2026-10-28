package net.exylia.lib.ability;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An item replaced in place is re-read, once a tick, and only where it counts. */
class PaperSlotListenerTest {

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
    }

    @AfterEach
    void tearDown() {
        FakeServer.reset();
    }

    @Test
    @DisplayName("the held hotbar slot, armour and offhand count; the rest of the inventory does not")
    void slotsThatCount() {
        assertTrue(PaperSlotListener.counts(3, 3), "the held slot");
        assertFalse(PaperSlotListener.counts(4, 3), "another hotbar slot");
        assertFalse(PaperSlotListener.counts(20, 3), "the backpack");
        for (int worn = 36; worn <= 40; worn++) {
            assertTrue(PaperSlotListener.counts(worn, 0), "armour and offhand: " + worn);
        }
        assertFalse(PaperSlotListener.counts(41, 0));
    }

    @Test
    @DisplayName("a burst of slot changes is one refresh next tick")
    void coalesced() {
        PluginAbilities abilities = PluginAbilities.detached(FakeServer.newPlugin("SlotTest"));
        AtomicInteger reads = new AtomicInteger();
        abilities.holdings(player -> {
            reads.incrementAndGet();
            return List.of();
        });
        Player player = new FakePlayer("Steve").player();

        for (int i = 0; i < 40; i++) {
            abilities.refreshSoon(player);
        }
        assertEquals(0, reads.get(), "nothing before the next tick");
        FakeServer.tick(5);
        assertEquals(1, reads.get(), "forty events, one refresh");
    }
}
