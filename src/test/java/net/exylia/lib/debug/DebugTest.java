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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    /** The message body: the last piece appended to a line. */
    private static TextColor bodyColour(Component line) {
        List<Component> parts = line.children();
        return parts.get(parts.size() - 1).color();
    }

    /** The label between the brackets, which sits just before the body. */
    private static TextColor labelColour(Component line) {
        List<Component> parts = line.children();
        // [ name ] [ LABEL ] body — the label is three back from the end.
        return parts.get(parts.size() - 3).color();
    }

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Debug.releaseAll();
        Debug.all(false);
        Debug.setSink((line, error) -> out.add(new Captured(line, error)));

        plugin = FakeServer.newPlugin("ExyliaTest");
        debug = Debug.of(plugin);
    }

    @AfterEach
    void tearDown() {
        Debug.resetSink();
        Debug.releaseAll();
        Debug.all(false);
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

        assertEquals("[ExyliaTest] [INFO] ready", out.get(0).plain());
    }

    @Test
    @DisplayName("the message is literal: & and { survive")
    void messageIsLiteral() {
        debug.log("100% & counting {primary}");

        // Parsed, the & would vanish and the token would become a colour.
        assertEquals("[ExyliaTest] [INFO] 100% & counting {primary}",
                out.get(0).plain());
    }

    @Test
    @DisplayName("each type says which it is")
    void typesAreLabelled() {
        debug.log("a");
        debug.success("b");
        debug.warn("c");
        debug.error("d");
        debug.enabled(true);
        debug.debug("e");

        assertEquals("[ExyliaTest] [INFO] a", out.get(0).plain());
        assertEquals("[ExyliaTest] [SUCCESS] b", out.get(1).plain());
        assertEquals("[ExyliaTest] [WARN] c", out.get(2).plain());
        assertEquals("[ExyliaTest] [ERROR] d", out.get(3).plain());
        assertEquals("[ExyliaTest] [DEBUG] e", out.get(4).plain());
    }

    @Test
    @DisplayName("each type wears its own colour")
    void typesHaveColours() {
        debug.log("a");
        debug.success("b");
        debug.warn("c");
        debug.error("d");

        TextColor letters = bodyColour(out.get(0).line());
        TextColor success = bodyColour(out.get(1).line());
        TextColor warning = bodyColour(out.get(2).line());
        TextColor error = bodyColour(out.get(3).line());
        assertTrue(success.value() != warning.value(),
                "success and warning must not look alike");
        assertTrue(error.value() != letters.value());
        assertTrue(warning.value() != letters.value());
    }

    @Test
    @DisplayName("the label wears its type's colour, not the body's")
    void labelIsColouredByType() {
        debug.warn("careful");
        debug.error("broke");

        TextColor warnLabel = labelColour(out.get(0).line());
        TextColor errorLabel = labelColour(out.get(1).line());
        assertTrue(warnLabel.value() != errorLabel.value(),
                "a warning and an error must not share a label colour");
    }

    @Test
    @DisplayName("an error with a throwable hands the throwable over")
    void errorCarriesThrowable() {
        RuntimeException boom = new RuntimeException("boom");

        debug.error("broke", boom);

        assertEquals("[ExyliaTest] [ERROR] broke", out.get(0).plain());
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

        assertEquals("[ExyliaTest] [DEBUG] noisy detail", out.get(0).plain());
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
    // The library-wide switch
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the library switch makes a plugin print without its own toggle")
    void libraryWideSwitchEnables() {
        Debug.all(true);

        debug.debug("noisy detail");

        assertEquals("[ExyliaTest] [DEBUG] noisy detail", out.get(0).plain());
    }

    @Test
    @DisplayName("the library switch reaches instances made before it was set")
    void libraryWideSwitchReachesExistingInstances() {
        Debug existing = Debug.of(plugin);
        Debug.all(true);

        existing.debug("noisy detail");

        assertEquals(1, out.size(), "an instance created earlier must obey the switch too");
    }

    @Test
    @DisplayName("turning the library switch off does not silence a plugin that enabled itself")
    void libraryWideSwitchDoesNotOverrideThePlugin() {
        debug.enabled(true);
        Debug.all(false);

        debug.debug("noisy detail");

        // It raises the floor, so the plugin's own toggle still wins over off.
        assertEquals(1, out.size());
    }

    @Test
    @DisplayName("the library switch off leaves a plugin that never enabled itself silent")
    void libraryWideSwitchOffIsQuiet() {
        Debug.all(true);
        Debug.all(false);

        debug.debug("noisy detail");

        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("the library switch gates only debug, not warnings")
    void libraryWideSwitchGatesOnlyDebug() {
        Debug.all(false);

        debug.warn("still here");

        assertEquals(1, out.size());
    }

    // ------------------------------------------------------------------
    // The banner
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the banner is ASCII art plus the version and the link")
    void motdPrintsArtAndVersion() {
        debug.motd();

        assertTrue(out.size() >= 3,
                "a banner is several art lines plus a version line, got " + out.size());
        List<String> lines = out.stream().map(Captured::plain).toList();
        assertTrue(lines.contains("Version: v1.0-test | Debug: false"),
                "the version line is missing, got " + lines);
        assertTrue(lines.contains("Powered by Exylia - https://discord.exylia.net"),
                "the Exylia line is missing, got " + lines);
    }

    @Test
    @DisplayName("the banner says whether debug is on")
    void motdReportsTheDebugState() {
        debug.enabled(true);

        debug.motd();

        assertTrue(out.stream().map(Captured::plain)
                        .anyMatch(line -> line.equals("Version: v1.0-test | Debug: true")),
                "a banner printed with debug on must say so");
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
    @DisplayName("the banner skips blank figlet rows but keeps its own framing")
    void motdSkipsBlankLines() {
        debug.motd();

        // Two deliberate blanks — one above, one below — and no figlet padding
        // in between, which would show up as more.
        long blanks = out.stream().filter(line -> line.plain().isBlank()).count();
        assertEquals(2, blanks, "the framing is one blank line at each end");
        assertTrue(out.get(0).plain().isBlank(), "the banner opens on a blank line");
        assertTrue(out.get(out.size() - 1).plain().isBlank(),
                "the banner closes on a blank line");
    }

    // ------------------------------------------------------------------
    // The gradient name
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the name is a gradient, not one flat colour")
    void nameIsAGradient() {
        debug.log("ready");

        List<TextColor> painted = nameColours(out.get(0).line());
        assertTrue(painted.stream().distinct().count() > 1,
                "a flat name means the gradient was lost");
    }

    @Test
    @DisplayName("the gradient runs out to the middle and back")
    void gradientIsSymmetric() {
        debug.log("ready");

        List<TextColor> painted = nameColours(out.get(0).line());
        assertEquals(painted.get(0), painted.get(painted.size() - 1),
                "both ends of the name must read the same");
        TextColor middle = painted.get(painted.size() / 2);
        assertNotEquals(painted.get(0), middle,
                "the middle must differ from the ends");
    }

    @Test
    @DisplayName("a one-letter plugin name still paints")
    void gradientHandlesAShortName() {
        Debug tiny = Debug.of(FakeServer.newPlugin("X"));

        tiny.log("ready");

        assertEquals("[X] [INFO] ready", out.get(0).plain());
    }

    @Test
    @DisplayName("recolouring the palette recolours the console")
    void followsThePalette() {
        debug.log("ready");
        List<TextColor> before = nameColours(out.get(0).line());
        out.clear();

        net.exylia.lib.text.Palette recoloured = new net.exylia.lib.text.Palette(
                "#112233", "#445566", "#b48fd9", "#e7cfff", "#a89ab5",
                "#a33b53", "#8fffc1", "#a1ffc3", "#ff9500", "#ffd2a8",
                "#59a4ff", "#7db7ff", "#ff6b9d", "#6c757d", "#ffd700", "#868e96");
        net.exylia.lib.text.Colors.apply(recoloured);
        debug.log("ready");

        assertNotEquals(before, nameColours(out.get(0).line()),
                "the name is read from the palette on every line, so a reload "
                        + "must reach it without an invalidateAll hook");
    }

    /** The colour of every character of the name, in order. */
    private static List<TextColor> nameColours(Component line) {
        // The painted name is appended as one piece holding a part per
        // character, so it arrives as the first child rather than as N.
        return line.children().get(0).children().stream()
                .map(Component::color)
                .toList();
    }
}
