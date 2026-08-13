package net.exylia.lib.placeholder;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.placeholder.internal.Registry;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Values attached to a single render.
 *
 * <p>These exist because of a bug that reached a live server: a plugin called
 * {@code apply(text, player, Map.of("class", "Warrior"))} and players saw a
 * literal {@code %class%} in chat. The map was only readable from inside a
 * registered resolver, so the obvious call did nothing at all.
 */
class RenderValuesTest {

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Registry.clear();
        plugin = FakeServer.newPlugin("TestPlugin");
    }

    @AfterEach
    void tearDown() {
        Registry.clear();
        FakeServer.reset();
    }

    @Test
    @DisplayName("a value passed for one message fills its placeholder in")
    void attachedValueIsUsed() {
        String out = Placeholders.apply("Joining %class%", null, Map.of("class", "Warrior"));

        assertEquals("Joining Warrior", out);
    }

    @Test
    @DisplayName("the exact message from the bug renders completely")
    void theMessageFromTheBug() {
        String raw = "Joining %class% in %time%s...";

        String out = Placeholders.apply(raw, null, Map.of("class", "Warrior", "time", "3"));

        assertEquals("Joining Warrior in 3s...", out);
    }

    @Test
    @DisplayName("a registered resolver wins over an attached value")
    void resolverBeatsAttachedValue() {
        Placeholders.register(plugin, "class", request -> "FromResolver");

        String out = Placeholders.apply("%class%", null, Map.of("class", "FromMap"));

        // The registration is the considered, server-wide answer. A value
        // attached to one message must not be able to shadow it by accident.
        assertEquals("FromResolver", out);
    }

    @Test
    @DisplayName("a resolver that returns nothing is not overridden by stray data")
    void resolverReturningNullIsNotOverridden() {
        Placeholders.register(plugin, "class", request -> null);

        String out = Placeholders.apply("%class%", null, Map.of("class", "FromMap"));

        assertEquals("%class%", out);
    }

    @Test
    @DisplayName("a placeholder with no value at all is left as written")
    void unknownStaysVisible() {
        assertEquals("%mystery%", Placeholders.apply("%mystery%", null, Map.of()));
    }

    @Test
    @DisplayName("a non-string value is formatted, not just concatenated")
    void attachedValueUsesFormats() {
        String out = Placeholders.apply("%coins:comma%", null, Map.of("coins", 1234567));

        assertEquals("1,234,567", out);
    }

    @Test
    @DisplayName("an unresolved placeholder is reported to the console")
    void unresolvedIsReported() {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("PrefixProbe");
        java.util.List<String> warnings = new java.util.ArrayList<>();
        logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                warnings.add(record.getMessage());
            }
            @Override public void flush() { }
            @Override public void close() { }
        });
        Placeholders.logger(logger);

        Placeholders.apply("%totally_unknown%", null, Map.of());

        assertTrue(warnings.stream().anyMatch(line -> line.contains("totally_unknown")),
                "an unresolved placeholder should say so once: " + warnings);
    }

    @Test
    @DisplayName("the same unresolved name is only reported once")
    void unresolvedReportedOnce() {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("PrefixProbe2");
        java.util.List<String> warnings = new java.util.ArrayList<>();
        logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                warnings.add(record.getMessage());
            }
            @Override public void flush() { }
            @Override public void close() { }
        });
        Placeholders.logger(logger);

        // A scoreboard renders this every tick; the console must not fill up.
        for (int i = 0; i < 100; i++) {
            Placeholders.apply("%noisy_unknown%", null, Map.of());
        }

        long mentions = warnings.stream().filter(line -> line.contains("noisy_unknown")).count();
        assertEquals(1, mentions, "should be reported once, not per render");
    }

    @Test
    @DisplayName("attached values work alongside a resolver that reads the viewer")
    void attachedValueWithViewer() {
        FakePlayer player = new FakePlayer("Steve");
        Placeholders.register(plugin, "who", request -> request.viewer().getName());

        String out = Placeholders.apply("%who% joins %class%", player.player(),
                Map.of("class", "Mage"));

        assertEquals("Steve joins Mage", out);
    }
}
