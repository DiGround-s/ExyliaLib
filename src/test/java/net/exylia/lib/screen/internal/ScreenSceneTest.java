package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.ScreenScene;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenSceneTest {

    @Test
    void spritesStandWhereTheyWereMovedAndEachChangeRedraws() {
        AtomicInteger redraws = new AtomicInteger();
        ScreenScene scene = new ScreenScene("table", redraws::incrementAndGet);
        scene.sprite("chip").rect(10, 10, "#ff0000").at(30, 20);
        scene.sprite("chip").move(50, 40, 8, Motion.Ease.OUT);
        assertEquals(4, redraws.get());   // size, content, at, move
        List<Layout.Placed> laid = Layout.lay(scene.node(200, 100), 200, 100);
        Layout.Placed chip = laid.stream().filter(p -> p.key().contains("scene:table:chip")).findFirst().orElseThrow();
        assertEquals(50, chip.x(), 0.001);
        assertEquals(40, chip.y(), 0.001);
        assertEquals(10, chip.w(), 0.001);
        scene.remove("chip");
        assertTrue(Layout.lay(scene.node(200, 100), 200, 100).stream().noneMatch(p -> p.key().contains("chip")));
    }
}
