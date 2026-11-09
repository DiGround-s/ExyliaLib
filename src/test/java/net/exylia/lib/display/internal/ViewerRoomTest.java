package net.exylia.lib.display.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.Rotation;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A window's stage: what is shown to the viewer alone stops at the room the screen leaves it. */
class ViewerRoomTest {

    private final Player viewer = new FakePlayer("Viewer").player();
    private final Player other = new FakePlayer("Other").player();

    @AfterEach
    void tearDown() {
        DisplayRuntime.freeViewer(viewer.getUniqueId());
        DisplayRuntime.releaseAll();
        DisplayRuntime.testHooks(null, null, null);
    }

    private DisplayHandle show(List<Player> to) {
        return show(to, 0);
    }

    private DisplayHandle show(List<Player> to, double y) {
        DisplayMotion still = DisplayMotion.of(List.of(new DisplayKeyframe(0, 0, 0, 0, Rotation.NONE, 1, 1, 1)), 5000);
        return DisplayRuntime.show("Test", DisplayModel.text(Component.empty()), still, new Location(null, 0, y, 0), to);
    }

    @Test
    void theStageStopsAtItsRoomAndGivesItBackWhenLetGo() {
        AtomicInteger next = new AtomicInteger(1);
        DisplaySink sink = (DisplaySink) Proxy.newProxyInstance(DisplaySink.class.getClassLoader(),
                new Class<?>[]{DisplaySink.class}, (proxy, method, args) -> null);
        DisplayRuntime.testHooks(System::currentTimeMillis, next::getAndIncrement, sink);
        DisplayRuntime.limitViewer(viewer.getUniqueId(), () -> 2, 48);

        assertNotNull(show(List.of(viewer)));
        assertNotNull(show(List.of(viewer)));
        assertNull(show(List.of(viewer)), "past its room");
        assertNotNull(show(List.of(other)), "nobody else is held to it");
        assertNotNull(show(List.of(viewer, other)), "what others see too is not the stage's");
        assertEquals(2, DisplayRuntime.shownOnlyTo(viewer));

        DisplayRuntime.freeViewer(viewer.getUniqueId());
        assertNotNull(show(List.of(viewer)));
    }

    @Test
    void aRehearsalKeepsToAQuarterOfTheRoom() {
        AtomicInteger next = new AtomicInteger(1);
        DisplaySink sink = (DisplaySink) Proxy.newProxyInstance(DisplaySink.class.getClassLoader(),
                new Class<?>[]{DisplaySink.class}, (proxy, method, args) -> null);
        DisplayRuntime.testHooks(System::currentTimeMillis, next::getAndIncrement, sink);
        DisplayRuntime.limitViewer(viewer.getUniqueId(), () -> 8, 48);

        assertNotNull(show(List.of(viewer), 60));
        assertNotNull(show(List.of(viewer), 60));
        assertNull(show(List.of(viewer), 60), "past a quarter of eight");
        for (int shown = 2; shown < 8; shown++) assertNotNull(show(List.of(viewer)), "the real play keeps the rest");
        assertNull(show(List.of(viewer)));
    }
}
