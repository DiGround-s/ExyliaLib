package net.exylia.lib.ragdoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A skin with one colour per part, for bodies that are not players.
 */
class RagdollSkinFlatTest {

    @Test
    @DisplayName("every cell of a flat part is its colour, at any detail")
    void partsAreOneColour() {
        RagdollSkin skin = RagdollSkin.flat(Map.of(
                RagdollPart.HEAD, 0x4A7A3A,
                RagdollPart.ARM_LEFT, 0xFF2E8C8C));

        assertEquals(0x4A7A3A, skin.colour(RagdollPart.HEAD, 0, 0, 1));
        assertEquals(0x4A7A3A, skin.colour(RagdollPart.HEAD, 3, 3, 4));
        int columns = RagdollPart.ARM_LEFT.columns(4);
        int rows = RagdollPart.ARM_LEFT.rows(4);
        assertEquals(0x2E8C8C, skin.colour(RagdollPart.ARM_LEFT, columns - 1, rows - 1,
                columns, rows), "the alpha given is ignored");
        assertNull(skin.cubes(net.exylia.lib.ragdoll.internal.SkinCubes.Quality.values()[0]),
                "no picture, so it is always drawn in blocks");
    }

    @Test
    @DisplayName("a part left out is drawn in the neutral grey")
    void missingPartIsGrey() {
        RagdollSkin skin = RagdollSkin.flat(Map.of(RagdollPart.HEAD, 0x112233));

        assertTrue(skin.has(RagdollPart.HEAD));
        assertFalse(skin.has(RagdollPart.TORSO));
        assertEquals(0x9E9E9E, skin.colour(RagdollPart.TORSO, 0, 0, 1));
    }
}
