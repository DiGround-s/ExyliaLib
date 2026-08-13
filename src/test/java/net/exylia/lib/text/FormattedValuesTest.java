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
