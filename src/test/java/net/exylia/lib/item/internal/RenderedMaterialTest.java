package net.exylia.lib.item.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.item.Source;
import net.exylia.lib.text.internal.TextEngine;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * What a placeholder resolves to when it names a thing rather than says one.
 *
 * <p>A row value reaches two very different places. {@code %effect_raw_name%}
 * lands in a line a player reads; {@code %effect_material%} lands in
 * {@code material:}, where it has to come back out as a registry key the server
 * can look up. Sending both through the text module is what put
 * {@code ғɪʀᴇ_ᴄʜᴀʀɢᴇ} into a registry lookup on a live server: every icon in the
 * menu drew as stone, with one console line each.
 *
 * <p>Every test here renders <em>for a viewer</em>, because that is the path
 * that reached the text module at all. With nobody looking the value was never
 * transformed, which is exactly why this survived the test suite.
 */
class RenderedMaterialTest {

    private Player viewer;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        viewer = new FakePlayer("DiGround").player();
        TextEngine.smallText(true);
    }

    @AfterEach
    void tearDown() {
        TextEngine.smallText(false);
        FakeServer.reset();
    }

    @Test
    @DisplayName("a material read out of a row value stays a registry key")
    void materialKeepsItsLetters() {
        // What KillEffect's menu does: material: "%effect_material%".
        String resolved = ItemRenderer.value("%effect_material%", viewer,
                Map.of("effect_material", "FIRE_CHARGE"));

        // Not ғɪʀᴇ_ᴄʜᴀʀɢᴇ. No registry has ever heard of that, so the item falls
        // back to stone and the console fills with one line per row.
        assertEquals("FIRE_CHARGE", resolved);
    }

    @Test
    @DisplayName("a head's owner keeps its letters too")
    void headOwnerKeepsItsLetters() {
        // playerhead-%player_name%: a name in small capitals is a request to
        // Mojang for a player who does not exist.
        String resolved = ItemRenderer.value("%owner%", viewer,
                Map.of("owner", "DiGround"));

        assertEquals("DiGround", resolved);
    }

    @Test
    @DisplayName("a material written literally is left alone as well")
    void literalMaterialIsUntouched() {
        String resolved = ItemRenderer.value("PACKED_ICE", viewer, Map.of());

        assertEquals("PACKED_ICE", resolved);
    }

    @Test
    @DisplayName("a value that names nothing still comes back whole")
    void unknownPlaceholderIsLeftAsWritten() {
        // Nobody owns %nothing%, so it stays visible rather than becoming the
        // small-capital rendering of its own name.
        String resolved = ItemRenderer.value("%nothing%", viewer, Map.of());

        assertEquals("%nothing%", resolved);
    }

    // ------------------------------------------------------------------
    // What a resolved material turns out to be
    // ------------------------------------------------------------------
    //
    // A menu row writes material: "%arena_icon%", and the value comes from
    // whatever the admin picked — which is a head far more often than a block,
    // because that is what an icon picker stores. Source has to read it once
    // the row has filled it in; reading it only when the file was loaded left
    // the registry being asked for a material called "headbase-eyJ0…", which
    // drew every icon in the menu as stone.

    /** A real texture, as an icon picker writes one. */
    private static final String TEXTURE =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQu"
                    + "bmV0L3RleHR1cmUvOWZkMTA4MzgzZGZhNWIwMmU4NjYzNTYwOTU0MTUyMGU0ZTE1"
                    + "ODk1MmQ2OGMxYzhmOGYyMDBlYzdlODg2NDJkIn19fQ==";

    @Test
    @DisplayName("a head a row hands back is a head, not a material nobody has")
    void resolvedHeadIsReadAgain() {
        Source effective = ItemRenderer.effective(Source.of("%arena_icon%"),
                fill("arena_icon", "headbase-" + TEXTURE));

        assertInstanceOf(Source.OfHead.class, effective);
    }

    @Test
    @DisplayName("both head spellings survive the trip through a row value")
    void bothHeadSpellingsAreRead() {
        for (String written : new String[] {"basehead-" + TEXTURE, "headbase-" + TEXTURE,
                "playerhead-DiGround", "urlhead-https://textures.minecraft.net/texture/abc"}) {
            assertInstanceOf(Source.OfHead.class,
                    ItemRenderer.effective(Source.of("%kit_icon%"), fill("kit_icon", written)),
                    written);
        }
    }

    @Test
    @DisplayName("a serialised stack a row hands back is still a serialised stack")
    void resolvedSnapshotIsReadAgain() {
        Source effective = ItemRenderer.effective(Source.of("%kit_icon%"),
                fill("kit_icon", "bytes:rO0ABXNy"));

        assertInstanceOf(Source.OfSnapshot.class, effective);
    }

    @Test
    @DisplayName("a material a row hands back is the resolved name, not the placeholder")
    void resolvedMaterialCarriesTheAnswer() {
        Source effective = ItemRenderer.effective(Source.of("%effect_material%"),
                fill("effect_material", "FIRE_CHARGE"));

        assertInstanceOf(Source.OfMaterial.class, effective);
        assertEquals("FIRE_CHARGE", effective.raw());
    }

    @Test
    @DisplayName("a material written literally is not read a second time")
    void literalSourceIsUntouched() {
        // The common case, and it must stay free: whatever the file said was
        // decided when the file was read.
        Source declared = Source.of("PACKED_ICE");

        assertSame(declared, ItemRenderer.effective(declared, value -> {
            throw new AssertionError("a literal material must not be resolved");
        }));
    }

    @Test
    @DisplayName("a head decided in the file is not read a second time either")
    void literalHeadIsUntouched() {
        Source declared = Source.of("basehead-" + TEXTURE);

        assertSame(declared, ItemRenderer.effective(declared, value -> {
            throw new AssertionError("a decided head must not be resolved");
        }));
    }

    /** The resolver a row with one value gives the renderer. */
    private UnaryOperator<String> fill(String name, String value) {
        return written -> ItemRenderer.value(written, viewer, Map.of(name, value));
    }
}
