package net.exylia.lib.effect.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.effect.Timer;
import net.exylia.lib.text.Colors;
import net.exylia.lib.text.Palette;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A display is never redrawn before it is drawn.
 *
 * <p>A boss bar redraw is an update packet for a bar the client must already
 * have; one that arrives first throws on the client and disconnects it. On
 * Paper the shared entity ticker is older than the task carrying the first
 * draw, so in the tick both are due it ran first.
 */
class DisplayOrderTest {

    private FakePlayer viewer;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Packets.override(false);
        Colors.apply(new Palette());
        Effects.owner(FakeServer.newPlugin("Practice", null));
        viewer = new FakePlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        EffectRuntime.stopEverything();
        FakeServer.reset();
        Packets.reset();
    }

    @Test
    @DisplayName("a display started while the entity ticker runs is drawn before its first redraw")
    void drawComesFirst() {
        // Keeps the shared entity ticker alive, so it is scheduled before the draw.
        Effects.bossBar("Queue").countdown(60).show(viewer.player());
        FakeServer.tick(2);

        List<String> calls = new CopyOnWriteArrayList<>();
        new ActiveDisplay(viewer.player(), new Rendered("Combat", null), Timer.countdown(10), 1, null) {
            @Override
            void draw(Player player, Rendered rendered, Timer timer) {
                calls.add("draw");
            }

            @Override
            void redraw(Player player, Rendered rendered, Timer timer) {
                calls.add("redraw");
            }

            @Override
            void clear(Player player) {
                calls.add("clear");
            }
        }.start();
        FakeServer.tick(3);

        assertEquals("draw", calls.get(0), "redrawn before drawn: " + calls);
    }
}
