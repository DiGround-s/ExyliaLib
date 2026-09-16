package net.exylia.lib.camera;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a written shot turns into.
 *
 * <p>All of it runs without a server, so it can be checked exactly. The
 * carry-over in particular is not cosmetic: it is the whole reason a push-in is
 * one word rather than four, and a frame that quietly forgot the channels it did
 * not mention would send every camera back to where the shot started.
 */
class CameraShotTest {

    private static final double EPSILON = 1e-6;

    private final List<String> problems = new ArrayList<>();

    private CameraShot parse(String text) {
        return CameraShot.parse(text, problems::add);
    }

    @Test
    @DisplayName("a frame carries over everything it does not mention")
    void carriesOver() {
        CameraShot shot = parse("0 distance=4 yaw=30 pitch=20 height=1.8 | 1 distance=2");

        double[] end = shot.at(1000);
        assertEquals(2.0, end[0], EPSILON);
        assertEquals(30.0, end[1], EPSILON);
        assertEquals(20.0, end[2], EPSILON);
        assertEquals(1.8, end[3], EPSILON);
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    @DisplayName("a tilde adds to where the channel already is")
    void relative() {
        CameraShot shot = parse("0 yaw=90 | 1 yaw=~360 ease=linear");

        assertEquals(90.0, shot.at(0)[1], EPSILON);
        assertEquals(270.0, shot.at(500)[1], EPSILON);
        assertEquals(450.0, shot.at(1000)[1], EPSILON);
    }

    @Test
    @DisplayName("a bare word sets the whole frame")
    void preset() {
        CameraShot shot = parse("0 behind | 1 front");

        double[] front = shot.at(1000);
        assertEquals(180.0, front[1], EPSILON);
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    @DisplayName("a camera cannot be put inside the subject or outside the client's tracking")
    void clamped() {
        CameraShot shot = parse("0 distance=0.01 pitch=-400 | 1 distance=900 pitch=400 height=99");

        assertEquals(0.6, shot.at(0)[0], EPSILON);
        assertEquals(-85.0, shot.at(0)[2], EPSILON);

        double[] end = shot.at(1000);
        assertEquals(24.0, end[0], EPSILON);
        assertEquals(85.0, end[2], EPSILON);
        assertEquals(6.0, end[3], EPSILON);
    }

    @Test
    @DisplayName("a shot that would hold somebody for minutes is cut off and said so")
    void durationIsCapped() {
        CameraShot shot = parse("0 behind | 40 yaw=~90 | 40 yaw=~90 | 1 close");

        assertEquals(40_000L, shot.durationMillis());
        assertEquals(2, shot.frames());
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("60 seconds")),
                problems.toString());
    }

    @Test
    @DisplayName("an orbit the client would take the wrong way round is reported")
    void tooFastAroundIsReported() {
        parse("0 behind | 0.1 yaw=~360");

        assertTrue(problems.stream().anyMatch(problem -> problem.contains("the right way")),
                problems.toString());
    }

    @Test
    @DisplayName("a frame that cannot be read costs its own line and nothing else")
    void badWordsAreSkipped() {
        CameraShot shot = parse("0 distance=3 nonsense=4 wobble | 1 distance=1");

        assertEquals(1.0, shot.at(1000)[0], EPSILON);
        assertEquals(2, problems.size(), problems.toString());
    }

    @Test
    @DisplayName("a shot with nothing in it is the shared empty one")
    void emptyIsShared() {
        assertSame(CameraShot.none(), parse("   "));
        assertTrue(CameraShot.none().isEmpty());

        // A single frame of no length has nowhere to go and nothing to play.
        assertTrue(parse("0 close").isEmpty());
        assertFalse(parse("0 close | 1 wide").isEmpty());
    }

    @Test
    @DisplayName("time never runs past either end of the shot")
    void clampedInTime() {
        CameraShot shot = parse("0 distance=5 | 1 distance=1");

        assertEquals(5.0, shot.at(-10_000)[0], EPSILON);
        assertEquals(1.0, shot.at(10_000)[0], EPSILON);
    }
}
