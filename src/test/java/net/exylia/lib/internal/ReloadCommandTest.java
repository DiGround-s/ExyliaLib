package net.exylia.lib.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.transfer.TableTransfer;
import net.exylia.lib.database.transfer.TransferOutcome;
import net.exylia.lib.database.transfer.TransferReport;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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

    // -------------------------------------------------------------- transfer

    /** A transfer side that answers from memory, so no database has to exist. */
    private static final class FakeTransfers implements TransferAccess {

        private final Map<String, List<String>> tables = new LinkedHashMap<>();
        private final TransferReport answer = new TransferReport(TransferOutcome.SUCCESS, null,
                List.of(), 0L, Duration.ZERO, List.of());
        private Path lastFile;
        private Boolean lastForce;

        @Override
        public List<String> plugins() {
            return List.copyOf(tables.keySet());
        }

        @Override
        public List<String> tablesOf(String pluginName) {
            return tables.get(pluginName);
        }

        @Override
        public boolean redisActive() {
            return false;
        }

        @Override
        public CompletableFuture<TransferReport> export(String pluginName, Path folder) {
            lastFile = folder;
            return CompletableFuture.completedFuture(answer);
        }

        @Override
        public CompletableFuture<TransferReport> importFrom(String pluginName, Path file,
                                                            boolean force) {
            lastFile = file;
            lastForce = force;
            return CompletableFuture.completedFuture(answer);
        }
    }

    private FakeTransfers transfers;

    private ReloadCommand withTransfers() {
        transfers = new FakeTransfers();
        return new ReloadCommand(reloads::incrementAndGet, () -> "1.36.0", () -> Platform.BUKKIT,
                LibrarySettings::new, List::of, () -> Path.of("/srv/dumps"), transfers);
    }

    @Test
    @DisplayName("export names the tables it found, not only how many")
    void exportNamesTheTables() {
        ReloadCommand command = withTransfers();
        transfers.tables.put("Practice", List.of("practice_stats", "practice_kits"));

        command.export(sender, "Practice");

        String text = sent.get(0);
        // The count alone hides the one failure mode this has: a plugin that
        // registers a repository lazily exports fewer tables than it owns, and
        // only the names make that visible against what somebody expected.
        assertTrue(text.contains("practice_stats"), "got: " + text);
        assertTrue(text.contains("practice_kits"), "got: " + text);
        assertTrue(text.contains("lazily"), "got: " + text);
    }

    @Test
    @DisplayName("export of a plugin with no tables is refused, and lists the ones that have")
    void exportRefusesAnUnknownPlugin() {
        ReloadCommand command = withTransfers();
        transfers.tables.put("Practice", List.of("practice_stats"));

        command.export(sender, "Shields");

        assertEquals(1, sent.size(), "nothing is started for a plugin that stores nothing");
        String text = sent.get(0);
        assertTrue(text.contains("no registered tables"), "got: " + text);
        assertTrue(text.contains("Practice"), "the refusal says what is available: " + text);
    }

    @Test
    @DisplayName("a refused import hands back the exact command, and says force merges")
    void importRefusalOffersForce() {
        String panel = ReloadCommand.importPanel("Practice", "dump.exyliadump.gz",
                new TransferReport(TransferOutcome.FAILED, null, List.of(), 0L, Duration.ZERO,
                        List.of("Refused: practice_stats (12 rows) already hold rows.")));

        assertTrue(panel.contains("/exylialib import Practice dump.exyliadump.gz true"),
                "got: " + panel);
        // The sentence itself, not a flag name. Somebody who reads force as
        // "replace" and runs it has silently mixed two servers into one table.
        assertTrue(panel.contains("MERGES"), "got: " + panel);
        assertTrue(panel.contains("not replace"), "got: " + panel);
    }

    @Test
    @DisplayName("a successful import panel does not offer force")
    void aCleanImportDoesNotOfferForce() {
        String panel = ReloadCommand.importPanel("Practice", "dump.exyliadump.gz",
                new TransferReport(TransferOutcome.SUCCESS, null,
                        List.of(TableTransfer.of("practice_stats", 12L)), 12L,
                        Duration.ofMillis(40), List.of()));

        assertFalse(panel.contains("force"), "got: " + panel);
        assertTrue(panel.contains("success"), "got: " + panel);
        assertTrue(panel.contains("12"), "got: " + panel);
    }

    @Test
    @DisplayName("the report panel distinguishes a skipped table from one that moved no rows")
    void theReportPanelShowsWhatHappenedPerTable() {
        String panel = ReloadCommand.reportPanel("Import", "Practice",
                new TransferReport(TransferOutcome.PARTIAL, null,
                        List.of(TableTransfer.of("practice_stats", 0L),
                                TableTransfer.skipped("someone_else", "nothing stores it"),
                                TableTransfer.drifted("practice_kits", 3L, "bound by name")),
                        3L, Duration.ZERO, List.of("Skipped someone_else.")));

        assertTrue(panel.contains("partial"), "got: " + panel);
        // Zero rows and "skipped" are different answers: one table was read and
        // was empty, the other was never claimed by anything.
        assertTrue(panel.contains("skipped"), "got: " + panel);
        assertTrue(panel.contains("layout drifted"), "got: " + panel);
        assertTrue(panel.contains("Problems"), "got: " + panel);
    }

    @Test
    @DisplayName("an import file name cannot escape the dump folder")
    void aFileNameCannotEscapeTheFolder() {
        // The argument arrives from a chat box, and Path.resolve on
        // "../../server.properties" leaves the folder entirely.
        assertEquals("server.properties", ReloadCommand.safeName("../../server.properties"));
        assertEquals("dump.exyliadump.gz", ReloadCommand.safeName("/etc/dump.exyliadump.gz"));
        assertEquals("dump.exyliadump.gz", ReloadCommand.safeName("dump.exyliadump.gz"));
    }

    @Test
    @DisplayName("import passes force through exactly as it was typed")
    void importPassesForceThrough() {
        ReloadCommand command = withTransfers();
        transfers.tables.put("Practice", List.of("practice_stats"));

        command.importDump(sender, "Practice", "dump.exyliadump.gz", true);

        assertEquals(Boolean.TRUE, transfers.lastForce);
        assertEquals(Path.of("/srv/dumps/dump.exyliadump.gz"), transfers.lastFile);
        assertTrue(sent.get(0).contains("force"), "the mode is shown before it runs: " + sent.get(0));
    }

    @Test
    @DisplayName("the overview names export and import too")
    void theOverviewNamesTransfer() {
        ReloadCommand command = withTransfers();
        command.overview(sender);
        String text = sent.get(0);
        assertTrue(text.contains("/exylialib export"), "got: " + text);
        assertTrue(text.contains("/exylialib import"), "got: " + text);
        assertTrue(text.contains("MERGES"), "got: " + text);
    }
}
