package net.exylia.lib.cosmetic;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The first line a player qualifies for wins, and unknown ids fall through. */
class CosmeticDefaultTest {

    private static final Set<String> CATALOGUE = Set.of("lightning", "blood");

    private final List<CosmeticDefault> lines = List.of(
            new CosmeticDefault("group.vip", "lightning"),
            new CosmeticDefault("group.legacy", "removed_effect"),
            new CosmeticDefault("", "blood"));

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
    }

    @Test
    void firstMatchingLineWins() {
        FakePlayer vip = new FakePlayer("Vip").grant("group.vip");
        assertEquals("lightning", CosmeticDefault.resolve(vip.player(), lines, CATALOGUE::contains));
    }

    @Test
    void emptyPermissionIsEveryone() {
        FakePlayer steve = new FakePlayer("Steve");
        assertEquals("blood", CosmeticDefault.resolve(steve.player(), lines, CATALOGUE::contains));
    }

    @Test
    void unknownIdFallsThrough() {
        FakePlayer legacy = new FakePlayer("Legacy").grant("group.legacy");
        assertEquals("blood", CosmeticDefault.resolve(legacy.player(), lines, CATALOGUE::contains));
    }

    @Test
    void noneStopsTheSearch() {
        List<CosmeticDefault> withNone = List.of(new CosmeticDefault("group.staff", "NONE"), new CosmeticDefault("", "blood"));
        FakePlayer staff = new FakePlayer("Staff").grant("group.staff");
        assertNull(CosmeticDefault.resolve(staff.player(), withNone, CATALOGUE::contains));
        assertEquals("blood", CosmeticDefault.resolve(new FakePlayer("Steve").player(), withNone, CATALOGUE::contains));
    }

    @Test
    void nothingConfiguredIsNothing() {
        assertNull(CosmeticDefault.resolve(new FakePlayer("Steve").player(), null, CATALOGUE::contains));
        assertNull(CosmeticDefault.resolve(new FakePlayer("Steve").player(), List.of(new CosmeticDefault()), CATALOGUE::contains));
    }

    @Test
    void noneSentinel() {
        assertTrue(CosmeticDefault.isNone("none"));
        assertTrue(CosmeticDefault.isNone(" None "));
        assertFalse(CosmeticDefault.isNone(null));
        assertFalse(CosmeticDefault.isNone("blood"));
    }
}
