package net.exylia.lib.util.sequence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lines read the way the compiler reads them, and written back the same.
 *
 * <p>The screen and the compiler are two readers of one notation. A line the
 * form takes apart and puts back together unchanged is the whole contract: an
 * admin who opens an effect and presses save must get the file they had.
 */
class SequenceLineTest {

    /** The shapes a stock plugin knows, which is what the picker offers. */
    private static final Set<String> SHAPES =
            net.exylia.lib.util.sequence.internal.Shapes.builtIn().keySet();

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a line is its token, its head and its named arguments")
    void reads() {
        SequenceLine line = SequenceLine.of("[CIRCLE] FLAME;radius:1.5;points:24");

        assertEquals("CIRCLE", line.token());
        assertEquals("FLAME", line.head());
        assertEquals("1.5", line.value("radius"));
        assertEquals("24", line.value("points"));
        assertEquals("", line.value("scale"));
    }

    @Test
    @DisplayName("a headless token keeps its first argument")
    void headless() {
        SequenceLine line = SequenceLine.of("[FIREWORK];color:red;power:1");

        assertEquals("FIREWORK", line.token());
        assertEquals("", line.head());
        assertEquals("red", line.value("color"));
        assertEquals("1", line.value("power"));
    }

    @Test
    @DisplayName("a title is read by position")
    void positional() {
        SequenceLine line = SequenceLine.of("[TITLE] Welcome;to the mines;0.5;3;1");

        assertEquals("Welcome", line.segment(0));
        assertEquals("to the mines", line.segment(1));
        assertEquals("1", line.segment(4));
        assertEquals("", line.segment(5));
    }

    @Test
    @DisplayName("a line with no token is not a line, and does not throw")
    void garbage() {
        SequenceLine line = SequenceLine.of("FLAME;count:20");

        assertEquals("", line.token());
        assertFalse(line.isPlayable());
        assertTrue(SequenceLine.spec(line.token(), SHAPES).isFree());
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a line survives being taken apart and put back together")
    void roundTrip() {
        String written = "[CIRCLE] FLAME;radius:1.5;points:24";
        SequenceLine line = SequenceLine.of(written);

        Map<String, String> values = new LinkedHashMap<>();
        for (SequenceLine.Field field : SequenceLine.spec(line.token(), SHAPES).fields()) {
            values.put(field.key(), line.value(field.key()));
        }

        assertEquals(written, SequenceLine.write(line.token(), line.head(), values));
    }

    @Test
    @DisplayName("blank answers are left out rather than written as defaults")
    void blanksAreNotWritten() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("count", "20");
        values.put("speed", "");
        values.put("color", "   ");

        assertEquals("[PARTICLE] FLAME;count:20",
                SequenceLine.write("PARTICLE", "FLAME", values));
    }

    @Test
    @DisplayName("a headless token is written the way the compiler reads one")
    void writesHeadless() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("color", "red");

        String written = SequenceLine.write("FIREWORK", "", values);

        assertEquals("[FIREWORK];color:red", written);
        assertEquals("red", SequenceLine.of(written).value("color"));
    }

    @Test
    @DisplayName("trailing empty parts of a title are dropped")
    void writesPositional() {
        assertEquals("[TITLE] Welcome",
                SequenceLine.writePositional("TITLE", List.of("Welcome", "", "", "", "")));
        assertEquals("[TITLE] Welcome;;0.5",
                SequenceLine.writePositional("TITLE", List.of("Welcome", "", "0.5", "", "")));
        assertEquals("[TITLE]",
                SequenceLine.writePositional("TITLE", List.of("", "", "", "", "")));
    }

    @Test
    @DisplayName("a line that says nothing keeps its token")
    void writesFree() {
        assertEquals("[EXPLOSION]", SequenceLine.writeFree("EXPLOSION", "  "));
        assertEquals("[DELAY] 0.2", SequenceLine.writeFree("DELAY", " 0.2 "));
    }

    // ------------------------------------------------------------------
    // What the form asks for
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every token the compiler plays is offered by the picker")
    void offersEveryToken() {
        List<String> tokens = SequenceLine.tokens(SHAPES);

        for (String token : List.of("PARTICLE", "SOUND", "POTION", "FIREWORK", "TITLE",
                "ACTION_BAR", "MESSAGE", "COMMAND", "LIGHTNING", "EXPLOSION",
                "BLOCK_BREAK", "DELAY")) {
            assertTrue(tokens.contains(token), token + " is missing from the picker");
        }
        for (String shape : SHAPES) {
            assertTrue(tokens.contains(shape.toUpperCase(java.util.Locale.ROOT)),
                    shape + " is missing from the picker");
        }
    }

    @Test
    @DisplayName("a shape is asked for its own parameters before the shared ones")
    void shapeFieldsComeFirst() {
        List<SequenceLine.Field> fields = SequenceLine.spec("SPIRAL", SHAPES).fields();
        List<String> keys = fields.stream().map(SequenceLine.Field::key).toList();

        assertEquals(List.of("height", "radius", "turns", "points"), keys.subList(0, 4));
        assertTrue(keys.contains("ticks"));
        assertTrue(keys.contains("rotate"));
        assertEquals(SequenceLine.Head.PARTICLE, SequenceLine.spec("SPIRAL", SHAPES).head());
    }

    @Test
    @DisplayName("each token asks for what it reads, and for a head only if it takes one")
    void headsMatchTheCompiler() {
        assertEquals(SequenceLine.Head.PARTICLE, SequenceLine.spec("PARTICLE", SHAPES).head());
        assertEquals(SequenceLine.Head.SOUND, SequenceLine.spec("SOUND", SHAPES).head());
        assertEquals(SequenceLine.Head.POTION, SequenceLine.spec("POTION", SHAPES).head());
        assertEquals(SequenceLine.Head.MATERIAL, SequenceLine.spec("BLOCK_BREAK", SHAPES).head());
        assertEquals(SequenceLine.Head.NONE, SequenceLine.spec("FIREWORK", SHAPES).head());
        assertEquals(SequenceLine.Head.NONE, SequenceLine.spec("LIGHTNING", SHAPES).head());
        assertTrue(SequenceLine.spec("MESSAGE", SHAPES).isFree());
        assertTrue(SequenceLine.spec("DELAY", SHAPES).isFree());
        assertEquals(SequenceLine.Form.POSITIONAL, SequenceLine.spec("TITLE", SHAPES).form());
    }

    @Test
    @DisplayName("a shape a plugin registered is editable without the library knowing it")
    void unknownShapeIsStillAShape() {
        SequenceLine.Spec spec = SequenceLine.spec("HEART", Set.of("heart"));

        assertEquals(SequenceLine.Head.PARTICLE, spec.head());
        assertEquals(SequenceLine.Form.NAMED, spec.form());
        assertTrue(spec.fields().stream().anyMatch(field -> field.key().equals("color")));
    }
}
