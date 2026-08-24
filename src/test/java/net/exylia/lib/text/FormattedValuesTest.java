package net.exylia.lib.text;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.placeholder.internal.Registry;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Formatted substitutions, and the report that must not cry wolf.
 *
 * <p>Both come from the same live incident: a class display name written as
 * {@code <#c8c8c8><bold>ARCHER</bold>} printed its raw tags in chat, and the
 * unknown-placeholder warning fired for values that were in fact supplied.
 */
class FormattedValuesTest {

    private Plugin plugin;
    private Logger logger;
    private List<String> warnings;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Prefixes.releaseAll();
        // Both are static across tests: the report dedupe lives in the
        // registry, and a compiled template keeps the logger it was compiled
        // with. Without this, whether a warning is captured depends on which
        // test ran first.
        Registry.clear();
        plugin = FakeServer.newPlugin("ExyliaClasses");

        warnings = new ArrayList<>();
        logger = Logger.getLogger("FormattedValuesTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { warnings.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        Placeholders.logger(logger);
    }

    @AfterEach
    void tearDown() {
        Prefixes.releaseAll();
        Registry.clear();
        FakeServer.reset();
    }

    // ---------------------------------------------------------------
    // Formatted values
    // ---------------------------------------------------------------

    @Test
    @DisplayName("withFormatted honours the formatting a config wrote")
    void formattedValueIsParsed() {
        String legacy = Text.of("%class%")
                .withFormatted("%class%", "<#c8c8c8><bold>ARCHER</bold>")
                .legacy();

        assertTrue(legacy.contains("ARCHER"), legacy);
        assertFalse(legacy.contains("<bold>"), "the tags must become formatting, not text: " + legacy);
        assertTrue(legacy.contains("§"), "a colour should survive as a section code: " + legacy);
    }

    @Test
    @DisplayName("with() stays literal: typed text cannot inject formatting")
    void literalValueCannotInject() {
        String legacy = Text.of("Killed by %name%")
                .with("%name%", "<red>Hacker")
                .legacy();

        assertTrue(legacy.contains("<red>Hacker"),
                "a literal value shows exactly what was typed: " + legacy);
    }

    @Test
    @DisplayName("with() overrides a registered placeholder for that text")
    void literalValueOverridesResolver() {
        FakePlayer player = new FakePlayer("Viewer");
        Placeholders.register(plugin, "player_name", request -> request.requireViewer().getName());

        assertEquals("Row", Text.of("%player_name%")
                .with("%player_name%", "Row")
                .forPlayer(player.player())
                .plain());
    }

    @Test
    @DisplayName("withFormatted() overrides a registered placeholder for that text")
    void formattedValueOverridesResolver() {
        FakePlayer player = new FakePlayer("Viewer");
        Placeholders.register(plugin, "player_name", request -> request.requireViewer().getName());

        String rendered = Text.of("%player_name%")
                .withFormatted("%player_name%", "{accent}Row")
                .forPlayer(player.player())
                .legacy();

        assertTrue(rendered.contains("Row"), rendered);
        assertFalse(rendered.contains("Viewer"), rendered);
        assertFalse(rendered.contains("{accent}"), rendered);
    }

    @Test
    @DisplayName("the warmup message renders the way the old file intended")
    void theMessageFromTheReport() {
        Prefixes.set(plugin, "&d&lEXYLIA CLASSES &8•&r");
        FakePlayer player = new FakePlayer("Steve");

        Text.from(plugin, "%prefix% {muted}Joining {highlight}%class% {muted}in {warning}%time%s{muted}...")
                .withFormatted("%class%", "<#c8c8c8><bold>ARCHER</bold>")
                .with("%time%", 5)
                .send(player.player());

        String shown = player.messages().get(0);
        assertTrue(shown.contains("Joining") && shown.contains("ARCHER") && shown.contains("5s"),
                shown);
        assertFalse(shown.contains("<#c8c8c8>"), shown);
        assertFalse(shown.contains("%prefix%"), shown);
    }

    @Test
    @DisplayName("a resolver returning a display name can be honoured, not shown raw")
    void resolverValueCanBeFormatted() {
        Placeholders.register(plugin, "class", request -> "<#c8c8c8><bold>ARCHER</bold>");
        FakePlayer player = new FakePlayer("Steve");

        String literal = Text.of("Now %class%").forPlayer(player.player()).legacy();
        String formatted = Text.of("Now %class%").forPlayerFormatted(player.player()).legacy();

        assertTrue(literal.contains("<#c8c8c8>"),
                "forPlayer keeps its literal contract: " + literal);
        assertFalse(formatted.contains("<#c8c8c8>"),
                "forPlayerFormatted honours it: " + formatted);
        assertTrue(formatted.contains("ARCHER"));
    }

    @Test
    @DisplayName("a formatted value brings its own colour with it")
    void paletteTokenInAFormattedValue() {
        // The shape that works: the value carries colour and the text it
        // colours. A rank display name written in a config is this.
        String formatted = Text.of("%rank%").withFormatted("%rank%", "{accent}MVP").legacy();

        assertFalse(formatted.contains("{accent}"), "the token is resolved: " + formatted);
        assertTrue(formatted.contains("§"), "and becomes a colour: " + formatted);
        assertTrue(formatted.contains("MVP"), formatted);
    }

    @Test
    @DisplayName("a colour with nothing to colour cannot travel as a value")
    void aBareColourIsNotAValue() {
        // From the ExyliaArmorTrims report: a menu passed "{accent}" as a row
        // value, expecting it to colour the name written after the
        // placeholder. It cannot, and no amount of asking changes that.
        //
        // Substitution happens on the component tree, not on the string, which
        // is what lets a template be parsed once and shared by every row. A
        // bare colour parses to an empty component that carries a colour, and
        // a colour on one node does not reach its siblings — "WILD" is next to
        // it, not inside it.
        //
        // So a colour is not data. A row says which state it is in and the
        // section's templates say what each state looks like.
        String formatted = Text.of("%name_color%WILD")
                .withFormatted("%name_color%", "{accent}")
                .legacy();
        assertFalse(formatted.contains("§"),
                "a bare colour cannot reach the text beside it: " + formatted);

        // Not a palette quirk: a raw hex value behaves the same way, because
        // the reason is the tree rather than the notation.
        assertFalse(Text.of("%c%WILD").withFormatted("%c%", "<#ff6b9d>").legacy().contains("§"),
                "the same is true written as hex");

        // And literally is what with() promises, which is the safe default.
        assertTrue(Text.of("%name_color%WILD").with("%name_color%", "{accent}").legacy()
                        .contains("{accent}"),
                "a literal value is shown exactly as given");
    }

    // ---------------------------------------------------------------
    // The warning must not cry wolf
    // ---------------------------------------------------------------

    @Test
    @DisplayName("values supplied through with() do not trigger the unknown report")
    void suppliedValuesDoNotWarn() {
        Prefixes.set(plugin, "EXYLIA >");
        FakePlayer player = new FakePlayer("Steve");

        Text.from(plugin, "%prefix% Joining %class% in %time%s")
                .withFormatted("%class%", "ARCHER")
                .with("%time%", 5)
                .forPlayer(player.player())
                .send(player.player());

        // The live server logged all three even though every one was supplied.
        assertTrue(warnings.isEmpty(), "nothing here is unknown: " + warnings);
    }

    @Test
    @DisplayName("a placeholder nobody supplies is still reported")
    void genuinelyUnknownStillWarns() {
        FakePlayer player = new FakePlayer("Steve");

        // Only forPlayer resolves, so only forPlayer can notice an unknown name.
        Text.of("You won %prize%").forPlayer(player.player()).send(player.player());

        assertTrue(warnings.stream().anyMatch(line -> line.contains("prize")),
                "a real typo must still be caught: " + warnings);
    }

    @Test
    @DisplayName("a message without an owner still explains %prefix% once")
    void ownerlessPrefixIsExplained() {
        FakePlayer player = new FakePlayer("Steve");

        Text.of("%prefix% hello").forPlayer(player.player()).send(player.player());

        assertTrue(warnings.stream().anyMatch(line -> line.contains("prefix")),
                "Text.of has no prefix to use, and saying so is how anyone finds out: " + warnings);
    }
}
