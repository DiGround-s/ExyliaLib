package net.exylia.lib.input.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.input.FormField;
import net.exylia.lib.input.FormInput;
import net.exylia.lib.input.FormKey;
import net.exylia.lib.input.FormValues;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.input.Validation;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Leaving a field as it was, or blank, in the chat fallback.
 *
 * <p>The report: on clients before 1.21.6 a form is asked in chat, a chat line
 * can never be empty, and so the reward editor's "blank for none" fields
 * (permission, condition, delivery message) could not be left blank: no reward
 * could be added from the editor on 1.21.4.
 */
class ChatAnswersTest {

    private static final FormKey<String> NAME = FormKey.text("name");
    private static final FormKey<String> PERMISSION = FormKey.text("permission");

    private Player player;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        player = new FakePlayer("Steve").player();
        FakeServer.online(player);
        Inputs.releaseAll();
    }

    private FormInput form() {
        return Inputs.of(FakeServer.newPlugin("ChatAnswers", null)).form(player, "Add a reward")
                .field(NAME, FormField.text(NAME, "Name").defaultValue("Diamonds"))
                .field(PERMISSION, FormField.text(PERMISSION, "Permission (blank for none)")
                        .optional().defaultValue("vip.rewards"));
    }

    /** Parses the form with the chat lines given, one per field, as ChatTransport records them. */
    private Object answered(String name, String permission) {
        Map<String, String> answers = new HashMap<>();
        for (Map.Entry<String, String> line : Map.of("name", name, "permission", permission).entrySet()) {
            String answer = ChatTransport.formAnswer(line.getValue());
            if (answer != null) answers.put(line.getKey(), answer);
        }
        return form().parseRaw(answers);
    }

    @Test
    @DisplayName("- leaves an optional field blank, which a chat line could not")
    void dashIsBlank() {
        FormValues values = assertInstanceOf(FormValues.class, answered("Gold", "-"));

        assertEquals("Gold", values.getText(NAME));
        assertFalse(values.has(PERMISSION), "blank for none, not the old permission");
    }

    @Test
    @DisplayName("skip keeps the value the field opened with")
    void skipKeeps() {
        FormValues values = assertInstanceOf(FormValues.class, answered("skip", "SKIP"));

        assertEquals("Diamonds", values.getText(NAME));
        assertEquals("vip.rewards", values.getText(PERMISSION));
    }

    @Test
    @DisplayName("a required field is still required when blanked")
    void requiredStaysRequired() {
        Validation refused = assertInstanceOf(Validation.class, answered("-", "-"));

        assertEquals(java.util.Set.of("name"), refused.fieldErrors().keySet());
    }

    @Test
    @DisplayName("any other line is the answer as typed")
    void otherLinesAreAnswers() {
        assertEquals("  rewards.vip ", ChatTransport.formAnswer("  rewards.vip "));
        assertNull(ChatTransport.formAnswer(" Skip "));
        assertEquals("", ChatTransport.formAnswer(" - "));
        assertEquals("-5", ChatTransport.formAnswer("-5"), "a negative number is a number");
    }
}
