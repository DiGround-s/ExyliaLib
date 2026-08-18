package net.exylia.lib.input;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asking several different things in one window.
 *
 * <p>ExyliaCommons could only ask for several <em>numbers</em> at once; a text
 * box beside a checkbox beside a number was not possible, so a plugin that
 * needed five answers asked five separate questions. These tests cover the
 * mixed form: text, number, decimal, flag and duration together, validated in
 * one pass.
 *
 * <p>Everything here goes through {@code parseRaw}, which is the same method
 * the dialog, the Bedrock form and the chat fallback all call. Testing it once
 * tests every transport's understanding of an answer.
 */
class FormInputTest {

    private static final FormKey<String> NAME = FormKey.text("name");
    private static final FormKey<Long> MIN_PLAYERS = FormKey.integer("minPlayers");
    private static final FormKey<Long> MAX_PLAYERS = FormKey.integer("maxPlayers");
    private static final FormKey<BigDecimal> REWARD = FormKey.decimal("reward");
    private static final FormKey<Boolean> RANKED = FormKey.flag("ranked");
    private static final FormKey<Duration> COOLDOWN = FormKey.duration("cooldown");

    private PluginInputs inputs;
    private Player player;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Plugin plugin = FakeServer.newPlugin("FormTestPlugin", null);
        player = new FakePlayer("Steve").player();
        FakeServer.online(player);
        Inputs.releaseAll();
        inputs = Inputs.of(plugin);
    }

    private FormInput arenaForm() {
        return inputs.form(player, "Create an arena")
                .text(NAME, "Name")
                .integer(MIN_PLAYERS, "Minimum players")
                .integer(MAX_PLAYERS, "Maximum players")
                .amount(REWARD, "Reward")
                .flag(RANKED, "Ranked")
                .duration(COOLDOWN, "Cooldown");
    }

    private static Map<String, String> answers(String name, String min, String max,
                                               String reward, String ranked, String cooldown) {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("name", name);
        raw.put("minPlayers", min);
        raw.put("maxPlayers", max);
        raw.put("reward", reward);
        raw.put("ranked", ranked);
        raw.put("cooldown", cooldown);
        return raw;
    }

    @Test
    @DisplayName("one window collects text, numbers, a flag and a duration together")
    void heterogeneousForm() {
        Object parsed = arenaForm().parseRaw(
                answers("Boxing", "2", "8", "10M", "true", "1h30m"));

        FormValues values = assertInstanceOf(FormValues.class, parsed,
                () -> "expected values, got " + parsed);

        assertEquals("Boxing", values.get(NAME));
        assertEquals(2L, values.getLong(MIN_PLAYERS));
        assertEquals(8L, values.getLong(MAX_PLAYERS));
        assertEquals(0, new BigDecimal("10000000").compareTo(values.getDecimal(REWARD)));
        assertTrue(values.getBoolean(RANKED));
        assertEquals(Duration.ofMinutes(90), values.getDuration(COOLDOWN));
    }

    @Test
    @DisplayName("every bad field is reported at once, not one per round trip")
    void allErrorsAtOnce() {
        // Reporting one error, waiting for a correction, then reporting the
        // next is how a six-field form takes six attempts to fill in.
        Object parsed = arenaForm().parseRaw(
                answers("Boxing", "two", "eight", "abc", "maybe", "soon"));

        Validation validation = assertInstanceOf(Validation.class, parsed);
        assertFalse(validation.valid());
        assertEquals(5, validation.fieldErrors().size(),
                () -> "expected every bad field named, got " + validation.fieldErrors());
        assertTrue(validation.fieldErrors().containsKey("minPlayers"));
        assertTrue(validation.fieldErrors().containsKey("cooldown"));
    }

    @Test
    @DisplayName("an error names its field, so it can be shown next to the box")
    void errorsNameTheirField() {
        Object parsed = arenaForm().parseRaw(
                answers("Boxing", "2", "eight", "10", "yes", "30s"));

        Validation validation = assertInstanceOf(Validation.class, parsed);
        assertEquals(Map.of("maxPlayers", "Enter a number."), validation.fieldErrors());
    }

    @Test
    @DisplayName("a rule spanning two fields runs after both have parsed")
    void crossFieldValidation() {
        // The case a per-field validator cannot express: a maximum below the
        // minimum is not wrong about either field on its own.
        FormInput form = arenaForm().validate(values ->
                values.getLong(MIN_PLAYERS) <= values.getLong(MAX_PLAYERS)
                        ? Validation.ok()
                        : Validation.error(MAX_PLAYERS, "The maximum cannot be below the minimum"));

        Object bad = form.parseRaw(answers("Boxing", "10", "5", "10", "no", "30s"));
        Validation validation = assertInstanceOf(Validation.class, bad);
        assertEquals("The maximum cannot be below the minimum",
                validation.fieldErrors().get("maxPlayers"));

        Object good = form.parseRaw(answers("Boxing", "2", "8", "10", "no", "30s"));
        assertInstanceOf(FormValues.class, good);
    }

    @Test
    @DisplayName("a cross-field rule never sees a half-parsed form")
    void crossFieldRunsOnlyWhenFieldsParsed() {
        // If it ran anyway, the rule would read a missing field and throw
        // inside the library rather than report the real problem.
        FormInput form = arenaForm().validate(values -> {
            values.getLong(MIN_PLAYERS);
            return Validation.ok();
        });

        Object parsed = form.parseRaw(answers("Boxing", "nonsense", "8", "10", "no", "30s"));

        Validation validation = assertInstanceOf(Validation.class, parsed);
        assertTrue(validation.fieldErrors().containsKey("minPlayers"));
    }

    @Test
    @DisplayName("a validator that throws is reported, not propagated to the player's thread")
    void throwingValidatorIsContained() {
        FormInput form = arenaForm().validate(values -> {
            throw new IllegalStateException("plugin bug");
        });

        Object parsed = form.parseRaw(answers("Boxing", "2", "8", "10", "no", "30s"));

        assertInstanceOf(Validation.class, parsed);
    }

    @Test
    @DisplayName("an optional field left blank falls back to its default")
    void optionalFields() {
        FormInput form = inputs.form(player, "Settings")
                .field(NAME, FormField.text(NAME, "Name"))
                .field(REWARD, FormField.amount(REWARD, "Reward")
                        .optional()
                        .defaultValue(BigDecimal.ZERO));

        Object parsed = form.parseRaw(Map.of("name", "Boxing", "reward", ""));

        FormValues values = assertInstanceOf(FormValues.class, parsed);
        assertEquals(0, BigDecimal.ZERO.compareTo(values.getDecimal(REWARD)));
    }

    @Test
    @DisplayName("a required field left blank is refused")
    void requiredFields() {
        Object parsed = arenaForm().parseRaw(
                answers("", "2", "8", "10", "no", "30s"));

        Validation validation = assertInstanceOf(Validation.class, parsed);
        assertTrue(validation.fieldErrors().containsKey("name"));
    }

    @Test
    @DisplayName("reading a field the form never had says so, instead of a null later")
    void unknownFieldIsNamed() {
        Object parsed = arenaForm().parseRaw(
                answers("Boxing", "2", "8", "10", "no", "30s"));
        FormValues values = assertInstanceOf(FormValues.class, parsed);

        InputException failure = assertThrows(InputException.class,
                () -> values.get(FormKey.text("nope")));
        assertTrue(failure.getMessage().contains("nope"), failure.getMessage());
    }

    @Test
    @DisplayName("two fields under one key is a caller bug, caught at build time")
    void duplicateKeysAreRefused() {
        // Silently keeping one of them would lose an answer the player gave.
        assertThrows(InputException.class, () -> inputs.form(player, "Broken")
                .text(NAME, "Name")
                .text(NAME, "Name again"));
    }

    @Test
    @DisplayName("a form with no fields is a caller bug")
    void emptyFormIsRefused() {
        assertThrows(InputException.class, () -> inputs.form(player, "Nothing").open());
    }

    @Test
    @DisplayName("an amount field understands 10M, exactly as a pay command does")
    void amountsMatchTheEconomy() {
        Object parsed = arenaForm().parseRaw(
                answers("Boxing", "2", "8", "1.5k", "no", "30s"));

        FormValues values = assertInstanceOf(FormValues.class, parsed);
        assertEquals(0, new BigDecimal("1500").compareTo(values.getDecimal(REWARD)));
    }
}
