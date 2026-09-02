package net.exylia.lib.util.sequence;

import net.exylia.lib.util.sequence.internal.Args;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The first segment of a line that has no head.
 *
 * <p>Every effect file in the ecosystem writes {@code [FIREWORK] color:red},
 * and the first segment is positional, so the colour was read as the head and
 * dropped. This asserts the fold that puts it back, and that it does not touch
 * the tokens whose head legitimately contains a colon.
 */
class HeadlessArgsTest {

    private static final Args.Problems QUIET = (where, problem) -> { };

    @Test
    @DisplayName("a headless token reads its first segment as a parameter")
    void firstSegmentBecomesNamed() {
        Args args = Args.parse("color:255,80,0;fade:255,200,0;type:BURST", QUIET).asHeadless();

        assertEquals("255,80,0", args.text("color", "missing"));
        assertEquals("255,200,0", args.text("fade", "missing"));
        assertEquals("BURST", args.text("type", "missing"));
    }

    @Test
    @DisplayName("a later segment wins nothing it did not already own")
    void existingNamedValuesSurvive() {
        Args args = Args.parse("count:3;y:2", QUIET).asHeadless();

        assertEquals("3", args.text("count", "missing"));
        assertEquals("2", args.text("y", "missing"));
    }

    @Test
    @DisplayName("a head that is a name is left exactly as it was")
    void plainHeadIsUntouched() {
        Args args = Args.parse("FLAME;count:4", QUIET);

        assertEquals("FLAME", args.asHeadless().head());
        assertEquals("FLAME", args.head());
    }

    @Test
    @DisplayName("a namespaced sound key is not a parameter called minecraft")
    void namespacedKeysAreNotFolded() {
        // Only the three headless tokens ever call asHeadless, but the guard
        // matters: a key read as a parameter is a sound that stops playing.
        Args args = Args.parse("minecraft:block.note_block.pling;1.0;2.0", QUIET);

        assertEquals("minecraft:block.note_block.pling", args.head());
    }
}
