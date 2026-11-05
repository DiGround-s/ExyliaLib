package net.exylia.lib.util.editor;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** {@link Pickers#rgb}: every colour the picker offers reads as itself, not as the fallback. */
class PickerColourTest {

    @Test
    void everyOfferedNameReadsAsItsOwnColour() {
        Set<Integer> seen = new HashSet<>();
        for (String name : Pickers.COLOUR_NAMES) {
            int rgb = Pickers.rgb(name, -1);
            assertNotEquals(-1, rgb, name);
            seen.add(rgb);
        }
        assertEquals(Pickers.COLOUR_NAMES.size(), seen.size(), "two names read as one colour");
    }

    @Test
    void onlyWhiteIsDrawnAsWhiteDye() {
        for (String name : Pickers.COLOUR_NAMES) {
            if (!name.equals("white")) assertNotEquals(org.bukkit.Material.WHITE_DYE, Pickers.dye(name), name);
        }
    }

    @Test
    void hexAndNonsense() {
        assertEquals(0x8a51c4, Pickers.rgb("#8A51C4", -1));
        assertEquals(0x808000, Pickers.rgb("OLIVE", -1));
        assertEquals(-1, Pickers.rgb("not a colour", -1));
        assertEquals(-1, Pickers.rgb(null, -1));
    }
}
