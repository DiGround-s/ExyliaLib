package net.exylia.lib.item.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.text.internal.TextEngine;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
