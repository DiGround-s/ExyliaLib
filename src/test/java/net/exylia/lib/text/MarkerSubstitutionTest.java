package net.exylia.lib.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The hand-written marker walk builds exactly the tree Adventure's own
 * {@code replaceText} built, which is what it replaced.
 */
class MarkerSubstitutionTest {

    private static final Pattern ANY_MARKER = Pattern.compile("[\\uE000-\\uE0FF]");

    private static final Component[] VALUES = {
            Component.text("14.3", NamedTextColor.RED),
            MiniMessage.miniMessage().deserialize("<gradient:#8a51c4:#ff6b9d>combo</gradient>"),
            Component.text("x").hoverEvent(HoverEvent.showText(Component.text("inner")))
    };

    private static Component adventure(Component component, Component[] replacements) {
        return component.replaceText(builder -> builder
                .match(ANY_MARKER)
                .replacement((match, ignored) -> {
                    int index = match.group().charAt(0) - '\uE000';
                    return index < replacements.length ? replacements[index] : Component.text(match.group());
                }));
    }

    private static void same(Component component) {
        assertEquals(adventure(component, VALUES), Text.substituteMarkers(component, VALUES));
    }

    @Test
    void markersAnywhereInText() {
        same(Component.text("Health \uE000 left"));
        same(Component.text("\uE000 left"));
        same(Component.text("Health \uE000"));
        same(Component.text("\uE000", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        same(Component.text("\uE000\uE001\uE002"));
        same(Component.text("a\uE000b\uE001c\uE000"));
        same(Component.text("unknown \uE0FF marker"));
    }

    @Test
    void markersInsideParsedTrees() {
        MiniMessage mini = MiniMessage.miniMessage();
        same(mini.deserialize("<gradient:#8a51c4:#ff6b9d>Vida \uE000 y \uE001</gradient> <gray>ping \uE002"));
        same(mini.deserialize("<bold><red>\uE000</red></bold><hover:show_text:'<green>\uE001 hits'>hover</hover>"));
        same(Component.text().append(Component.text("a"), Component.text("\uE001", NamedTextColor.AQUA)
                .append(Component.text(" \uE000"))).build());
    }

    @Test
    void markersInHoverAndTranslations() {
        same(Component.text("\uE002").hoverEvent(HoverEvent.showText(Component.text("outer \uE000"))));
        same(Component.text("plain").hoverEvent(HoverEvent.showText(Component.text("\uE001"))));
        same(Component.translatable("chat.type.text",
                List.of(TranslationArgument.component(Component.text("\uE000")),
                        TranslationArgument.numeric(3))));
    }

    @Test
    void untouchedTreesComeBackAsTheyWere() {
        Component plain = MiniMessage.miniMessage().deserialize("<gradient:#8a51c4:#ff6b9d>no values here</gradient>");
        assertSame(plain, Text.substituteMarkers(plain, VALUES));
    }
}
