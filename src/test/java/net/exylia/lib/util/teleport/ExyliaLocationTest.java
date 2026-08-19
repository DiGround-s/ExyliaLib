package net.exylia.lib.util.teleport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stored format, which is not ours to change.
 *
 * <p>ExyliaCommons wrote these strings into databases and configuration files
 * across the ecosystem. A parser that stops reading one of them does not fail a
 * compile: it orphans every warp and home a server already had, the moment
 * somebody updates a jar.
 */
class ExyliaLocationTest {

    @Test
    @DisplayName("the six-part local format still reads")
    void sixPartsIsLocal() {
        ExyliaLocation place = ExyliaLocation.fromString("world,100.5,64.0,-200.25,90.0,-12.5");

        assertTrue(place.isLocal(), "six parts has never named a server");
        assertNull(place.server());
        assertEquals("world", place.world());
        assertEquals(100.5, place.x(), 0.0001);
        assertEquals(64.0, place.y(), 0.0001);
        assertEquals(-200.25, place.z(), 0.0001);
        assertEquals(90.0f, place.yaw(), 0.0001f);
        assertEquals(-12.5f, place.pitch(), 0.0001f);
    }

    @Test
    @DisplayName("the seven-part format keeps its server name")
    void sevenPartsNamesAServer() {
        ExyliaLocation place = ExyliaLocation.fromString("practice-1,arena,10.0,70.0,20.0,0.0,0.0");

        assertFalse(place.isLocal());
        assertEquals("practice-1", place.server());
        assertEquals("arena", place.world());
    }

    @Test
    @DisplayName("a dash for the server means this server")
    void dashMeansLocal() {
        ExyliaLocation place = ExyliaLocation.fromString("-,world,1.0,2.0,3.0,0.0,0.0");

        assertTrue(place.isLocal(), "a stored dash has always meant 'wherever you are'");
        assertNull(place.server());
    }

    @Test
    @DisplayName("what is written can be read back unchanged")
    void roundTrips() {
        ExyliaLocation original = new ExyliaLocation("lobby", "world", 1.5, 2.5, 3.5, 45f, -20f);

        ExyliaLocation again = ExyliaLocation.fromString(original.toString());

        assertEquals(original, again);
    }

    @Test
    @DisplayName("a local place round-trips through the seven-part form")
    void localRoundTrips() {
        ExyliaLocation original = new ExyliaLocation(null, "world", 1.5, 2.5, 3.5, 45f, -20f);

        ExyliaLocation again = ExyliaLocation.fromString(original.toString());

        assertTrue(again.isLocal());
        assertEquals(original, again);
    }

    @Test
    @DisplayName("toString always writes the seven-part form")
    void alwaysWritesSevenParts() {
        String written = new ExyliaLocation(null, "world", 1, 2, 3, 0, 0).toString();

        // The shorter form loses the server of anything that later crosses a
        // network, and there is no way to tell afterwards which it was.
        assertEquals(7, written.split(",").length, "got: " + written);
        assertTrue(written.startsWith("-,"), "a local place writes a dash, got: " + written);
    }

    @Test
    @DisplayName("a local place matches whatever server reads it")
    void localIsAlwaysTheSameServer() {
        ExyliaLocation place = ExyliaLocation.fromString("world,1,2,3,0,0");

        assertTrue(place.isSameServer("practice-1"));
        assertTrue(place.isSameServer("lobby-3"));
    }

    @Test
    @DisplayName("a named place matches only its own server, whatever the case")
    void namedMatchesItsOwn() {
        ExyliaLocation place = ExyliaLocation.fromString("Practice-1,arena,1,2,3,0,0");

        assertTrue(place.isSameServer("practice-1"), "server names are not case sensitive");
        assertFalse(place.isSameServer("lobby-1"));
    }

    @Test
    @DisplayName("garbage is refused rather than guessed at")
    void garbageIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> ExyliaLocation.fromString("not a location"));
        assertThrows(IllegalArgumentException.class,
                () -> ExyliaLocation.fromString("world,1,2"));
        assertThrows(IllegalArgumentException.class,
                () -> ExyliaLocation.fromString("a,b,world,1,2,3,0,0"));
        assertThrows(IllegalArgumentException.class,
                () -> ExyliaLocation.fromString("world,x,64,0,0,0"));
        assertThrows(IllegalArgumentException.class,
                () -> ExyliaLocation.fromString(",1,2,3,0,0"));
    }
}
