package net.exylia.lib.util;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Taking a player's effects aside and giving them back: the whole set, or only
 * the types a caller is about to replace.
 */
class EffectSnapshotTest {

    private static final Set<String> KNOWN = Set.of("SPEED", "STRENGTH", "NIGHT_VISION");

    private Player player;
    /** The fake player's active effects, by type. */
    private final Map<String, EffectSnapshot.Entry> active = new LinkedHashMap<>();

    private Effects.EffectResolver originalResolver;
    private Effects.EffectRemover originalRemover;
    private EffectSnapshot.EffectReader originalReader;
    private EffectSnapshot.EffectRestorer originalRestorer;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        player = new FakePlayer("Steve").player();
        FakeServer.online(player);
        active.clear();

        originalResolver = Effects.getResolver();
        originalRemover = Effects.getRemover();
        originalReader = EffectSnapshot.getReader();
        originalRestorer = EffectSnapshot.getRestorer();

        Effects.setResolver(name -> KNOWN.contains(name) ? name : null);
        Effects.setRemover((p, type) -> active.remove((String) type));
        EffectSnapshot.setReader(p -> {
            List<EffectSnapshot.Held> held = new ArrayList<>();
            active.forEach((type, entry) -> held.add(new EffectSnapshot.Held(type, entry)));
            return held;
        });
        EffectSnapshot.setRestorer((p, type, entry) -> active.put((String) type, entry));
    }

    @AfterEach
    void tearDown() {
        Effects.setResolver(originalResolver);
        Effects.setRemover(originalRemover);
        EffectSnapshot.setReader(originalReader);
        EffectSnapshot.setRestorer(originalRestorer);
        FakeServer.reset();
    }

    private void on(String type, int duration, int amplifier) {
        active.put(type, new EffectSnapshot.Entry(type, duration, amplifier, false, true, true));
    }

    @Test
    @DisplayName("a scoped restore gives back the named types and leaves every other one running")
    void scopedLeavesOthersAlone() {
        on("SPEED", 600, 1);
        on("NIGHT_VISION", 400, 0);
        EffectSnapshot own = EffectSnapshot.of(player, List.of("speed", "STRENGTH"));

        // The fight swaps Speed for its own, and Night Vision keeps ticking down.
        active.remove("SPEED");
        on("SPEED", 3600, 0);
        on("STRENGTH", 3600, 0);
        on("NIGHT_VISION", 120, 0);

        own.restoreTo(player);

        assertEquals(600, active.get("SPEED").duration());
        assertEquals(1, active.get("SPEED").amplifier());
        assertFalse(active.containsKey("STRENGTH"), "a type that was not on at capture is gone");
        assertEquals(120, active.get("NIGHT_VISION").duration(), "an unnamed type is not reset");
    }

    @Test
    @DisplayName("a full restore clears everything and puts back exactly what was on")
    void fullClearsEverything() {
        on("SPEED", 600, 1);
        EffectSnapshot own = EffectSnapshot.of(player);

        on("NIGHT_VISION", 400, 0);
        active.put("SPEED", new EffectSnapshot.Entry("SPEED", 20, 0, false, true, true));

        own.restoreTo(player);

        assertEquals(Set.of("SPEED"), active.keySet());
        assertEquals(600, active.get("SPEED").duration());
    }

    @Test
    @DisplayName("an effect that did not end comes back not ending")
    void infiniteStaysInfinite() {
        on("SPEED", Effects.INFINITE, 0);
        EffectSnapshot own = EffectSnapshot.of(player, List.of("SPEED"));
        active.clear();

        own.restoreTo(player);

        assertEquals(Effects.INFINITE, active.get("SPEED").duration());
    }

    @Test
    @DisplayName("a name the server has no effect for is skipped, not an error")
    void unknownNamesSkipped() {
        on("SPEED", 600, 0);
        EffectSnapshot own = EffectSnapshot.of(player, List.of("NOT_AN_EFFECT", " "));

        assertTrue(own.isEmpty());
        own.restoreTo(player);
        assertEquals(600, active.get("SPEED").duration(), "an empty scope touches nothing");
    }

    @Test
    @DisplayName("the snapshot keeps its own copy of what was held")
    void heldIsReported() {
        on("SPEED", 600, 1);
        on("STRENGTH", 300, 0);
        EffectSnapshot own = EffectSnapshot.of(player, List.of("STRENGTH"));

        assertEquals(1, own.effects().size());
        assertEquals("STRENGTH", own.effects().get(0).type());
    }
}
