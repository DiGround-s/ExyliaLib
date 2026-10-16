package net.exylia.lib.web.internal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.exylia.lib.config.ConfigIssue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The browser's values go through the same binder as the file: same shape out, same rules in. */
class ValueCodecTest {

    private final Board board = new Board()
            .withEntries(Map.of("spawn", new Board.Entry(true, 2.5, new Board.Click(List.of("console: say hi")))))
            .withLines(List.of("<size:2>{primary}HELLO", "%player_name%"));

    @Test
    void valuesRoundTripThroughJson() {
        JsonObject json = ValueCodec.toJson(Board.class, "Test", board);
        // A key with a unit stays the bare number the file writes; the schema carries the unit.
        assertEquals(20, json.get("refresh").getAsLong());
        assertEquals("5s", json.get("lasts").getAsString());
        assertEquals("fast", json.get("mode").getAsString());
        assertEquals(2.5, json.getAsJsonObject("entries").getAsJsonObject("spawn").get("scale").getAsDouble());

        ValueCodec.Decoded<Board> back = ValueCodec.fromJson(Board.class, "Test", new Board(), json, "board");
        assertFalse(back.rejected(), () -> back.issues().toString());
        assertEquals(board, back.value());
    }

    @Test
    void aKeyLeftOutKeepsTheCurrentValueAndAnUnknownOneIsDropped() {
        JsonObject json = JsonParser.parseString("{\"mode\":\"slow-motion\",\"bogus\":1}").getAsJsonObject();
        ValueCodec.Decoded<Board> back = ValueCodec.fromJson(Board.class, "Test", board, json, "board");
        assertFalse(back.rejected());
        assertEquals(Board.Mode.SLOW_MOTION, back.value().mode());
        assertEquals(board.entries(), back.value().entries());
        assertTrue(back.issues().stream().anyMatch(issue -> issue.type() == ConfigIssue.Type.UNKNOWN_KEY));
    }

    @Test
    void aValueOfTheWrongShapeIsRefused() {
        JsonObject json = ValueCodec.toJson(Board.class, "Test", board);
        json.getAsJsonObject("entries").getAsJsonObject("spawn").addProperty("scale", "wide");
        ValueCodec.Decoded<Board> back = ValueCodec.fromJson(Board.class, "Test", board, json, "board");
        assertTrue(back.rejected());
        assertEquals(ConfigIssue.Type.INVALID_VALUE, back.problems().get(0).type());
    }

    @Test
    void theHashFollowsTheValues() {
        String first = ValueCodec.hash(ValueCodec.toJson(Board.class, "Test", board));
        assertEquals(first, ValueCodec.hash(ValueCodec.toJson(Board.class, "Test", board)));
        assertNotEquals(first, ValueCodec.hash(ValueCodec.toJson(Board.class, "Test", board.withLook(new Board.Look(true)))));
    }
}
