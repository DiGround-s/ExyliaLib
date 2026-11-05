package net.exylia.lib.guide.internal;

import net.exylia.lib.guide.BeaconStyle;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How big a beacon's label is drawn, and that a camera shot gets none of it. */
class BeaconLabelTest {

    private static final BlockData GLASS = (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(),
            new Class<?>[]{BlockData.class}, (proxy, method, args) -> null);
    private static final BeaconStyle STYLE = BeaconStyle.of(GLASS, 0x8A51C4).markerDistance(30);

    @Test
    @DisplayName("the label grows with the body's distance at the target and beside the stand-in")
    void sized() {
        float base = STYLE.label().scale();
        assertEquals(base * 45 / 8.0, LiveBeacon.labelScale(false, false, 45, STYLE), 1e-4);
        assertEquals(base * 3, LiveBeacon.labelScale(false, true, 400, STYLE), 1e-4);
    }

    @Test
    @DisplayName("while the player looks through a camera the label is not drawn, wherever the body is")
    void filmed() {
        assertEquals(0f, LiveBeacon.labelScale(true, false, 45, STYLE));
        assertEquals(0f, LiveBeacon.labelScale(true, true, 400, STYLE));
    }
}
