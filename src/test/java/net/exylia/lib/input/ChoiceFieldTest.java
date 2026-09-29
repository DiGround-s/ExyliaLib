package net.exylia.lib.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A field answered by picking one of a few options. */
class ChoiceFieldTest {

    private static final FormKey<String> CURRENCY = FormKey.text("currency");
    private static final List<FormField.Option> OPTIONS = List.of(
            new FormField.Option("default", "Default"),
            new FormField.Option("gems", "Gems"));

    @Test
    @DisplayName("an offered key is the answer, spelled the way the option spells it")
    void offeredKey() {
        FormField<String> field = FormField.choice(CURRENCY, "Currency", OPTIONS);
        assertEquals(FormField.Kind.CHOICE, field.kind());
        assertEquals(OPTIONS, field.options());
        InputParser.Parsed<String> parsed = field.parse("GEMS");
        assertTrue(parsed.ok());
        assertEquals("gems", parsed.value());
    }

    @Test
    @DisplayName("anything else is refused with the keys it could have been")
    void otherwiseRefused() {
        InputParser.Parsed<String> parsed = FormField.choice(CURRENCY, "Currency", OPTIONS).parse("coins");
        assertFalse(parsed.ok());
        assertEquals("Choose one of: default, gems", parsed.error());
    }

    @Test
    @DisplayName("a choice with nothing to choose is a mistake in the code")
    void emptyRefused() {
        assertThrows(InputException.class, () -> FormField.choice(CURRENCY, "Currency", List.of()));
    }
}
