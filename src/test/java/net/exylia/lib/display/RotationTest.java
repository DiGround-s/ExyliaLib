package net.exylia.lib.display;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quaternion maths behind a display's rotation.
 *
 * <p>Worth asserting as numbers because the failure is invisible in review and
 * obvious in game: a composition written the wrong way round tilts the model
 * around the world's axes instead of its own, and a ring of blades ends up
 * lying on the floor.
 */
class RotationTest {

    private static final float EPSILON = 1e-5f;

    @Test
    @DisplayName("no rotation is the identity quaternion")
    void identity() {
        assertTrue(Rotation.NONE.isNone());
        assertEquals(1f, Rotation.NONE.w());
        assertFalse(Rotation.around(Rotation.Axis.Y, Math.PI).isNone());
    }

    @Test
    @DisplayName("a half turn around an axis puts all of it in that axis")
    void halfTurn() {
        Rotation half = Rotation.around(Rotation.Axis.Y, Math.PI);

        assertEquals(0f, half.x(), EPSILON);
        assertEquals(1f, half.y(), EPSILON);
        assertEquals(0f, half.z(), EPSILON);
        assertEquals(0f, half.w(), EPSILON);
    }

    @Test
    @DisplayName("two quarter turns around one axis make a half turn")
    void composesAlongOneAxis() {
        Rotation quarter = Rotation.around(Rotation.Axis.Z, Math.PI / 2);
        Rotation composed = quarter.then(quarter);
        Rotation half = Rotation.around(Rotation.Axis.Z, Math.PI);

        assertEquals(half.z(), composed.z(), EPSILON);
        assertEquals(half.w(), composed.w(), EPSILON);
    }

    @Test
    @DisplayName("a full turn comes back to where it started")
    void fullTurnReturns() {
        Rotation full = Rotation.around(Rotation.Axis.X, Math.PI * 2);

        // Negated, which is the same orientation: a quaternion and its negation
        // draw the same thing, and the client's shortest arc treats them alike.
        assertEquals(0f, full.x(), EPSILON);
        assertEquals(-1f, full.w(), EPSILON);
    }

    @Test
    @DisplayName("composition applies the receiver first")
    void orderIsReadable() {
        Rotation tilt = Rotation.around(Rotation.Axis.X, Math.PI / 2);
        Rotation spin = Rotation.around(Rotation.Axis.Y, Math.PI / 2);

        // tilt.then(spin) is spin * tilt: the model is tipped, then the tipped
        // model is turned. Written the other way round it would be a different
        // orientation, which is the bug this asserts against.
        Rotation composed = tilt.then(spin);

        assertEquals(0.5f, composed.x(), EPSILON);
        assertEquals(0.5f, composed.y(), EPSILON);
        assertEquals(-0.5f, composed.z(), EPSILON);
        assertEquals(0.5f, composed.w(), EPSILON);
    }

    @Test
    @DisplayName("an axis is read from configuration, defaulting to yaw")
    void axisFromConfiguration() {
        assertEquals(Rotation.Axis.X, Rotation.Axis.of("x"));
        assertEquals(Rotation.Axis.Z, Rotation.Axis.of("ROLL"));
        assertEquals(Rotation.Axis.Y, Rotation.Axis.of("nonsense"));
    }
}
