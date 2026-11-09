package net.exylia.lib.reload;

import net.exylia.lib.FakeServer;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.debug.DebugCapture;
import net.exylia.lib.text.Prefixes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reloading a plugin, and being told the library reloaded.
 */
class ReloadsTest {

    private List<String> console;
    private final List<String> toSender = new CopyOnWriteArrayList<>();
    private Plugin plugin;
    private CommandSender sender;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Reloads.releaseAll();
        Debug.releaseAll();
        Prefixes.releaseAll();
        console = DebugCapture.start();

        plugin = FakeServer.newPlugin("MyPlugin");
        sender = (CommandSender) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage") && args[0] instanceof Component c) {
                        toSender.add(PlainTextComponentSerializer.plainText().serialize(c));
                        return null;
                    }
                    return FakeServer.defaultValue(method.getReturnType());
                });
    }

    @AfterEach
    void tearDown() {
        DebugCapture.stop();
        Debug.releaseAll();
        Reloads.releaseAll();
        Prefixes.releaseAll();
        FakeServer.reset();
    }

    // ------------------------------------------------------------------
    // Steps
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every step runs")
    void allStepsRun() {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();

        Reloads.of(plugin)
                .step("a", a::incrementAndGet)
                .step("b", b::incrementAndGet)
                .run();

        assertEquals(1, a.get());
        assertEquals(1, b.get());
    }

    @Test
    @DisplayName("steps run in the order they were declared")
    void stepsKeepOrder() {
        List<String> order = new ArrayList<>();

        Reloads.of(plugin)
                .step("configs", () -> order.add("configs"))
                .step("menus", () -> order.add("menus"))
                .step("boards", () -> order.add("boards"))
                .run();

        assertEquals(List.of("configs", "menus", "boards"), order);
    }

    @Test
    @DisplayName("a failing step does not stop the ones after it")
    void failureDoesNotStopTheRest() {
        AtomicInteger after = new AtomicInteger();

        Reloads.Report report = Reloads.of(plugin)
                .step("broken", () -> {
                    throw new IllegalStateException("boom");
                })
                .step("after", after::incrementAndGet)
                .run();

        assertEquals(1, after.get(), "a half-reloaded plugin is worse than a reported failure");
        assertFalse(report.ok());
        assertEquals(List.of("broken"), report.failed());
    }

    @Test
    @DisplayName("a failing step is reported to the console with its name")
    void failureIsReported() {
        Reloads.of(plugin)
                .step("menus", () -> {
                    throw new IllegalStateException("boom");
                })
                .run();

        assertTrue(console.stream().anyMatch(line -> line.contains("menus")),
                "the console must name what failed, got: " + console);
    }

    @Test
    @DisplayName("a clean reload reports how many steps and how long")
    void reportDescribesSuccess() {
        Reloads.Report report = Reloads.of(plugin)
                .step("a", () -> { })
                .step("b", () -> { })
                .run();

        assertTrue(report.ok());
        assertEquals(2, report.steps());
        assertTrue(report.describe().contains("2 steps"), report.describe());
        assertTrue(report.describe().contains("ms"), report.describe());
    }

    @Test
    @DisplayName("a partial reload says how many survived and what failed")
    void reportDescribesFailure() {
        Reloads.Report report = Reloads.of(plugin)
                .step("ok", () -> { })
                .step("broken", () -> {
                    throw new IllegalStateException("boom");
                })
                .run();

        assertEquals("1/2", report.describe().split(" ")[1], report.describe());
        assertTrue(report.describe().contains("broken"), report.describe());
    }

    @Test
    @DisplayName("the sender is told the result")
    void senderIsTold() {
        Reloads.of(plugin).step("a", () -> { }).run(sender);

        assertEquals(1, toSender.size());
        assertTrue(toSender.get(0).contains("Reloaded"), toSender.get(0));
    }

    @Test
    @DisplayName("the sender's line carries the plugin's prefix")
    void senderLineIsPrefixed() {
        Prefixes.set(plugin, "[MyPlugin]");

        Reloads.of(plugin).step("a", () -> { }).run(sender);

        assertTrue(toSender.get(0).startsWith("[MyPlugin]"), toSender.get(0));
    }

    @Test
    @DisplayName("a plugin with no prefix is not sent a literal %prefix%")
    void senderLineWithoutPrefix() {
        Reloads.of(plugin).step("a", () -> { }).run(sender);

        assertFalse(toSender.get(0).contains("%prefix%"), toSender.get(0));
    }

    @Test
    @DisplayName("the sender is told which step failed, not only the console")
    void senderIsToldWhatFailed() {
        Reloads.of(plugin)
                .step("menus", () -> {
                    throw new IllegalStateException("boom");
                })
                .step("configs", () -> { })
                .run(sender);

        assertTrue(toSender.get(0).contains("1/2"), toSender.get(0));
        assertTrue(toSender.get(0).contains("menus"), toSender.get(0));
    }

    @Test
    @DisplayName("a reload with no sender still logs")
    void noSenderStillLogs() {
        Reloads.of(plugin).step("a", () -> { }).run(null);

        assertTrue(toSender.isEmpty());
        assertFalse(console.isEmpty());
    }

    @Test
    @DisplayName("an empty reload is honest rather than failing")
    void emptyReload() {
        Reloads.Report report = Reloads.of(plugin).run();

        assertTrue(report.ok());
        assertEquals(0, report.steps());
    }

    // ------------------------------------------------------------------
    // Library reload listeners
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a listener runs when the library reloads")
    void listenerRuns() {
        AtomicInteger rebuilt = new AtomicInteger();
        Reloads.onLibraryReload(plugin, rebuilt::incrementAndGet);

        Reloads.fireLibraryReload();

        assertEquals(1, rebuilt.get(), "this is what rebuilds a menu after a recolour");
    }

    @Test
    @DisplayName("every plugin's listener runs, not just the first")
    void allListenersRun() {
        AtomicInteger one = new AtomicInteger();
        AtomicInteger two = new AtomicInteger();
        Reloads.onLibraryReload(plugin, one::incrementAndGet);
        Reloads.onLibraryReload(FakeServer.newPlugin("Other"), two::incrementAndGet);

        Reloads.fireLibraryReload();

        assertEquals(1, one.get());
        assertEquals(1, two.get());
    }

    @Test
    @DisplayName("a listener that throws does not stop the others")
    void listenerFailureIsContained() {
        AtomicInteger after = new AtomicInteger();
        Reloads.onLibraryReload(plugin, () -> {
            throw new IllegalStateException("boom");
        });
        Reloads.onLibraryReload(FakeServer.newPlugin("Other"), after::incrementAndGet);

        Reloads.fireLibraryReload();

        assertEquals(1, after.get(), "one plugin's bug is not another plugin's problem");
    }

    @Test
    @DisplayName("a plugin's listeners go when it disables")
    void releaseDropsListeners() {
        AtomicInteger rebuilt = new AtomicInteger();
        Reloads.onLibraryReload(plugin, rebuilt::incrementAndGet);

        Reloads.release("MyPlugin");
        Reloads.fireLibraryReload();

        assertEquals(0, rebuilt.get(), "a disabled plugin must not be called");
        assertEquals(0, Reloads.listenerCount());
    }

    @Test
    @DisplayName("a step can also run on library reload")
    void stepAlsoOnLibraryReload() {
        AtomicInteger rebuilt = new AtomicInteger();
        Reloads.of(plugin).stepAlsoOnLibraryReload("menus", rebuilt::incrementAndGet);

        Reloads.fireLibraryReload();

        assertEquals(1, rebuilt.get());
    }

    @Test
    @DisplayName("an ordinary step does not run on library reload")
    void ordinaryStepIsNotAListener() {
        AtomicInteger ran = new AtomicInteger();
        Reloads.of(plugin).step("configs", ran::incrementAndGet);

        Reloads.fireLibraryReload();

        assertEquals(0, ran.get(),
                "re-reading a plugin's own files is not what a recolour means");
    }

    @Test
    @DisplayName("a plugin in line for a language reload rebuilds in its turn, each thing once")
    void aLanguageChangeMovesTheListenersIntoTheTurn() throws Exception {
        java.nio.file.Path folder = java.nio.file.Files.createTempDirectory("reloads").resolve("Listening");
        java.nio.file.Files.createDirectories(folder);
        java.nio.file.Path config = folder.resolve("config.yml");
        java.nio.file.Files.writeString(config, "language: es\n");
        org.bukkit.plugin.Plugin plugin = FakeServer.newPlugin("Listening", folder.toFile());
        java.util.concurrent.atomic.AtomicInteger menus = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger other = new java.util.concurrent.atomic.AtomicInteger();
        Reloads.of(plugin).stepAlsoOnLibraryReload("menus", menus::incrementAndGet);
        Reloads.onLibraryReload(plugin, other::incrementAndGet);

        Reloads.fireLibraryReload();
        assertEquals(1, menus.get(), "the same language rebuilds straight away, as ever");
        assertEquals(1, other.get());

        java.nio.file.Files.writeString(config, "language: fr\n");
        Reloads.fireLibraryReload();
        assertEquals(1, menus.get(), "nothing in the tick of the command");
        assertEquals(1, other.get());

        FakeServer.tick(6);
        assertEquals(2, menus.get(), "once in its turn, not as step and listener");
        assertEquals(2, other.get(), "a listener that is not a step still runs");
    }

    @Test
    @DisplayName("a language change reloads the plugins that follow it one at a time, in later ticks")
    void aLanguageChangeSpreadsThePluginReloads() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("reloads");
        java.util.List<java.nio.file.Path> configs = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger read = new java.util.concurrent.atomic.AtomicInteger();
        for (String name : java.util.List.of("First", "Second")) {
            java.nio.file.Path folder = java.nio.file.Files.createDirectories(root.resolve(name));
            java.nio.file.Path config = folder.resolve("config.yml");
            java.nio.file.Files.writeString(config, "language: es\n");
            configs.add(config);
            Reloads.of(FakeServer.newPlugin(name, folder.toFile())).step("files", read::incrementAndGet);
        }

        Reloads.fireLibraryReload();
        FakeServer.tick(20);
        assertEquals(0, read.get(), "the same language has nothing to read again");

        for (java.nio.file.Path config : configs) java.nio.file.Files.writeString(config, "language: fr\n");
        Reloads.fireLibraryReload();
        assertEquals(0, read.get(), "nothing runs in the tick of the command");

        FakeServer.tick(6);
        assertEquals(1, read.get(), "one plugin in its turn");

        FakeServer.tick(6);
        assertEquals(2, read.get(), "the next in a later tick");

        Reloads.fireLibraryReload();
        FakeServer.tick(20);
        assertEquals(2, read.get(), "once");
    }

    @Test
    @DisplayName("such a step still runs on the plugin's own reload")
    void libraryStepStillRunsNormally() {
        AtomicInteger rebuilt = new AtomicInteger();
        Reloads reloads = Reloads.of(plugin)
                .stepAlsoOnLibraryReload("menus", rebuilt::incrementAndGet);

        reloads.run();

        assertEquals(1, rebuilt.get());
    }
}
