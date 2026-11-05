package net.exylia.lib.text;

import net.exylia.lib.text.internal.TextEngine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Palette gradients, gradients over values, icons and {@code error_light}: the pieces of the lore style. */
class LoreToolkitTest {

    private static final TextColor PRIMARY = TextColor.fromHexString("#8a51c4");
    private static final TextColor SECONDARY_LIGHT = TextColor.fromHexString("#b48fd9");

    @AfterEach
    void reset() {
        TextEngine.smallText(false);
    }

    @Test
    void aPaletteTokenInsideAGradientIsItsBareColour() {
        List<TextComponent> letters = letters(Text.of("<gradient:{primary}:{secondary_light}>ABCD</gradient>").build());
        assertEquals("ABCD", join(letters));
        assertEquals(PRIMARY, letters.get(0).color());
        assertEquals(SECONDARY_LIGHT, letters.get(3).color());
    }

    @Test
    void aTokenOutsideATagIsStillAColourTag() {
        Component line = Text.of("{primary}A <gradient:{info}:{info_light}>B</gradient>").build();
        assertEquals("A B", plain(line));
        assertEquals(PRIMARY, letters(line).get(0).color());
    }

    @Test
    void aGradientRunsAcrossASubstitutedValue() {
        Component line = Text.of("{letters}Queue ▸ <gradient:{primary}:{secondary_light}>&l%kit%</gradient>")
                .with("%kit%", "NoDebuff").build();
        assertEquals("Queue ▸ NoDebuff", plain(line));
        List<TextComponent> name = letters(line).subList("Queue ▸ ".length(), "Queue ▸ NoDebuff".length());
        assertEquals("NoDebuff", join(name));
        assertEquals(PRIMARY, name.get(0).color());
        assertEquals(SECONDARY_LIGHT, name.get(name.size() - 1).color());
        assertFalse(name.get(1).color().equals(name.get(0).color()), "the colour moves along the value");
        assertTrue(boldAt(line, "Queue ▸ ".length()), "bold written inside the gradient is kept");
        assertNoLabel(line);
    }

    @Test
    void aGradientOverTextAndAValueRunsAcrossBoth() {
        Component line = Text.of("<gradient:{primary}:{secondary_light}>#%rank% %name%</gradient>")
                .with("%rank%", "3").with("%name%", "Steve").build();
        List<TextComponent> letters = letters(line);
        assertEquals("#3 Steve", join(letters));
        assertEquals(PRIMARY, letters.get(0).color());
        assertEquals(SECONDARY_LIGHT, letters.get(letters.size() - 1).color());
        assertNoLabel(line);
    }

    @Test
    void aGradientOverAResolvedPlaceholderPairRunsAcrossIt() {
        Component line = Text.component("<gradient:{primary}:{secondary_light}>%p%</gradient>", List.of("%p%", "Alex"));
        List<TextComponent> letters = letters(line);
        assertEquals("Alex", join(letters));
        assertEquals(PRIMARY, letters.get(0).color());
        assertEquals(SECONDARY_LIGHT, letters.get(3).color());
        assertNoLabel(line);
    }

    @Test
    void aValueGradientNeverWritesAnUppercaseKey() {
        // Adventure 4.25 writes hex in capitals; a font key must be lowercase.
        Component line = Text.of("<gradient:#A33B53:#D4617D>%v%</gradient>").with("%v%", "Back").build();
        assertEquals("Back", join(letters(line)));
        assertEquals(TextColor.fromHexString("#a33b53"), letters(line).get(0).color());
        assertNoLabel(line);
    }

    @Test
    void errorLightIsAPaletteRole() {
        Component line = Text.of("{error_light}x").build();
        assertEquals(TextColor.fromHexString("#d4617d"), letters(line).get(0).color());
    }

    @Test
    void withoutSpritesAnIconIsItsGlyphInTheColourAroundIt() {
        // The test classpath has the Adventure of paper-api 1.21.4: no sprites.
        Component line = Text.of("{info}{icon:clock} {letters}Played").build();
        assertEquals("⌚ Played", plain(line));
        assertEquals(TextColor.fromHexString("#59a4ff"), letters(line).get(0).color());
    }

