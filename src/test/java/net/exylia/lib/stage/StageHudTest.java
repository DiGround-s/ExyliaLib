package net.exylia.lib.stage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageHudTest {

    @Test
    @DisplayName("the countdown rounds seconds up, expires at its deadline and ticks once per second shown")
    void countdown() {
        AtomicLong now = new AtomicLong(1_000);
        StageHud hud = new StageHud(null, 2, now::get);
        hud.clock(3_000);
        assertFalse(hud.expired());
        assertEquals(3, hud.secondsLeft());
        assertTrue(hud.ticked(), "the first read always redraws");
        assertFalse(hud.ticked(), "the same second does not");
        now.addAndGet(400);
        assertEquals(3, hud.secondsLeft(), "2.6 seconds read as 3");
        assertFalse(hud.ticked());
        now.addAndGet(700);
        assertEquals(2, hud.secondsLeft());
        assertTrue(hud.ticked());
        now.set(4_000);
        assertTrue(hud.expired());
        assertEquals(0, hud.secondsLeft());
        now.set(9_000);
        assertEquals(0, hud.secondsLeft(), "never negative");
    }

    @Test
    @DisplayName("a new clock starts over and redraws at once")
    void restart() {
        AtomicLong now = new AtomicLong();
        StageHud hud = new StageHud(null, 1, now::get);
        hud.clock(1_000);
        hud.ticked();
        now.set(1_500);
        assertTrue(hud.expired());
        hud.clock(5_000);
        assertFalse(hud.expired());
        assertTrue(hud.ticked());
        hud.stopAll();
    }

    @Test
    @DisplayName("a sound line's pitch is its third part, or 1")
    void pitch() {
        assertEquals(1.8, StageLook.pitch("BLOCK_NOTE_BLOCK_HAT|0.4|1.8"), 1e-9);
        assertEquals(1.0, StageLook.pitch("UI_BUTTON_CLICK|0.6"), 1e-9);
        assertEquals(1.0, StageLook.pitch("UI_BUTTON_CLICK|0.6|loud"), 1e-9);
    }

    @Test
    @DisplayName("narrow glyphs count as narrow, so a number is not shrunk below what fits")
    void width() {
        assertEquals(0.18 * 4, StageLook.width("ABCD"), 1e-9);
        assertTrue(StageLook.width("7.92") < StageLook.width("7992"));
        assertEquals(0.5, StageLook.fit(net.kyori.adventure.text.Component.text("AB"), 10, 0.5), 1e-9, "capped");
    }
}
