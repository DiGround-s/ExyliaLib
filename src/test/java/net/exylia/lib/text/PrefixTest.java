package net.exylia.lib.text;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The prefix a plugin puts in front of its messages.
 *
 * <p>Players saw a literal {@code %prefix%} in chat because nothing resolved it:
 * the messages file used it, and no part of the library knew what it meant.
 */
class PrefixTest {

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Prefixes.releaseAll();
        plugin = FakeServer.newPlugin("ExyliaClasses");
    }

    @AfterEach
    void tearDown() {
        Prefixes.releaseAll();
        FakeServer.reset();
    }

    @Test
    @DisplayName("a plugin's prefix replaces %prefix% in its messages")
    void prefixIsSubstituted() {
        Prefixes.set(plugin, "EXYLIA CLASSES >");

        String out = Text.from(plugin, "%prefix% You are now Warrior").plain();

        assertEquals("EXYLIA CLASSES > You are now Warrior", out);
    }

    @Test
    @DisplayName("the prefix keeps its own colours")
    void prefixIsFormatted() {
        Prefixes.set(plugin, "&c&lCLASSES");

        // The prefix goes in before parsing, so its formatting is real
        // formatting rather than literal text.
        String legacy = Text.from(plugin, "%prefix% hello").legacy();

        assertFalse(legacy.contains("&c"), "the prefix should be parsed, not shown raw: " + legacy);
        assertTrue(legacy.contains("CLASSES"));
    }

    @Test
    @DisplayName("two plugins each get their own prefix")
    void prefixesArePerPlugin() {
        Plugin other = FakeServer.newPlugin("ExyliaFFA");
        Prefixes.set(plugin, "CLASSES >");
        Prefixes.set(other, "FFA >");

        assertEquals("CLASSES > hi", Text.from(plugin, "%prefix% hi").plain());
        assertEquals("FFA > hi", Text.from(other, "%prefix% hi").plain());
    }

    @Test
    @DisplayName("text that names no plugin leaves %prefix% alone")
    void withoutOwnerPrefixIsUntouched() {
        Prefixes.set(plugin, "CLASSES >");

        // Text.of has no owner, so there is no prefix to use and guessing would
        // be worse than leaving it visible.
        assertEquals("%prefix% hi", Text.of("%prefix% hi").plain());
    }

    @Test
    @DisplayName("a plugin that set no prefix leaves the token visible")
    void noPrefixSetLeavesToken() {
        assertEquals("%prefix% hi", Text.from(plugin, "%prefix% hi").plain());
    }

    @Test
    @DisplayName("setting the prefix again replaces it, as a reload would")
    void prefixCanBeChanged() {
        Prefixes.set(plugin, "OLD >");
        Prefixes.set(plugin, "NEW >");

        assertEquals("NEW > hi", Text.from(plugin, "%prefix% hi").plain());
    }

    @Test
    @DisplayName("disabling a plugin forgets its prefix")
    void releaseForgetsPrefix() {
        Prefixes.set(plugin, "CLASSES >");
        Prefixes.release("ExyliaClasses");

        assertEquals("%prefix% hi", Text.from(plugin, "%prefix% hi").plain());
    }

    @Test
    @DisplayName("a message with no prefix token is unaffected")
    void messageWithoutTokenIsUnchanged() {
        Prefixes.set(plugin, "CLASSES >");

        assertEquals("plain message", Text.from(plugin, "plain message").plain());
    }

    @Test
    @DisplayName("the prefix works alongside an effect tag and centring")
    void prefixWithEffectTag() {
        Prefixes.set(plugin, "CLASSES >");
        FakePlayer player = new FakePlayer("Steve");

        Text.from(plugin, "[sound:BLOCK_NOTE_BLOCK_PLING|1.0|1.0]%prefix% You are now Warrior")
                .send(player.player());

        assertEquals(1, player.messages().size());
        assertTrue(player.messages().get(0).contains("CLASSES > You are now Warrior"),
                "got: " + player.messages().get(0));
        assertEquals(1, player.sounds().size(), "the sound should still play");
    }

    @Test
    @DisplayName("the prefix counts towards a centred line")
    void prefixIsCentredWithTheMessage() {
        // Much wider than the token it replaces: a prefix of a similar width
        // would land in the same place by luck and prove nothing.
        Prefixes.set(plugin, "EXYLIA CLASSES SERVER NETWORK");

        String centred = Text.from(plugin, "[center]%prefix% hi").plain();
        String manual = Text.of(Centering.center("EXYLIA CLASSES SERVER NETWORK hi")).plain();

        // If the prefix went in after centring, the line would be padded for
        // "%prefix% hi" and sit in the wrong place.
        assertEquals(manual, centred);
    }

    @Test
    @DisplayName("the prefix survives placeholder substitution in the same message")
    void prefixAndPlaceholdersTogether() {
        Prefixes.set(plugin, "CLASSES >");

        String out = Text.from(plugin, "%prefix% You are now %class%")
                .with("%class%", "Warrior")
                .plain();

        assertEquals("CLASSES > You are now Warrior", out);
    }

    @Test
    @DisplayName("a prefix without a reset does not bold the message")
    void prefixFormattingStopsAtTheMessage() {
        // ExyliaShields shipped "{primary}&lSHIELDS {muted}» " and every line it
        // sent came out bold.
        Prefixes.set(plugin, "&lSHIELDS »");

        String legacy = Text.from(plugin, "%prefix% hello").legacy();

        assertTrue(legacy.matches("(?s).*»(§r|&r).*hello"), "the message should start unbolded: " + legacy);
        assertEquals("&lSHIELDS »<reset>", Prefixes.get(plugin));
    }

    @Test
    @DisplayName("a prefix that already resets is kept as written")
    void prefixWithResetIsKept() {
        Prefixes.set(plugin, "&lSTAFF &8•&r");
        assertEquals("&lSTAFF &8•&r", Prefixes.get(plugin));

        Prefixes.set(plugin, "{primary}&lMARKET {letters_black}•<reset>");
        assertEquals("{primary}&lMARKET {letters_black}•<reset>", Prefixes.get(plugin));
    }

    @Test
    @DisplayName("an empty prefix stays empty")
    void emptyPrefixStaysEmpty() {
        Prefixes.set(plugin, "  ");

        assertEquals("", Prefixes.get(plugin));
        assertEquals("hi", Text.from(plugin, "%prefix% hi").plain());
    }

    @Test
    @DisplayName("one space follows the prefix however the line and the prefix are written")
    void oneSpaceAfterThePrefix() {
        Prefixes.set(plugin, "MARKET •");
        assertEquals("MARKET • hi", Text.from(plugin, "%prefix% hi").plain());
        assertEquals("MARKET • hi", Text.from(plugin, "%prefix%hi").plain());
        assertEquals("MARKET • hi", Text.from(plugin, "%prefix%   hi").plain());

        // A prefix shipped with its own space, by plugins that write %prefix%text.
        Prefixes.set(plugin, "E-SKINS • ");
        assertEquals("E-SKINS • hi", Text.from(plugin, "%prefix%hi").plain());
        assertEquals("E-SKINS • hi", Text.from(plugin, "%prefix% hi").plain());
    }

    @Test
    @DisplayName("nothing is added after a prefix that ends the line")
    void noSpaceBeforeALineBreak() {
        assertEquals("X •<reset>", Prefixes.fill("%prefix%", "X •"));
        assertEquals("X •<reset>\nhi", Prefixes.fill("%prefix% \nhi", "X •"));
        assertEquals("%prefix% hi", Prefixes.fill("%prefix% hi", null));
        // A prefix handed in by hand is sealed the same way.
        assertEquals("&lX »<reset> hi", Prefixes.fill("%prefix%hi", "&lX » "));
    }
}
