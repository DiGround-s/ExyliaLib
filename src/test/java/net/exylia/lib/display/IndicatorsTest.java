package net.exylia.lib.display;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Floating numbers: what merges, what is capped, who sees them.
 */
class IndicatorsTest {

    private final AtomicLong clock = new AtomicLong();
    private final List<String> texts = new ArrayList<>();
    private final List<Handle> handles = new ArrayList<>();
    private final UUID id = UUID.randomUUID();

    private World world;
    private Location where;
    private Indicators indicators;
    private Entity mob;

    /** A handle that knows whether it was taken down. */
    private static final class Handle implements DisplayHandle {
        private boolean removed;

        @Override
        public void remove() {
            removed = true;
        }

        @Override
        public boolean isShowing() {
            return !removed;
        }
    }

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        world = FakeServer.newWorld("numbers");
        FakeServer.worlds(world);
        where = new Location(world, 0, 64, 0);
        FakeServer.online(new FakePlayer("Watcher").at(where.clone().add(5, 0, 0)).player());
        indicators = new Indicators((text, at, viewers) -> {
            texts.add(PlainTextComponentSerializer.plainText().serialize(text));
            Handle handle = new Handle();
            handles.add(handle);
            return handle;
        }, clock::get);
        mob = (Entity) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Entity.class}, (self, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getLocation" -> where.clone();
                    case "getHeight" -> 1.8;
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    default -> FakeServer.defaultValue(method.getReturnType());
                });
    }

    @AfterEach
    void tearDown() {
        FakeServer.reset();
    }

    @Test
    @DisplayName("two hits within the window are one number: the old one goes and the sum shows")
    void mergesWithinTheWindow() {
        indicators.damage(mob, 5, false);
        clock.set(200);
        indicators.damage(mob, 2.5, false);

        assertEquals(2, texts.size());
        assertTrue(texts.get(1).contains("7.5"), texts.get(1));
        assertFalse(handles.get(0).isShowing(), "the merged number replaced the first one");
        assertEquals(1, indicators.live(id));

        clock.set(200 + Indicators.MERGE_MS + 1);
        indicators.damage(mob, 1, false);
        assertTrue(texts.get(2).contains("1"));
        assertEquals(2, indicators.live(id), "past the window it is a number of its own");
    }

    @Test
    @DisplayName("a merged number stays critical if any of its hits was")
    void critSticks() {
        indicators.damage(mob, 5, true);
        clock.set(100);
        indicators.damage(mob, 3, false);

        assertTrue(texts.get(1).contains("✦"), texts.get(1));
        assertTrue(texts.get(1).contains("8"), texts.get(1));
    }

    @Test
    @DisplayName("damage and healing never merge into each other")
    void kindsStayApart() {
        indicators.damage(mob, 4, false);
        clock.set(50);
        indicators.heal(mob, 2);

        assertEquals(2, indicators.live(id));
        assertTrue(texts.get(1).contains("+2"), texts.get(1));
    }

    @Test
    @DisplayName("an entity carries at most four numbers; the oldest goes first")
    void capOfFour() {
        for (int hit = 0; hit < 6; hit++) {
            clock.set(hit * 1000L);
            indicators.damage(mob, 1, false);
        }

        assertEquals(Indicators.MAX_PER_ENTITY, indicators.live(id));
        assertFalse(handles.get(0).isShowing());
        assertFalse(handles.get(1).isShowing());
        assertTrue(handles.get(5).isShowing());
    }

    @Test
    @DisplayName("a label within the window replaces the last one instead of adding up")
    void labelsReplace() {
        indicators.text(mob, Component.text("9 left"));
        clock.set(100);
        indicators.text(mob, Component.text("8 left"));

        assertEquals(List.of("9 left", "8 left"), texts);
        assertEquals(1, indicators.live(id));
    }

    @Test
    @DisplayName("nobody within range sees nothing, and nothing is built")
    void nobodyInRange() {
        indicators.range(2);
        indicators.damage(mob, 5, false);

        assertTrue(texts.isEmpty());
    }

    @Test
    @DisplayName("amounts read with one decimal, and none when whole")
    void formats() {
        assertEquals("7.5", Indicators.format(7.5));
        assertEquals("12", Indicators.format(12.0));
        assertEquals("3", Indicators.format(3.04));
    }
}
