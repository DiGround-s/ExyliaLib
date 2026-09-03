package net.exylia.lib.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who is allowed to see the server as it really is. */
class WatchersTest {

    @AfterEach
    void tearDown() {
        Watchers.releaseAll();
    }

    @Test
    void nobodyWatchesUntilSomePluginSaysSo() {
        assertFalse(Watchers.watching(FakePlayer.any()));
    }

    @Test
    void oneVoiceIsEnough() {
        Watchers.rule(FakePlugin.named("ExyliaPracticeCore"), viewer -> false);
        Watchers.rule(FakePlugin.named("ExyliaStaff"), viewer -> true);
        assertTrue(Watchers.watching(FakePlayer.any()));
    }

    @Test
    void aRuleReplacesItsOwnPluginsPreviousOne() {
        Watchers.rule(FakePlugin.named("ExyliaStaff"), viewer -> true);
        Watchers.rule(FakePlugin.named("ExyliaStaff"), viewer -> false);
        assertFalse(Watchers.watching(FakePlayer.any()));
    }

    @Test
    void aDisabledPluginStopsHavingASay() {
        Watchers.rule(FakePlugin.named("ExyliaStaff"), viewer -> true);
        Watchers.release("ExyliaStaff");
        assertFalse(Watchers.watching(FakePlayer.any()));
    }

    /** A broken rule must not switch off the isolation a match depends on. */
    @Test
    void aRuleThatBreaksIsReadAsNo() {
        Watchers.rule(FakePlugin.named("ExyliaStaff"), viewer -> {
            throw new IllegalStateException("no");
        });
        assertFalse(Watchers.watching(FakePlayer.any()));
    }

    @Test
    void clearingTakesTheRuleAway() {
        var plugin = FakePlugin.named("ExyliaStaff");
        Watchers.rule(plugin, viewer -> true);
        Watchers.clear(plugin);
        assertFalse(Watchers.watching(FakePlayer.any()));
    }

    @Test
    void aModeIsToldWhenSomebodyStartsWatching() {
        java.util.List<String> heard = new java.util.ArrayList<>();
        Watchers.onChange(FakePlugin.named("ExyliaPracticeCore"), viewer -> heard.add("redraw"));
        Watchers.refresh(FakePlayer.any());
        assertEquals(java.util.List.of("redraw"), heard);
    }

    @Test
    void aListenerThatBreaksDoesNotStopTheRest() {
        java.util.List<String> heard = new java.util.ArrayList<>();
        Watchers.onChange(FakePlugin.named("ExyliaPracticeCore"), viewer -> {
            throw new IllegalStateException("no");
        });
        Watchers.onChange(FakePlugin.named("ExyliaFFA"), viewer -> heard.add("redraw"));
        Watchers.refresh(FakePlayer.any());
        assertEquals(java.util.List.of("redraw"), heard);
    }

    @Test
    void aDisabledPluginStopsListening() {
        java.util.List<String> heard = new java.util.ArrayList<>();
        Watchers.onChange(FakePlugin.named("ExyliaPracticeCore"), viewer -> heard.add("redraw"));
        Watchers.release("ExyliaPracticeCore");
        Watchers.refresh(FakePlayer.any());
        assertTrue(heard.isEmpty());
    }
}
