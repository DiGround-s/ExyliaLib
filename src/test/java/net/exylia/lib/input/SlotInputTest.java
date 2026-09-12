package net.exylia.lib.input;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which positions a grid accepts.
 *
 * <p>The picker draws taken positions differently but still submits them, and
 * chat can send any number at all, so the refusal has to live in the request:
 * a taken position must be refused identically wherever the answer came from.
 */
class SlotInputTest {

    private FakePlayer player;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        player = new FakePlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        FakeServer.reset();
    }

    private SlotInput grid() {
        return Inputs.of(FakeServer.newPlugin("Shop", null))
                .slot(player.player(), "Where should it go?", 45)
                .taken(List.of(3, 7));
    }

    @Test
    @DisplayName("a free position is the answer")
    void free() {
        InputParser.Parsed<Integer> parsed = grid().parseRaw("12");
        assertTrue(parsed.ok());
        assertEquals(12, parsed.value());
    }

    @Test
    @DisplayName("a taken position is refused, whichever transport sent it")
    void taken() {
        InputParser.Parsed<Integer> parsed = grid().parseRaw("7");
        assertFalse(parsed.ok());
        assertEquals("That position is already taken.", parsed.error());
    }

    @Test
    @DisplayName("a position outside the grid is refused")
    void outside() {
        assertFalse(grid().parseRaw("45").ok());
        assertFalse(grid().parseRaw("-2").ok());
    }

    @Test
    @DisplayName("the next-free answer is refused unless it was offered")
    void auto() {
        assertFalse(grid().parseRaw("-1").ok());
        InputParser.Parsed<Integer> parsed = grid().auto().parseRaw("-1");
        assertTrue(parsed.ok());
        assertEquals(SlotInput.AUTO, parsed.value());
    }

    @Test
    @DisplayName("only a taken position names what occupies it")
    void occupant() {
        SlotInput grid = grid().describe(slot -> "Diamond Sword");
        assertEquals("Diamond Sword", grid.occupant(3));
        assertNull(grid.occupant(4));
    }
}
