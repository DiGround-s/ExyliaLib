package net.exylia.lib.util.sequence.internal;

import net.exylia.lib.FakePlayer;
import org.bukkit.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which way a body ends up facing.
 *
 * <p>The case worth a test is the one nobody thinks of: a self effect, where
 * the body and whoever caused it are the same person standing in the same
 * place. There is no angle between a point and itself, and the arithmetic that
 * works everywhere else answers zero — due south. Every emote on a server faced
 * south whatever the player was looking at, and nothing failed.
 */
class FaceSourceTest {

    private static final float EPSILON = 1e-3f;

    @Test
    @DisplayName("a body turns to face a source standing somewhere else")
    void facesSomebodyElse() {
        // The body is north of the source, so it looks south, back at them.
        Location body = new Location(null, 0, 64, -5, 123f, 0f);
        Steps.faceSource(body, new FakePlayer("watcher")
                .at(new Location(null, 0, 64, 0)).player());

        assertEquals(0f, body.getYaw(), EPSILON);
    }

    @Test
    @DisplayName("a body west of the source looks east at them")
    void facesEast() {
        Location body = new Location(null, -5, 64, 0, 0f, 0f);
        Steps.faceSource(body, new FakePlayer("watcher")
                .at(new Location(null, 0, 64, 0)).player());

        // East is a yaw of -90 in Minecraft, which is where 270 also points.
        assertEquals(-90f, body.getYaw(), EPSILON);
    }

    @Test
    @DisplayName("a body standing where the source stands keeps the yaw it came with")
    void selfEffectKeepsItsOwnYaw() {
        // An emote: the location came from the player, so it already carries
        // the direction they were looking. Overwriting it is the bug.
        Location body = new Location(null, 12.5, 64, -3.5, 143.25f, 0f);
        Steps.faceSource(body, new FakePlayer("performer")
                .at(new Location(null, 12.5, 64, -3.5, 143.25f, 0f)).player());

        assertEquals(143.25f, body.getYaw(), EPSILON);
    }

    @Test
    @DisplayName("a hair of drift between the two is still the same spot")
    void driftIsStillTheSameSpot() {
        // A player's location read twice in one tick can differ by a fraction
        // of a block; that is not a direction to face.
        Location body = new Location(null, 0, 64, 0, 77f, 0f);
        Steps.faceSource(body, new FakePlayer("performer")
                .at(new Location(null, 0.02, 64, -0.03)).player());

        assertEquals(77f, body.getYaw(), EPSILON);
    }

    @Test
    @DisplayName("no source at all leaves the body as it was")
    void noSource() {
        Location body = new Location(null, 0, 64, 0, 45f, 0f);
        Steps.faceSource(body, null);

        assertEquals(45f, body.getYaw(), EPSILON);
    }
}