    @Test
    void anIconCanNameItsOwnGlyphAndAnySprite() {
        assertEquals("✧ ♦", plain(Text.of("{icon:clock|✧} {icon:items:item/diamond|♦}").build()));
    }

    @Test
    void anUnknownIconStaysAsWritten() {
        assertEquals("{icon:nothing}", plain(Text.of("{primary}{icon:nothing}").build()));
    }

    @Test
    void smallCapitalsLeaveTheGlyphAlone() {
        TextEngine.smallText(true);
        assertEquals("⌚ ᴘʟᴀʏᴇᴅ", plain(Text.of("{info}{icon:clock} {letters}Played").build()));
    }

    @Test
    void loreWritesTheCardLayout() {
        assertEquals("{letters}Mine {letters_black}▸ <gradient:{primary}:{secondary_light}>Reset</gradient>",
                Lore.name(Lore.Tone.PRIMARY, "Mine", "Reset"));
        assertEquals(List.of(
                "{letters_black}Mine setting",
                "",
                "{error}✎ Information",
                " {letters}Removes the mine.",
                "",
                "{error}✦ Status",
                "  {letters_black}· {info}{icon:clock} {letters}Interval{letters_black}: {info}%interval%",
                "",
                "{error}» Left-click {letters_black}· {letters}delete",
                "{error}» Right-click {letters_black}· {letters}keep",
                "{letters_black}You will be asked to confirm."),
                Lore.of(Lore.Tone.ERROR).subtitle("Mine setting")
                        .section("Information").text("Removes the mine.")
                        .section("✦", "Status").stat("clock", "info", "Interval", "%interval%")
                        .click("Left-click", "delete").click("Right-click", "keep")
                        .note("You will be asked to confirm.").lines());
    }

    @Test
    void aLoreCardParses() {
        Component name = Text.of(Lore.name(Lore.Tone.HIGHLIGHT, "#3", "%player%")).with("%player%", "Steve").build();
        assertEquals("#3 ▸ Steve", plain(name));
        assertNoLabel(name);
    }

    private static void assertNoLabel(Component component) {
        assertNull(component.style().font(), "no paint label reaches the client");
        for (Component child : component.children()) {
            assertNoLabel(child);
        }
    }

    private static boolean boldAt(Component line, int index) {
        int[] cursor = {0};
        Boolean[] found = {null};
        walk(line, false, cursor, index, found);
        return Boolean.TRUE.equals(found[0]);
    }

    private static void walk(Component component, boolean bold, int[] cursor, int index, Boolean[] found) {
        boolean here = component.style().decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE
                || bold && component.style().decoration(TextDecoration.BOLD) != TextDecoration.State.FALSE;
        if (component instanceof TextComponent text) {
            int length = text.content().codePointCount(0, text.content().length());
            if (found[0] == null && index >= cursor[0] && index < cursor[0] + length) {
                found[0] = here;
            }
            cursor[0] += length;
        }
        for (Component child : component.children()) {
            walk(child, here, cursor, index, found);
        }
    }

    /** Every visible character as its own component, with the colour it is drawn in. */
    private static List<TextComponent> letters(Component component) {
        List<TextComponent> result = new ArrayList<>();
        collect(component, null, result);
        return result;
    }

    private static void collect(Component component, TextColor inherited, List<TextComponent> into) {
        TextColor colour = component.color() != null ? component.color() : inherited;
        if (component instanceof TextComponent text) {
            text.content().codePoints().forEach(point ->
                    into.add(Component.text(new String(Character.toChars(point)), colour)));
        }
        for (Component child : component.children()) {
            collect(child, colour, into);
        }
    }

    private static String join(List<TextComponent> letters) {
        StringBuilder text = new StringBuilder();
        letters.forEach(letter -> text.append(letter.content()));
        return text.toString();
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
