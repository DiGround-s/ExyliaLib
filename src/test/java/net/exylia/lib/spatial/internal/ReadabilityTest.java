package net.exylia.lib.spatial.internal;

import net.exylia.lib.spatial.SpatialTheme;
import net.exylia.lib.stage.StageLook;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadabilityTest {

    @Test
    void theFloorMakesAGlyphLookTheAngleAsked() {
        double scale = Geometry.readableScale(3.6, 0.9, Geometry.CAPITAL_PIXELS);
        assertEquals(0.9, Geometry.glyphDegrees(scale, Geometry.CAPITAL_PIXELS, 3.6), 1e-9);
        assertTrue(Geometry.readableScale(3.6, 0.9, Geometry.SMALL_CAPITAL_PIXELS) > scale,
                "small capitals need a larger scale to read as tall");
        assertTrue(Geometry.readableScale(5, 0.9, 7) > scale, "further away needs larger");
    }

    @Test
    void theDefaultThemeReadsAtTheDefaultDistanceWithoutTheFloor() {
        SpatialTheme theme = new SpatialTheme();
        // Panels stand about 3.6 blocks from the eyes in the libraries' layouts; body lines are drawn as written.
        for (double scale : new double[]{theme.textScale(), theme.smallScale()}) {
            assertTrue(Geometry.glyphDegrees(scale, Geometry.CAPITAL_PIXELS, 3.6) >= theme.minGlyphDegrees(),
                    scale + " reads under " + theme.minGlyphDegrees() + " degrees");
        }
        assertTrue(Geometry.glyphDegrees(theme.titleScale() * 0.8, Geometry.SMALL_CAPITAL_PIXELS, 3.6) >= theme.minGlyphDegrees(),
                "panel titles in small capitals read");
        assertFalse(theme.smallCapsBody());
    }

    @Test
    void longLinesAreCutWithAnEllipsisAndKeepTheirMarkup() {
        String raw = "{primary}&lHit the Griddy Forever";
        String cut = Geometry.truncate(raw, 1.0);
        assertTrue(cut.startsWith("{primary}&l"), cut);
        assertTrue(cut.endsWith("…"), cut);
        assertTrue(Geometry.visibleWidth(cut) <= 1.0 + 1e-9, "fits: " + Geometry.visibleWidth(cut));
        assertEquals(raw, Geometry.truncate(raw, 10), "a line that fits is left alone");
        assertEquals(StageLook.width("Hit"), Geometry.visibleWidth("<#8a51c4>{letters}&lHit"), 1e-9, "markup takes no room");
        assertEquals("a{b c}", Geometry.truncate("a{b c}", 10), "braces that are not a token are letters");
    }

    @Test
    void draftsDifferOnlyFromWhatIsSaved() {
        Drafts drafts = new Drafts();
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("effects", "fire");
        saved.put("look_helmet", "vex");
        Map<String, String> selection = new HashMap<>(Map.of("effects", "fire"));
        assertEquals(Set.of(), drafts.changed(saved, "effects"::equals, selection::get));

        selection.put("effects", "ice");
        drafts.put("look_helmet", "wild", saved);
        assertEquals(java.util.List.of("effects", "look_helmet"),
                java.util.List.copyOf(drafts.changed(saved, "effects"::equals, selection::get)));

        drafts.put("look_helmet", "vex", saved);
        assertEquals("vex", drafts.get("look_helmet", saved), "drafting the saved value is no draft");
        assertEquals(Set.of("effects"), drafts.changed(saved, "effects"::equals, selection::get));

        drafts.put("look_boots", "coast", saved);
        assertTrue(drafts.changed(saved, "effects"::equals, selection::get).contains("look_boots"), "a key the saved map lacks");
        saved.put("look_boots", "coast");
        drafts.prune(saved);
        selection.put("effects", "fire");
        assertEquals(Set.of(), drafts.changed(saved, "effects"::equals, selection::get), "saved: nothing left");
    }
}
