package net.exylia.lib.screen.internal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** A word whose letters are coloured one by one (a gradient) is never broken in the middle. */
class TextWrapWordTest {

    @Test
    void gradientWordStaysWhole() {
        Component text = Component.empty();
        String words = "Find 35 rare crops today";
        for (int i = 0; i < words.length(); i++) {
            text = text.append(Component.text(words.charAt(i), TextColor.color(i * 9 % 256, 100, 200)));
        }
        Layout.Wrapped wrapped = TextWrap.wrap(text, 60);
        String plain = PlainTextComponentSerializer.plainText().serialize(wrapped.text());
        for (String line : plain.split("\n")) assertFalse(line.isBlank());
        assertEquals(words, plain.replace("\n", " "));
    }
}
