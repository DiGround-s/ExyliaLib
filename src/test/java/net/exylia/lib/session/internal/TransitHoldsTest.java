package net.exylia.lib.session.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransitHoldsTest {

    private final UUID player = UUID.randomUUID();

    @BeforeEach
    void reset() {
        TransitHolds.clearForTests();
    }

    @Test
    void beginHoldsForAMoment() {
        assertFalse(TransitHolds.isHeld(player));
        TransitHolds.begin(player);
        assertTrue(TransitHolds.isHeld(player));
    }

    @Test
    void aTeleportOutsideATransitHoldsNothing() {
        CompletableFuture<Object> landed = new CompletableFuture<>();
        TransitHolds.track(player, landed);
        assertFalse(TransitHolds.isHeld(player));
        landed.complete(null);
        assertFalse(TransitHolds.isHeld(player));
    }

    @Test
    void aRestoreHoldsUntilItEndsAndOnlyLingersWhenItGaveSomethingBack() {
        CompletableFuture<Boolean> nothing = new CompletableFuture<>();
        TransitHolds.during(player, nothing, Boolean.TRUE::equals);
        assertTrue(TransitHolds.isHeld(player));
        nothing.complete(false);
        assertFalse(TransitHolds.isHeld(player));

        CompletableFuture<Boolean> restored = new CompletableFuture<>();
        TransitHolds.during(player, restored, Boolean.TRUE::equals);
        restored.complete(true);
        assertTrue(TransitHolds.isHeld(player));
    }

    @Test
    void aTeleportInsideATransitKeepsItOpenUntilItLands() {
        CompletableFuture<Boolean> restore = new CompletableFuture<>();
        TransitHolds.during(player, restore, Boolean.TRUE::equals);
        CompletableFuture<Object> landed = new CompletableFuture<>();
        TransitHolds.track(player, landed);
        restore.complete(false);
        assertTrue(TransitHolds.isHeld(player));
        landed.complete(null);
        assertTrue(TransitHolds.isHeld(player));
    }
}
