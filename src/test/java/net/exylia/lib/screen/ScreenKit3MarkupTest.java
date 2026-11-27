package net.exylia.lib.screen;

import net.exylia.lib.screen.internal.Layout;
import net.exylia.lib.spatial.SpatialPreview;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.exylia.lib.screen.ScreenMarkupTest.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 1.677.0 kit: free cells, a mob in a window, tinted sprites. */
class ScreenKit3MarkupTest {

    private static Node render(String markup, int rows, String... css) {
        ScreenMarkup parsed = ScreenMarkup.parse("kit3", markup, css);
        parsed.source("kits", x -> ScreenMarkupTest.kits(rows));
        assertTrue(parsed.problems().isEmpty(), parsed.problems().toString());
        return new ScreenMarkupTest.Fake(parsed.screen()).render();
    }

    private static Layout.Placed placed(Node root, String id) {
        for (Layout.Placed p : Layout.lay(root, 400, 200)) if (id.equals(p.node().id())) return p;
        throw new AssertionError("no box " + id);
    }

    @Test
    void freeCellsStandWhereTheirLeftAndTopSay() {
        Node root = render("""
                <screen><row><grid id="sky" source="kits" columns="2" rows="2" layout="free" track="%kit_id%">
                  <box class="star"/></grid></row></screen>""", 6, """
                #sky { width: 200px; height: 100px; }
                .star { width: 10px; height: 10px; }
                #sky > :nth-child(1) { left: 22px; top: 6px; }
                .star:nth-child(2) { left: 150px; top: 70px; }""");
        Layout.Placed sky = placed(root, "sky");
        Layout.Placed first = placed(root, "kits@k1");
        Layout.Placed second = placed(root, "kits@k2");
        assertEquals(sky.x() + 22, first.x(), 1e-6);
        assertEquals(sky.y() + 6, first.y(), 1e-6);
        assertEquals(sky.x() + 150, second.x(), 1e-6);
        assertEquals(sky.y() + 70, second.y(), 1e-6);
        assertEquals(10, first.w(), 1e-6);
        // nth-child counts the window: the third cell is unplaced, so it keeps its flow place, the second line's first.
        Layout.Placed third = placed(root, "kits@k3");
        assertEquals(sky.x(), third.x(), 1e-6);
        assertTrue(third.y() > sky.y(), "the third cell sits on the second line");
        // The window is 4 of 6: k5 is on the next page.
        assertNull(find(root, "kits@k5"));
    }

    @Test
    void withoutLayoutFreeLeftAndTopDoNothing() {
        Node root = render("""
                <screen><row><grid id="sky" source="kits" columns="2" rows="2" track="%kit_id%"><box class="star"/></grid></row></screen>""",
                4, "#sky { width: 200px; height: 100px; } .star { width: 10px; height: 10px; } .star:nth-child(1) { left: 22px; top: 6px; }");
        assertNull(find(root, "kits@k1").absolute());
    }

    @Test
    void aFreeListPlacesItsCellsToo() {
        Node root = render("""
                <screen><row><list id="path" source="kits" rows="3" layout="free" track="%kit_id%"><box class="stop"/></list></row></screen>""",
                3, "#path { width: 100px; height: 100px; } .stop { width: 8px; height: 8px; } .stop:nth-child(2) { left: 40px; top: 50px; }");
        Layout.Placed path = placed(root, "path");
        assertEquals(path.x() + 40, placed(root, "kits@k2").x(), 1e-6);
        assertEquals(path.y() + 50, placed(root, "kits@k2").y(), 1e-6);
        assertNull(find(root, "kits@k1").absolute());
    }

    @Test
    void aWindowMobIsAMobPreviewAndAnythingElseLetsTheChildrenStandIn() {
        Node root = render("""
                <screen><window id="pet" mob="iron_golem"/><window id="none" mob="${'arrow'}"><box id="stand-in"/></window></screen>""", 0);
        SpatialPreview.Mob mob = assertInstanceOf(SpatialPreview.Mob.class, find(root, "pet").preview());
        assertEquals(EntityType.IRON_GOLEM, mob.type());
        assertNull(find(root, "none").preview());
        assertNotNull(find(root, "stand-in"));
        assertEquals(SpatialPreview.none(), SpatialPreview.mob(EntityType.PLAYER));
        assertEquals(SpatialPreview.none(), SpatialPreview.mob("minecraft:nothing_here"));
        assertEquals(SpatialPreview.mob(EntityType.WOLF), SpatialPreview.mob("minecraft:wolf"));
    }

    @Test
    void anIconTintColoursItsSprite() {
        Node root = render("""
                <screen><icon id="fish" material="cod" tint="#120d1a"/><icon id="plain" material="cod"/></screen>""", 0);
        assertEquals(TextColor.fromHexString("#120d1a"), colour(find(root, "fish").text()));
        assertTrue(!TextColor.fromHexString("#120d1a").equals(colour(find(root, "plain").text())));
    }

    private static TextColor colour(Component text) {
        if (text.color() != null) return text.color();
        for (Component child : text.children()) {
            TextColor c = colour(child);
            if (c != null) return c;
        }
        return null;
    }

    @Test
    void checkFlagsMisuse() {
        List<String> problems = ScreenMarkup.parse("bad", """
                <screen><box layout="free"/><grid source="kits" layout="ring"><box/></grid>
                  <window mob="arrow"/><box mob="wolf"/><window mob="%pet_mob%"/>
                  <icon tint="dark">cod</icon><box tint="#000000"/><item material="cod" tint="${shade}"/></screen>""").problems();
        // Six misuses, and the unregistered source.
        assertEquals(7, problems.size(), problems.toString());
    }

    @Test
    void nodeTintTakesOnlyAColour() {
        assertEquals("#120d1a", Node.box().tint("#120D1A").tint());
        assertNull(Node.box().tint("dark").tint());
    }
}
