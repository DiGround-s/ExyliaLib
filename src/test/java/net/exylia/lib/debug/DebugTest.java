package net.exylia.lib.debug;

import net.exylia.lib.FakeServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The debug output: prefix, colours, the debug toggle and the banner.
 */
class DebugTest {

    private record Captured(Component line, Throwable error) {
        String plain() {
            return PlainTextComponentSerializer.plainText().serialize(line);
        }
    }

    private final List<Captured> out = new CopyOnWriteArrayList<>();
    private Plugin plugin;
    private Debug debug;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Debug.releaseAll();
        Debug.setSink((line, error) -> out.add(new Captured(line, error)));

        plugin = FakeServer.newPlugin("ExyliaTest");
        debug = Debug.of(plugin);
    }

    @AfterEach
    void tearDown() {
        Debug.resetSink();
        Debug.releaseAll();
        FakeServer.reset();
    }

    // ------------------------------------------------------------------
    // Instances
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the same plugin gets the same instance")
    void cachedPerPlugin() {
        assertSame(debug, Debug.of(plugin));
    }

    @Test
    @DisplayName("a released plugin gets a fresh one")
    void releaseDrops() {
        Debug.release("ExyliaTest");

        assertNotSame(debug, Debug.of(plugin));
    }

    // ------------------------------------------------------------------
    // Shape of a line
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every line carries the plugin's name")
    void prefixedWithName() {
        debug.log("ready");

        assertEquals("[ExyliaTest] ready", out.get(0).plain());
    }

    @Test
    @DisplayName("the message is literal: & and { survive")
    void messageIsLiteral() {
        debug.log("100% & counting {primary}");

        // Parsed, the & would vanish and the token would become a colour.
        assertEquals("[ExyliaTest] 100% & counting {primary}", out.get(0).plain());
    }

    @Test
    @DisplayName("each type wears its own colour")
    void typesHaveColours() {
        debug.log("a");
        debug.success("b");
        debug.warn("c");
        debug.error("d");

        // The body colour lives on the appended part, so compare those.
        TextColor letters = out.get(0).line().children().get(0).color();
        TextColor success = out.get(1).line().children().get(0).color();
        TextColor warning = out.get(2).line().children().get(0).color();
        TextColor error = out.get(3).line().children().get(0).color();
        assertTrue(success.value() != warning.value(),
                "success and warning must not look alike");
        assertTrue(error.value() != letters.value());
        assertTrue(warning.value() != letters.value());
    }

    @Test
    @DisplayName("an error with a throwable hands the throwable over")
    void errorCarriesThrowable() {
        RuntimeException boom = new RuntimeException("boom");

        debug.error("broke", boom);

        assertEquals("[ExyliaTest] broke", out.get(0).plain());
        assertSame(boom, out.get(0).error());
    }

    @Test
    @DisplayName("an error without a throwable carries none")
    void plainErrorCarriesNothing() {
        debug.error("broke");

        assertEquals(null, out.get(0).error());
    }

    @Test
    @DisplayName("the stack goes to the plugin's logger, with a level, never to raw stdout")
    void stackGoesThroughTheLogger() {
        List<java.util.logging.LogRecord> records = new java.util.ArrayList<>();
        java.util.logging.Logger pluginLogger = java.util.logging.Logger.getLogger("ExyliaTest");
        pluginLogger.setUseParentHandlers(false);
        pluginLogger.addHandler(new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        RuntimeException boom = new RuntimeException("boom");

        debug.error("broke", boom);

        // printStackTrace would leave no trace here: it wrote to System.err,
        // which no handler can see and no log file keeps.
        assertEquals(1, records.size());
        assertSame(boom, records.get(0).getThrown());
        assertEquals(java.util.logging.Level.WARNING, records.get(0).getLevel());
    }

    // ------------------------------------------------------------------
    // The debug toggle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("debug lines stay silent until enabled")
    void debugIsQuietByDefault() {
        debug.debug("noisy detail");

        assertTrue(out.isEmpty(), "a debug line must not print when disabled");
    }

    @Test
    @DisplayName("enabled makes debug lines print")
    void debugPrintsWhenEnabled() {
        debug.enabled(true);

        debug.debug("noisy detail");

        assertEquals("[ExyliaTest] noisy detail", out.get(0).plain());
    }

    @Test
    @DisplayName("disabling again silences it")
    void debugCanBeTurnedBackOff() {
        debug.enabled(true);
        debug.enabled(false);

        debug.debug("noisy detail");

        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("the toggle only gates debug, not the rest")
    void toggleGatesOnlyDebug() {
        debug.warn("still here");

        assertEquals(1, out.size());
    }

    // ------------------------------------------------------------------
    // The banner
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the banner is ASCII art plus the version")
    void motdPrintsArtAndVersion() {
        debug.motd();

        assertTrue(out.size() >= 3,
                "a banner is several art lines plus a version line, got " + out.size());
        assertEquals("v1.0-test", out.get(out.size() - 1).plain());
    }

    @Test
    @DisplayName("the art is more than the name printed plainly")
    void motdIsActuallyArt() {
        debug.motd();

        boolean anyArtLine = out.stream()
                .map(Captured::plain)
                .anyMatch(line -> line.contains("_") || line.contains("|"));
        assertTrue(anyArtLine, "figlet art draws letters with _ and |");
    }

    @Test
    @DisplayName("the banner skips blank figlet rows")
    void motdSkipsBlankLines() {
        debug.motd();

        assertFalse(out.stream().anyMatch(line -> line.plain().isBlank()));
    }
}
