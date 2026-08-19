package net.exylia.lib.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.platform.Platform;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The library's own command: what each subcommand does and tells the sender.
 */
class ReloadCommandTest {

    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger reloads = new AtomicInteger();
    private ReloadCommand command;
    private CommandSender sender;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();

        command = new ReloadCommand(reloads::incrementAndGet, () -> "1.14.0",
                () -> Platform.BUKKIT, LibrarySettings::new, List::of);
        sender = (CommandSender) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage") && args[0] instanceof Component c) {
                        sent.add(PlainTextComponentSerializer.plainText().serialize(c));
                        return null;
                    }
                    return FakeServer.defaultValue(method.getReturnType());
                });
    }

    @AfterEach
    void tearDown() {
        FakeServer.reset();
    }

    // --------------------------------------------------------------- reload

    @Test
    @DisplayName("reload actually reloads")
    void reloadRunsTheAction() {
        command.reload(sender);

        assertEquals(1, reloads.get(), "the palette reload must run");
    }

    @Test
    @DisplayName("reload tells the sender it happened")
    void reloadConfirms() {
        command.reload(sender);

        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("Reloaded"),
                "the sender hears the result, got: " + sent.get(0));
    }

    @Test
    @DisplayName("the confirmation carries how long it took")
    void reloadReportsDuration() {
        command.reload(sender);

        assertTrue(sent.get(0).contains("ms"), "got: " + sent.get(0));
    }

    @Test
    @DisplayName("reload names every file it refreshes, not only colours")
    void reloadDescribesEveryFile() {
        command.reload(sender);

        String text = sent.get(0);
        assertTrue(text.contains("colours"), "got: " + text);
        assertTrue(text.contains("formats"), "got: " + text);
        assertTrue(text.contains("economy"), "got: " + text);
        assertTrue(text.contains("input"), "got: " + text);
    }

    // ------------------------------------------------------------- overview

    @Test
    @DisplayName("the overview names the version and every subcommand")
    void overviewShowsVersionAndUsage() {
        command.overview(sender);

        assertEquals(1, sent.size());
        String text = sent.get(0);
        assertTrue(text.contains("1.14.0"), "got: " + text);
        assertTrue(text.contains("/exylialib reload"), "got: " + text);
        assertTrue(text.contains("/exylialib info"), "got: " + text);
        assertTrue(text.contains("/exylialib stats"), "got: " + text);
    }

    @Test
    @DisplayName("the overview does not reload anything")
    void overviewDoesNotReload() {
        command.overview(sender);

        assertEquals(0, reloads.get());
    }

    // ------------------------------------------------------------------ info

    @Test
    @DisplayName("info shows the version and platform")
    void infoShowsVersionAndPlatform() {
        command.info(sender);

        assertEquals(1, sent.size());
        String text = sent.get(0);
        assertTrue(text.contains("1.14.0"), "got: " + text);
        assertTrue(text.contains("BUKKIT"), "got: " + text);
    }

    @Test
    @DisplayName("info shows an explicit empty state with no dependents")
    void infoShowsEmptyStateForNoDependents() {
        command.info(sender);

        String text = sent.get(0);
        assertTrue(text.contains("none found"), "got: " + text);
    }

    @Test
    @DisplayName("info lists a dependent plugin with its own version")
    void infoListsDependents() {
        ReloadCommand withDependent = new ReloadCommand(reloads::incrementAndGet, () -> "1.14.0",
                () -> Platform.PAPER, LibrarySettings::new,
                () -> List.of(new ReloadCommand.Dependent("ExyliaFFA", "3.2.1")));

        withDependent.info(sender);

        String text = sent.get(0);
        assertTrue(text.contains("ExyliaFFA"), "got: " + text);
        assertTrue(text.contains("3.2.1"), "got: " + text);
    }

    @Test
    @DisplayName("info does not reload anything")
    void infoDoesNotReload() {
        command.info(sender);

        assertEquals(0, reloads.get());
    }

    // ----------------------------------------------------------------- stats

    @Test
    @DisplayName("stats does not crash with every module empty")
    void statsHandlesEmptyModules() {
        command.stats(sender);

        assertEquals(1, sent.size());
        String text = sent.get(0);
        assertTrue(text.contains("Scoreboards"), "got: " + text);
        assertTrue(text.contains("Holograms"), "got: " + text);
        assertTrue(text.contains("Effects"), "got: " + text);
    }

    @Test
    @DisplayName("stats mentions holograms, whatever this JVM's PacketEvents support happens to be")
    void statsMentionsHolograms() {
        command.stats(sender);

        String text = sent.get(0);
        assertTrue(text.contains("Holograms"), "got: " + text);
    }

    @Test
    @DisplayName("the holograms line reads N/A rather than a raw zero when unsupported")
    void hologramsLineUnsupported() {
        String line = ReloadCommand.hologramsLine(false, 0);

        assertTrue(line.contains("N/A"), "got: " + line);
    }

    @Test
    @DisplayName("the holograms line shows the live count when supported")
    void hologramsLineSupported() {
        String line = ReloadCommand.hologramsLine(true, 7);

        assertTrue(line.contains("7"), "got: " + line);
        assertFalse(line.contains("N/A"), "got: " + line);
    }

    @Test
    @DisplayName("stats shows Redis as off rather than failing when it was never configured")
    void statsShowsRedisOffWithoutCrashing() {
        command.stats(sender);

        String text = sent.get(0);
        assertTrue(text.toLowerCase(java.util.Locale.ROOT).contains("redis"), "got: " + text);
        assertTrue(text.contains("off"), "got: " + text);
    }

    @Test
    @DisplayName("stats does not reload anything")
    void statsDoesNotReload() {
        command.stats(sender);

        assertEquals(0, reloads.get());
    }

    // -------------------------------------------------- dependency discovery

    @Test
    @DisplayName("a plugin listing the library under depend is found")
    void dependsOnLibraryChecksHardDepend() {
        Plugin dependent = FakeServer.newPlugin("ExyliaFFA", "1.0", List.of("ExyliaLib"), List.of());

        assertTrue(ReloadCommand.dependsOnLibrary(dependent, "ExyliaLib"));
    }

    @Test
    @DisplayName("a plugin listing the library under softdepend is found")
    void dependsOnLibraryChecksSoftDepend() {
        Plugin dependent = FakeServer.newPlugin("ExyliaTotems", "1.0", List.of(), List.of("ExyliaLib"));

        assertTrue(ReloadCommand.dependsOnLibrary(dependent, "ExyliaLib"));
    }

    @Test
    @DisplayName("the match is case-insensitive")
    void dependsOnLibraryIsCaseInsensitive() {
        Plugin dependent = FakeServer.newPlugin("ExyliaFFA", "1.0", List.of("exylialib"), List.of());

        assertTrue(ReloadCommand.dependsOnLibrary(dependent, "ExyliaLib"));
    }

    @Test
    @DisplayName("a plugin with unrelated dependencies is not found")
    void dependsOnLibraryRejectsUnrelated() {
        Plugin unrelated = FakeServer.newPlugin("Vault", "1.0", List.of("SomethingElse"), List.of());

        assertFalse(ReloadCommand.dependsOnLibrary(unrelated, "ExyliaLib"));
    }

    @Test
    @DisplayName("dependentsOf finds enabled plugins that depend on the library, excluding the library itself")
    void dependentsOfScansThePluginManager() {
        Plugin library = FakeServer.newPlugin("ExyliaLib", null);
        Plugin dependent = FakeServer.newPlugin("ExyliaFFA", "3.2.1", List.of("ExyliaLib"), List.of());
        Plugin unrelated = FakeServer.newPlugin("Vault", "1.0", List.of(), List.of());
        FakeServer.plugins(library, dependent, unrelated);

        List<ReloadCommand.Dependent> found = ReloadCommand.dependentsOf(library);

        assertEquals(1, found.size());
        assertEquals("ExyliaFFA", found.get(0).name());
        assertEquals("3.2.1", found.get(0).version());
    }

    @Test
    @DisplayName("dependentsOf reports no dependents when none declare the library")
    void dependentsOfEmptyWhenNoneDepend() {
        Plugin library = FakeServer.newPlugin("ExyliaLib", null);
        Plugin unrelated = FakeServer.newPlugin("Vault", "1.0", List.of(), List.of());
        FakeServer.plugins(library, unrelated);

        assertTrue(ReloadCommand.dependentsOf(library).isEmpty());
    }
}
