package net.exylia.lib.guide;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The questions a trail asks of a route, answered as numbers.
 *
 * <p>The window is the part worth guarding: its markers must sit on fixed
 * multiples of the spacing, or walking one step would move every marker on
 * screen instead of adding one ahead and dropping one behind.
 */
class RouteTest {

    private static final double EPSILON = 1e-9;

    /** An L: ten blocks east, then ten blocks south. */
    private static Route ell() {
        return Route.of(List.of(new Vector(0, 64, 0), new Vector(10, 64, 0), new Vector(10, 64, 10)));
    }

    @Test
    @DisplayName("length and arc are measured along the line")
    void length() {
        Route route = ell();
        assertEquals(20, route.length(), EPSILON);
        Route.Station corner = route.at(10);
        assertEquals(10, corner.x(), EPSILON);
        assertEquals(0, corner.z(), EPSILON);
        Route.Station later = route.at(15);
        assertEquals(10, later.x(), EPSILON);
        assertEquals(5, later.z(), EPSILON);
    }

    @Test
    @DisplayName("a station points the way the route runs, in Minecraft yaw")
    void heading() {
        Route route = ell();
        // East is -90 in Minecraft's yaw, south is 0.
        assertEquals(-90f, route.at(5).yaw(), 1e-4);
        assertEquals(0f, route.at(15).yaw(), 1e-4);
    }

    @Test
    @DisplayName("the nearest point is projected onto a segment, with its distance and arc")
    void closest() {
        Route.Nearest near = ell().closest(4, 64, 3);
        assertEquals(4, near.arc(), EPSILON);
        assertEquals(4, near.x(), EPSILON);
        assertEquals(0, near.z(), EPSILON);
        assertEquals(3, near.distance(), EPSILON);

        Route.Nearest past = ell().closest(30, 64, 30);
        assertEquals(20, past.arc(), EPSILON);
    }

    @Test
    @DisplayName("where a route passes a spot twice, the later stretch wins")
    void doublesBack() {
        Route out = Route.of(List.of(new Vector(0, 64, 0), new Vector(10, 64, 0), new Vector(0, 64, 0)));
        assertEquals(15, out.closest(5, 64, 0).arc(), EPSILON);
    }

    @Test
    @DisplayName("the window is every multiple of the spacing inside it, clamped to the route")
    void window() {
        Route route = ell();
        assertArrayEquals(new int[]{2, 3, 4, 5}, route.stations(2.5, 5, 1.5));
        // A step forward keeps the markers already drawn and adds one.
        assertArrayEquals(new int[]{2, 3, 4, 5, 6}, route.stations(3.0, 6, 1.5));
        // Near the end the window stops at the route's length.
        assertArrayEquals(new int[]{13}, route.stations(19.4, 10, 1.5));
        assertEquals(0, route.stations(20, 10, 1.5).length);
    }

    @Test
    @DisplayName("a marker is snapped to the first surface with room above it")
    void snap() {
        // Ground is the top of y=63; y=64 and above is air.
        Route.Ground flat = (x, y, z) -> y <= 63 ? y + 1.0 : Double.NaN;
        assertEquals(64, Route.snap(0.5, 64.7, 0.5, flat), EPSILON);
        assertEquals(64, Route.snap(0.5, 62.2, 0.5, flat), EPSILON);
        // A slab at y=66 the route is half buried in.
        Route.Ground slab = (x, y, z) -> y == 66 ? 66.5 : y <= 63 ? y + 1.0 : Double.NaN;
        assertEquals(66.5, Route.snap(0.5, 66.0, 0.5, slab), EPSILON);
        // A two-high tunnel: the ceiling at y=66 is never taken for the floor.
        Set<Integer> solid = Set.of(63, 66);
        Route.Ground tunnel = (x, y, z) -> solid.contains(y) || y < 63 ? y + 1.0 : Double.NaN;
        assertEquals(64, Route.snap(0.5, 64.0, 0.5, tunnel), EPSILON);
        // Nothing to stand on nearby: the route's own height.
        assertEquals(80, Route.snap(0.5, 80, 0.5, (x, y, z) -> Double.NaN), EPSILON);
    }

    @Test
    @DisplayName("thinning drops the points a straight stretch does not need")
    void simplify() {
        Route walked = Route.of(List.of(new Vector(0, 64, 0), new Vector(1, 64, 0.05), new Vector(2, 64, 0),
                new Vector(3, 64, -0.05), new Vector(4, 64, 0), new Vector(4, 64, 5)));
        Route thin = walked.simplified(0.2);
        assertEquals(3, thin.size());
        assertEquals(new Vector(4, 64, 0), thin.point(1));
    }

    @Test
    @DisplayName("standing still while recording adds nothing, and an empty route is refused")
    void duplicates() {
        Route route = Route.of(List.of(new Vector(0, 64, 0), new Vector(0, 64, 0), new Vector(1, 64, 0)));
        assertEquals(2, route.size());
        assertThrows(IllegalArgumentException.class, () -> Route.of(List.of()));
    }

    @Test
    @DisplayName("the window slides with the walker all the way to the end of a long route")
    void windowSlides() {
        Route route = Route.of(List.of(new Vector(0, 64, 0), new Vector(300, 64, 0)));
        int lastSeen = -1;
        for (double walked = 0; walked <= 300; walked += 7) {
            Route.Nearest nearest = route.closest(walked, 64, 0.8);
            assertEquals(walked, nearest.arc(), 1e-9);
            int[] window = route.stations(nearest.arc(), 16, 1.5);
            org.junit.jupiter.api.Assertions.assertTrue(window[0] >= walked / 1.5 - 1e-9, "window left behind at " + walked);
            org.junit.jupiter.api.Assertions.assertTrue(window.length <= 12, "window grew at " + walked);
            lastSeen = window[window.length - 1];
        }
        assertEquals(200, lastSeen, "the last marker is at the end of the route");
    }

    @Test
    @DisplayName("a steep climb leaves no column of footprints: one at the foot, then on along the top")
    void climbIsNotAColumn() {
        Route route = Route.of(List.of(new Vector(0, 64, 0), new Vector(1, 74, 0), new Vector(20, 74, 0)));
        double previousX = Double.NaN;
        double previousY = Double.NaN;
        for (int station : route.stations(0, route.length(), 1.5)) {
            if (route.stacked(station, 1.5)) {
                continue;
            }
            Route.Station at = route.at(station * 1.5);
            if (!Double.isNaN(previousX)) {
                boolean sameSpot = Math.abs(at.x() - previousX) < 0.75;
                org.junit.jupiter.api.Assertions.assertFalse(sameSpot && Math.abs(at.y() - previousY) > 1.01,
                        "two footprints stacked at x " + at.x());
            }
            previousX = at.x();
            previousY = at.y();
        }
        // A flat route has nothing stacked.
        Route flat = Route.of(List.of(new Vector(0, 64, 0), new Vector(20, 64, 0)));
        for (int station : flat.stations(0, 20, 1.5)) {
            org.junit.jupiter.api.Assertions.assertFalse(flat.stacked(station, 1.5));
        }
    }
}
