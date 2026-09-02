package net.exylia.lib.text;

import net.exylia.lib.text.internal.FormatScanner;
import net.exylia.lib.text.internal.TextEngine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers what the text module promises: every notation Exylia uses is
 * understood, formatting is actually applied, and the fast paths do not change
 * the result.
 */
class TextModuleTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @BeforeEach
    void setUp() {
        Colors.apply(new Palette());
        TextEngine.invalidate();
    }

    private static String plain(Component component) {
        return PLAIN.serialize(component);
    }

    @Test
    @DisplayName("a placeholder carrying its own colour is still substituted")
    void placeholderWithFormattingInsideIsSubstituted() {
        // What a scoreboard line looks like once the palette token inside the
        // placeholder has been parsed: the token is no longer one component,
        // so matching it whole against the parsed tree finds nothing.
        String raw = "{secondary}RANGO: %rank_or:{error}none%";
        Component component = Text.component(raw,
                java.util.List.of("%rank_or:{error}none%", "&aVIP"));

        assertEquals("RANGO: VIP", plain(component));
    }

    @Test
    @DisplayName("a placeholder inside a gradient is still substituted")
    void placeholderInsideGradientIsSubstituted() {
        Component component = Text.component(
                "<gradient:#8a51c4:#ff6b9d>%streak%</gradient>",
                java.util.List.of("%streak%", "12"));

        assertEquals("12", plain(component));
    }

    /** Reads the colour of the first part that has one. */
    private static TextColor firstColor(Component component) {
        if (component.color() != null) {
            return component.color();
        }
        for (Component child : component.children()) {
            TextColor found = firstColor(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Notations
    // ------------------------------------------------------------------

    @Test
    @DisplayName("legacy colour codes become real colours")
    void legacyCodes() {
        Component component = Text.component("&aHello");

        assertEquals("Hello", plain(component));
        assertEquals(NamedTextColor.GREEN, firstColor(component));
    }

    @Test
    @DisplayName("legacy decorations are applied")
    void legacyDecorations() {
        Component component = Text.component("&lBOLD");

        assertEquals("BOLD", plain(component));
        assertTrue(hasDecoration(component, TextDecoration.BOLD), "bold should be applied");
    }

    @Test
    @DisplayName("legacy hex is understood in both forms")
    void legacyHex() {
        TextColor expected = TextColor.fromHexString("#8a51c4");

        assertEquals(expected, firstColor(Text.component("&#8a51c4Exylia")),
                "&#rrggbb is the common form");
        assertEquals(expected, firstColor(Text.component("&x&8&a&5&1&c&4Exylia")),
                "&x&r&r&g&g&b&b is what older tools produce");
    }

    @Test
    @DisplayName("MiniMessage tags keep working")
    void miniMessage() {
        Component component = Text.component("<red>Danger</red>");

        assertEquals("Danger", plain(component));
        assertEquals(NamedTextColor.RED, firstColor(component));
    }

    @Test
    @DisplayName("gradients work, which legacy strings could never do")
    void gradients() {
        Component component = Text.component("<gradient:#8a51c4:#ff6b9d>Exylia</gradient>");

        assertEquals("Exylia", plain(component));
        assertNotNull(firstColor(component), "a gradient should colour the text");
    }

    @Test
    @DisplayName("palette tokens resolve to the configured colour")
    void paletteTokens() {
        Component component = Text.component("{primary}Welcome");

        assertEquals("Welcome", plain(component));
        assertEquals(TextColor.fromHexString("#8a51c4"), firstColor(component));
    }

    @Test
    @DisplayName("all three notations mix in one string")
    void mixedNotations() {
        // Exactly the shape of a real Exylia item name.
        Component component = Text.component("{primary}&lNAME &8[{success}5&8]");

        assertEquals("NAME [5]", plain(component));
        assertEquals(TextColor.fromHexString("#8a51c4"), firstColor(component));
    }

    @Test
    @DisplayName("an unknown token is left alone for whoever owns it")
    void unknownTokensSurvive() {
        // Plugins use braces for their own placeholders; eating them silently
        // would be a bug that only shows up in production.
        assertEquals("Hello {player}", plain(Text.component("{letters}Hello {player}")));
    }

    @Test
    @DisplayName("a stray angle bracket does not swallow the message")
    void strayAngleBracket() {
        assertEquals("5 < 10 is true", plain(Text.component("5 < 10 is true")));
        assertEquals("use <red to colour", plain(Text.component("use <red to colour")));
    }

    @Test
    @DisplayName("an unknown tag is shown rather than swallowed")
    void unknownTagIsLiteral() {
        // Anything that is not a real tag stays visible, so a message is never
        // silently truncated by a stray bracket.
        assertEquals("<notatag>x", plain(Text.component("<notatag>x")));
        assertEquals("a < b > c", plain(Text.component("a < b > c")));
    }

    @Test
    @DisplayName("real MiniMessage tags are still honoured")
    void realTagsStillWork() {
        // The flip side of the rule above: <reset> is a genuine tag, so it is
        // applied and does not appear in the output.
        assertEquals("ab", plain(Text.component("a<reset>b")));
    }

    @Test
    @DisplayName("a lone ampersand is left as written")
    void loneAmpersand() {
        assertEquals("Tom & Jerry", plain(Text.component("Tom & Jerry")));
    }

    @Test
    @DisplayName("a malformed tag shows the text instead of failing")
    void malformedTagDoesNotThrow() {
        // Losing a message to an exception is worse than showing it raw.
        assertNotNull(Text.component("<gradient:not-a-colour>oops</gradient>"));
    }

    // ------------------------------------------------------------------
    // Fast paths
    // ------------------------------------------------------------------

    @Test
    @DisplayName("plain text skips the parser entirely")
    void plainTextIsDetected() {
        assertTrue(FormatScanner.isPlain(FormatScanner.scan("Steve")));
        assertTrue(FormatScanner.isPlain(FormatScanner.scan("1234")));

        assertFalse(FormatScanner.isPlain(FormatScanner.scan("&aX")));
        assertFalse(FormatScanner.isPlain(FormatScanner.scan("{primary}X")));
        assertFalse(FormatScanner.isPlain(FormatScanner.scan("<red>X")));
    }

    @Test
    @DisplayName("plain text is not cached, formatted text is")
    void cachingOnlyWhereItPays() {
        TextEngine.invalidate();

        Text.component("just a name");
        assertEquals(0, TextEngine.cacheSize(),
                "caching a plain string costs more than building it");

        Text.component("{primary}formatted");
        assertEquals(1, TextEngine.cacheSize(), "formatted text is worth caching");
    }

    @Test
    @DisplayName("the same text is parsed once and reused")
    void repeatedTextIsReused() {
        String line = "{primary}Score: {highlight}100";

        Component first = Text.component(line);
        Component second = Text.component(line);

        assertSame(first, second, "a scoreboard line rebuilt every tick must not re-parse");
    }

    @Test
    @DisplayName("changing the palette invalidates what was cached with it")
    void paletteChangeInvalidatesCache() {
        Component before = Text.component("{primary}X");
        assertEquals(TextColor.fromHexString("#8a51c4"), firstColor(before));

        Palette repainted = new Palette(
                "#00ff00", "#aa76de", "#b48fd9", "#e7cfff", "#a89ab5",
                "#a33b53", "#8fffc1", "#a1ffc3", "#ff9500", "#ffd2a8",
                "#59a4ff", "#7db7ff", "#ff6b9d", "#6c757d", "#ffd700", "#868e96");
        Colors.apply(repainted);

        assertEquals(TextColor.fromHexString("#00ff00"), firstColor(Text.component("{primary}X")),
                "stale colours must not survive a palette change");
    }

    // ------------------------------------------------------------------
    // Substitution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("values are substituted into the text")
    void substitution() {
        Component component = Text.of("{letters}Coins: {highlight}%coins%")
                .with("%coins%", 250)
                .build();

        assertEquals("Coins: 250", plain(component));
    }

    @Test
    @DisplayName("substituting keeps the surrounding text cached")
    void substitutionReusesTheParse() {
        TextEngine.invalidate();
        String template = "{letters}Coins: {highlight}%coins%";

        Text.of(template).with("%coins%", 1).build();
        Text.of(template).with("%coins%", 2).build();
        Text.of(template).with("%coins%", 3).build();

        assertEquals(1, TextEngine.cacheSize(),
                "the template is parsed once no matter how often the value changes");
    }

    @Test
    @DisplayName("a value written inside a gradient is substituted")
    void substitutionInsideGradient() {
        // A gradient colours one character at a time, so the token is split
        // across components before anything is replaced.
        Component component = Text.of("<gradient:#ff4d4d:#ffd700>%streak% kill streak</gradient>")
                .with("%streak%", 7)
                .build();

        assertEquals("7 kill streak", plain(component));
    }

    @Test
    @DisplayName("a substituted value cannot inject formatting")
    void substitutionIsNotParsed() {
        // A player calling themselves "&cX" must not get coloured text.
        Component component = Text.of("Hello %name%").with("%name%", "&cX").build();

        assertEquals("Hello &cX", plain(component));
    }

    @Test
    @DisplayName("a null value becomes empty rather than the word null")
    void nullSubstitution() {
        assertEquals("Value: ", plain(Text.of("Value: %v%").with("%v%", null).build()));
    }

    @Test
    @DisplayName("with() does not modify the text it was called on")
    void withIsImmutable() {
        Text base = Text.of("%a% %b%");
        Text one = base.with("%a%", "1");
        Text two = base.with("%b%", "2");

        assertEquals("%a% %b%", plain(base.build()), "the original must be untouched");
        assertEquals("1 %b%", plain(one.build()));
        assertEquals("%a% 2", plain(two.build()));
    }

    // ------------------------------------------------------------------
    // Output formats
    // ------------------------------------------------------------------

    @Test
    @DisplayName("plain() strips every kind of formatting")
    void plainOutput() {
        assertEquals("NAME [5]", Text.of("{primary}&lNAME &8[{success}5&8]").plain());
    }

    @Test
    @DisplayName("legacy() produces a section-sign string for old APIs")
    void legacyOutput() {
        String legacy = Text.of("&aHello").legacy();

        assertTrue(legacy.contains("Hello"));
        assertTrue(legacy.indexOf('\u00a7') >= 0, "should use the section sign: " + legacy);
    }

    @Test
    @DisplayName("empty text is handled")
    void emptyText() {
        assertEquals("", plain(Text.component("")));
    }

    // ------------------------------------------------------------------
    // Colours as values
    // ------------------------------------------------------------------

    @Test
    @DisplayName("palette colours are available as values")
    void coloursAsValues() {
        assertEquals(TextColor.fromHexString("#8a51c4"), Colors.get("primary"));
        assertEquals(TextColor.fromHexString("#ff9500"), Colors.get("warning"),
                "the Java warning colour is #ff9500");
        assertNull(Colors.get("nope"));
        assertEquals(NamedTextColor.RED, Colors.get("nope", NamedTextColor.RED));
    }

    @Test
    @DisplayName("both snake_case and camelCase token names work")
    void tokenAliases() {
        assertEquals(Colors.get("letters_black"), Colors.get("lettersBlack"));
        assertEquals(TextColor.fromHexString("#a89ab5"), Colors.get("letters_black"));
    }

    private static boolean hasDecoration(Component component, TextDecoration decoration) {
        if (component.decoration(decoration) == TextDecoration.State.TRUE) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasDecoration(child, decoration)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Clicks
    // ------------------------------------------------------------------

    /**
     * The reported defect: the value reached the message a player reads and the
     * button still ran "/events join %event_id%" verbatim, because a command is
     * not text and replaceText only walks text.
     */
    @Test
    @DisplayName("a substitution reaches the command a click runs")
    void substitutionReachesTheClickCommand() {
        Component component = Text
                .of("<click:run_command:'/events join %event_id%'>{success}CLICK TO JOIN</click>")
                .with("%event_id%", "koth_1")
                .build();

        assertEquals("/events join koth_1", firstClick(component).value());
        assertEquals("CLICK TO JOIN", plain(component));
    }

    @Test
    @DisplayName("a click with nothing to substitute is left alone")
    void clickWithoutPlaceholder() {
        Component component = Text.of("<click:run_command:'/events list'>LIST</click>")
                .with("%event_id%", "koth_1")
                .build();

        assertEquals("/events list", firstClick(component).value());
    }

    /** Reads the click of the first part that has one. */
    private static net.kyori.adventure.text.event.ClickEvent firstClick(Component component) {
        if (component.clickEvent() != null) {
            return component.clickEvent();
        }
        for (Component child : component.children()) {
            net.kyori.adventure.text.event.ClickEvent found = firstClick(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
