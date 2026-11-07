package net.exylia.lib.screen.internal;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PicturesTest {

    /** A skin whose every part is painted its own colour, so the front view shows where each one landed. */
    private static BufferedImage skin() {
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        paint(skin, 8, 8, 8, 8, 0xFF000001);    // head front
        paint(skin, 20, 20, 8, 12, 0xFF000002); // body front
        paint(skin, 44, 20, 4, 12, 0xFF000003); // right arm front
        paint(skin, 36, 52, 4, 12, 0xFF000004); // left arm front
        paint(skin, 4, 20, 4, 12, 0xFF000005);  // right leg front
        paint(skin, 20, 52, 4, 12, 0xFF000006); // left leg front
        paint(skin, 40, 8, 1, 1, 0xFF000007);   // one hat pixel over the head's top left
        return skin;
    }

    private static void paint(BufferedImage image, int x, int y, int w, int h, int argb) {
        for (int dy = 0; dy < h; dy++) for (int dx = 0; dx < w; dx++) image.setRGB(x + dx, y + dy, argb);
    }

    @Test
    void theFrontStandsEachPartWhereTheBodyHasIt() {
        Pictures.Picture body = Pictures.front(skin(), false);
        assertEquals(16, body.width());
        assertEquals(32, body.height());
        int[] p = body.argb();
        assertEquals(0xFF000007, p[0 * 16 + 4]);  // the hat covers the head
        assertEquals(0xFF000001, p[1 * 16 + 5]);
        assertEquals(0xFF000002, p[8 * 16 + 4]);
        assertEquals(0xFF000003, p[8 * 16 + 0]);  // the wearer's right arm on the picture's left
        assertEquals(0xFF000004, p[8 * 16 + 15]);
        assertEquals(0xFF000005, p[20 * 16 + 4]);
        assertEquals(0xFF000006, p[20 * 16 + 8]);
        assertEquals(0, p[0]);                     // beside the head is see-through
    }

    @Test
    void aSlimArmIsThreePixelsAgainstTheBody() {
        int[] p = Pictures.front(skin(), true).argb();
        assertEquals(0, p[8 * 16 + 0]);
        assertEquals(0xFF000003, p[8 * 16 + 1]);
        assertEquals(0xFF000004, p[8 * 16 + 14]);
        assertEquals(0, p[8 * 16 + 15]);
    }

    @Test
    void armourIsLaidOverThePartsItCovers() {
        // A 64 by 32 armour layer: the chestplate's front of the body and of the right arm painted.
        BufferedImage chest = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        paint(chest, 20, 20, 8, 12, 0xFF0000AA);
        paint(chest, 44, 20, 4, 12, 0xFF0000BB);
        BufferedImage boots = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        paint(boots, 4, 29, 4, 3, 0xFF0000CC);
        Outfit outfit = new Outfit(java.util.List.of(), java.util.List.of(chest), java.util.List.of(),
                java.util.List.of(boots), new Pictures.Picture(16, 16, new int[256]));
        java.util.List<Pictures.Face> sides = Pictures.sides(skin(), false, outfit);
        assertEquals(6 * 4 + 1, sides.size());   // six boxes and the held item
        int[] p = Pictures.front(sides).argb();
        assertEquals(0xFF0000AA, p[8 * 16 + 4]);  // the chestplate over the body
        assertEquals(0xFF0000BB, p[8 * 16 + 0]);  // over the right arm
        assertEquals(0xFF0000BB, p[8 * 16 + 15]); // and, mirrored, the left one
        assertEquals(0xFF000001, p[1 * 16 + 5]);  // no helmet: the face shows
        assertEquals(0xFF000005, p[20 * 16 + 4]); // the boots cover only the foot
        assertEquals(0xFF0000CC, p[31 * 16 + 4]);
        assertEquals(0xFF0000CC, p[31 * 16 + 8]);
        assertEquals(0.625, sides.get(sides.size() - 1).scale());
    }

    @Test
    void outfitWordsKeepColourAndTrim() {
        var pieces = Outfit.parse("diamond_helmet LEATHER_CHESTPLATE#A06540 iron_leggings@coast:gold ../../x minecraft:bow");
        assertEquals(4, pieces.size());
        assertEquals(0xA06540, pieces.get(1).tint());
        assertEquals("coast", pieces.get(2).trimPattern());
        assertEquals("gold", pieces.get(2).trimMaterial());
        assertEquals("bow", pieces.get(3).material());
        assertEquals("leather_chestplate#a06540", pieces.get(1).token());
        assertEquals("gold", Outfit.asset("golden_boots"));
        assertEquals("turtle_scute", Outfit.asset("turtle_helmet"));
    }
}
