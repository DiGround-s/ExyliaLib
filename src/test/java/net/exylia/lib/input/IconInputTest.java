package net.exylia.lib.input;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.util.head.Head;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Choosing what something is drawn as.
 *
 * <p>An icon reaches the same column whichever way it was given, and that
 * column is read back by {@link net.exylia.lib.item.Source}. What is asserted
 * here is the part that decides what may be given: the head prompt in
 * particular, which is the only one a player types into and therefore the only
 * one that can be answered with something that is not an icon at all.
 */
class IconInputTest {

    /** A real texture, as a texture site hands one out. */
    private static final String TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJl"
            + "cy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjIzZmI2NzQyOTcxNmIyMWJjNmU4ZTdkNjY5Y2VkZGY2NWIxM2Uw"
            + "NzkwYTVjZTU1YjJlMDc3YjgyZDE5ZTEyNCJ9fX0=";

    private PluginInputs inputs;
    private Player player;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Plugin plugin = FakeServer.newPlugin("IconTestPlugin", null);
        player = new FakePlayer("Steve").player();
        FakeServer.online(player);
        Inputs.releaseAll();
        inputs = Inputs.of(plugin);
    }

    @Test
    @DisplayName("every head spelling is accepted, and nothing else is")
    void headPromptAcceptsHeadsOnly() {
        for (String head : new String[] {"basehead-" + TEXTURE, "headbase-" + TEXTURE,
                "playerhead-Notch", "playerhead-%player_name%",
                "urlhead-https://textures.minecraft.net/texture/abc"}) {
            assertTrue(IconInput.isHead(head), head);
        }
        // A material typed into the head prompt is the mistake this catches: it
        // would be stored happily and drawn as itself, which is not what the
        // admin who opened the head prompt asked for.
        for (String other : new String[] {"DIAMOND_SWORD", "bytes:rO0ABXNy", "itemsadder:ruby"}) {
            assertFalse(IconInput.isHead(other), other);
        }
    }

    @Test
    @DisplayName("a way has to be offered")
    void atLeastOneWay() {
        assertThrows(InputException.class, () -> inputs.icon(player, "Pick").ways());
        assertThrows(InputException.class,
                () -> inputs.icon(player, "Pick").ways((IconInput.Way) null));
    }

    @Test
    @DisplayName("the same way twice is offered once")
    void waysAreDeduplicated() {
        IconInput request = inputs.icon(player, "Pick");

        assertSame(request, request.ways(IconInput.Way.HEAD, IconInput.Way.HEAD));
    }

    @Test
    @DisplayName("a length nobody can answer is refused when it is set")
    void maxLengthMustBePositive() {
        assertThrows(InputException.class, () -> inputs.icon(player, "Pick").maxLength(0));
        assertThrows(InputException.class, () -> inputs.icon(player, "Pick").maxLength(-1));
    }

    @Test
    @DisplayName("a request needs somebody to ask and something to ask")
    void promptAndPlayerAreRequired() {
        assertThrows(InputException.class, () -> inputs.icon(player, "  "));
        assertThrows(InputException.class, () -> inputs.icon(null, "Pick"));
    }
    @Test
    @DisplayName("an icon is asked for by inserting the item, not by holding it")
    void insertReplacedHeld() {
        List<String> ways = new ArrayList<>();
        for (IconInput.Way way : IconInput.Way.values()) {
            ways.add(way.name());
        }

        // The one that went: holding the item meant closing the screen you were
        // on, finding it, holding it and reopening — and from a menu it could
        // not be done at all.
        assertEquals(List.of("MATERIAL", "INSERT", "HEAD", "BROWSE"), ways);
        assertEquals("Insert an item", IconInput.Way.INSERT.label());
        assertSame(Material.HOPPER, IconInput.Way.INSERT.icon());
    }

    @Test
    @DisplayName("a head can be searched for, not only pasted")
    void browsesTheCatalogue() {
        // Pasting assumes the admin already has the base64. Browsing is for the
        // head they have not found yet, and it answers in the same grammar.
        assertEquals("Browse a head", IconInput.Way.BROWSE.label());
        assertSame(Material.SPYGLASS, IconInput.Way.BROWSE.icon());
        assertTrue(IconInput.isHead(new Head(1, "Cat", "abc123", "Animals").icon()));
    }


}
