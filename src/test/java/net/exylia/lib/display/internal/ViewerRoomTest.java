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
    private final org.bukkit.World world = (org.bukkit.World) Proxy.newProxyInstance(org.bukkit.World.class.getClassLoader(),
            new Class<?>[]{org.bukkit.World.class}, (proxy, method, args) -> null);

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
        return DisplayRuntime.show("Test", DisplayModel.text(Component.empty()), still, new Location(world, 0, y, 0), to);
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
    void aRehearsalIsNeverSentAndTakesNoRoomYetIsMeasured() {
        AtomicInteger next = new AtomicInteger(1);
        AtomicInteger sentTo = new AtomicInteger();
        DisplaySink sink = (DisplaySink) Proxy.newProxyInstance(DisplaySink.class.getClassLoader(),
                new Class<?>[]{DisplaySink.class}, (proxy, method, args) -> {
                    if (args != null && args[0] instanceof List<?> viewers) sentTo.addAndGet(viewers.size());
                    return null;
                });
        DisplayRuntime.testHooks(System::currentTimeMillis, next::getAndIncrement, sink);
        DisplayRuntime.limitViewer(viewer.getUniqueId(), () -> 1, 48);

        assertNotNull(show(List.of(viewer), 60));
        assertNotNull(show(List.of(viewer), 60));
        assertEquals(0, sentTo.get(), "out of sight, so out of the wire");
        assertEquals(2, DisplayRuntime.sample(viewer, new Location(world, 0, 60, 0), 3, java.util.Set.of()).size());
        assertNotNull(show(List.of(viewer)), "the real play keeps all of its room");
        assertNull(show(List.of(viewer)));
    }
}
