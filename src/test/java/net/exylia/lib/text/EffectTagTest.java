package net.exylia.lib.text;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.effect.internal.EffectRuntime;
import net.exylia.lib.effect.internal.Packets;
import net.exylia.lib.text.internal.EffectTag;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Effects written into a message, in ExyliaCommons' notation.
 *
 * <p>The notation is not ours to redesign: existing message files have to
 * keep working without being touched.
 */
class EffectTagTest {

    private FakePlayer viewer;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Packets.override(false);
        Colors.apply(new Palette());

        Plugin plugin = FakeServer.newPlugin("TagTest", null);
        Effects.owner(plugin);
        viewer = new FakePlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        EffectRuntime.stopEverything();
        FakeServer.reset();
        Packets.reset();
    }

    // ------------------------------------------------------------------
    // Parsing — commons' notation, unchanged
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a sound is read from the tag")
    void readsSound() {
        EffectTag.Parsed parsed = EffectTag.parse("[sound:ENTITY_PLAYER_LEVELUP]Hello");

        assertEquals(1, parsed.sounds().size());
        assertEquals("ENTITY_PLAYER_LEVELUP", parsed.sounds().get(0));
        assertEquals("Hello", parsed.message());
    }

    @Test
    @DisplayName("a sound keeps its volume and pitch, separated by pipes")
    void readsSoundArguments() {
        EffectTag.Parsed parsed = EffectTag.parse("[sound:PLING|0.5|1.8]Hi");

        assertEquals("PLING|0.5|1.8", parsed.sounds().get(0));
        String[] parts = EffectTag.arguments(parsed.sounds().get(0));
        assertEquals("PLING", parts[0]);
        assertEquals(0.5, EffectTag.number(parts, 1, 1), 0.001);
        assertEquals(1.8, EffectTag.number(parts, 2, 1), 0.001);
    }

    @Test
    @DisplayName("several kinds are separated by semicolons")
    void readsSeveralKinds() {
        EffectTag.Parsed parsed =
                EffectTag.parse("[sound:PLING;particle:FLAME|20;center]Done");

        assertEquals(1, parsed.sounds().size());
        assertEquals(1, parsed.particles().size());
        assertTrue(parsed.centered());
        assertEquals("Done", parsed.message());
    }

    @Test
    @DisplayName("several of one kind are separated by commas")
    void readsSeveralOfAKind() {
        EffectTag.Parsed parsed = EffectTag.parse("[sound:PLING,LEVELUP]Hi");

        assertEquals(2, parsed.sounds().size());
        assertEquals("LEVELUP", parsed.sounds().get(1));
    }

    @Test
    @DisplayName("both the singular and plural names work, as in commons")
    void acceptsBothNames() {
        assertEquals(1, EffectTag.parse("[sounds:PLING]x").sounds().size());
        assertEquals(1, EffectTag.parse("[particles:FLAME]x").particles().size());
        assertEquals(1, EffectTag.parse("[fireworks:BALL]x").fireworks().size());
    }

    @Test
    @DisplayName("centered is accepted as well as center")
    void acceptsBothCentreSpellings() {
        assertTrue(EffectTag.parse("[center]x").centered());
        assertTrue(EffectTag.parse("[centered]x").centered());
    }

    // ------------------------------------------------------------------
    // Things that are not tags
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a message with no tag is untouched")
    void plainMessage() {
        EffectTag.Parsed parsed = EffectTag.parse("Just a message");

        assertEquals("Just a message", parsed.message());
        assertFalse(parsed.hasEffects());
    }

    @Test
    @DisplayName("a bracketed prefix that means nothing to us stays in the text")
    void unknownPrefixIsKept() {
        // "[Server] Restarting" must not lose its prefix.
        EffectTag.Parsed parsed = EffectTag.parse("[Server] Restarting");

        assertEquals("[Server] Restarting", parsed.message());
    }

    @Test
    @DisplayName("an unclosed bracket is text, not a broken tag")
    void unclosedBracket() {
        EffectTag.Parsed parsed = EffectTag.parse("[WARN this never closes");

        assertEquals("[WARN this never closes", parsed.message());
    }

    @Test
    @DisplayName("a tag only counts at the very start")
    void tagMustLeadTheLine() {
        EffectTag.Parsed parsed = EffectTag.parse("Hello [sound:PLING]");

        assertFalse(parsed.hasEffects());
        assertEquals("Hello [sound:PLING]", parsed.message());
    }

    @Test
    @DisplayName("a tag mid-line is guarded twice over")
    void midLineTagIsGuardedTwice() {
        // The leading-bracket check is the first guard; even without it, the
        // text before the bracket would not parse as a kind. Verified by
        // sabotage: removing the check alone changes nothing, so this is
        // defence rather than a single point of failure.
        EffectTag.Parsed parsed = EffectTag.parse("x[sound:PLING]y");

        assertFalse(parsed.hasEffects());
        assertEquals("x[sound:PLING]y", parsed.message());
    }

    @Test
    @DisplayName("an unknown kind is ignored without eating the message")
    void unknownKindIsIgnored() {
        EffectTag.Parsed parsed = EffectTag.parse("[sound:PLING;wobble:9]Hi");

        assertEquals(1, parsed.sounds().size());
        assertEquals("Hi", parsed.message());
    }

    // ------------------------------------------------------------------
    // What the player ends up with
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the tag never reaches the screen")
    void tagIsNotShown() {
        assertEquals("Hello", Text.of("[sound:PLING]Hello").plain());
    }

    @Test
    @DisplayName("the tag never reaches a log either")
    void tagIsNotInPlainText() {
        assertEquals("Saved", Text.of("[sound:PLING;particle:FLAME]{success}Saved").plain());
    }

    @Test
    @DisplayName("colour still works alongside a tag")
    void colourSurvives() {
        var component = Text.of("[sound:PLING]{success}Saved").build();

        assertEquals("Saved", PlainText.of(component));
        assertEquals(Colors.get("success"), component.color() != null
                ? component.color() : component.children().get(0).color());
    }

    @Test
    @DisplayName("a centred message is padded, and the padding is spaces")
    void centeredMessageIsPadded() {
        String plain = Text.of("[center]Hi").plain();

        assertTrue(plain.startsWith(" "), "expected padding, got: '" + plain + "'");
        assertEquals("Hi", plain.strip());
    }

    @Test
    @DisplayName("the sound in the tag actually plays")
    void soundIsPlayed() {
        Text.of("[sound:ENTITY_PLAYER_LEVELUP]Well done").send(viewer.player());

        assertEquals(1, viewer.sounds().size(),
                "the whole point of the tag is that the effect happens");
        assertEquals("Well done", viewer.messages().get(0));
    }

    @Test
    @DisplayName("a message with no tag plays nothing")
    void plainMessagePlaysNothing() {
        Text.of("Just text").send(viewer.player());

        assertTrue(viewer.sounds().isEmpty());
    }

    @Test
    @DisplayName("the message still arrives when a sound name is nonsense")
    void malformedEffectDoesNotEatTheMessage() {
        Text.of("[sound:NOT_A_REAL_SOUND]Important").send(viewer.player());

        // The message is the point; the effect is decoration.
        assertEquals(1, viewer.messages().size());
        assertEquals("Important", viewer.messages().get(0));
    }

    @Test
    @DisplayName("a console gets the message without the tag and without effects")
    void consoleGetsTextOnly() {
        var console = FakeServer.consoleSender();

        Text.of("[sound:PLING]Restarting").send(console);

        assertEquals("Restarting", FakeServer.consoleMessages().get(0));
    }

    /** Small helper so the assertions above read cleanly. */
    private static final class PlainText {
        static String of(net.kyori.adventure.text.Component component) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(component);
        }
    }
}
