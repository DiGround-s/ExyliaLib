package net.exylia.lib.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.transfer.TableTransfer;
import net.exylia.lib.database.transfer.TransferOutcome;
import net.exylia.lib.database.transfer.TransferReport;
import net.exylia.lib.internal.ExyliaLibUpdater.UpdateOutcome;
import net.exylia.lib.internal.ExyliaLibUpdater.UpdateStatus;
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
        private TransferReport exportAnswer = new TransferReport(TransferOutcome.SUCCESS,
                Path.of("/srv/dumps/Practice-h2.exyliadump.gz"), List.of(), 7L, Duration.ZERO,
                List.of());
        private final List<String> wiped = new CopyOnWriteArrayList<>();

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
            return CompletableFuture.completedFuture(exportAnswer);
        }

        @Override
        public CompletableFuture<TransferReport> wipe(String pluginName, String table) {
            wiped.add(pluginName + ':' + (table == null ? "*" : table));
            return CompletableFuture.completedFuture(new TransferReport(TransferOutcome.SUCCESS,
                    null, List.of(TableTransfer.of("practice_stats", 12L)), 12L, Duration.ZERO,
                    List.of()));
        }

        @Override
        public CompletableFuture<TransferReport> importFrom(String pluginName, Path file,
                                                            boolean force) {
            lastFile = file;
            lastForce = force;
            return CompletableFuture.completedFuture(answer);
        }
    }

    // --------------------------------------------------------------- update

    private ReloadCommand withUpdate(UpdateOutcome outcome) {
        return new ReloadCommand(reloads::incrementAndGet, () -> "1.64.0", () -> Platform.BUKKIT,
                LibrarySettings::new, List::of, () -> Path.of("/srv/dumps"),
                new FakeTransfers(), () -> CompletableFuture.completedFuture(outcome));
    }

    @Test
    @DisplayName("update says what it is doing before it waits on GitHub")
    void updateAnnouncesTheCheck() {
        withUpdate(new UpdateOutcome(UpdateStatus.UP_TO_DATE, "1.64.0", null)).update(sender);

        assertEquals(2, sent.size(), "one line before the check, one after");
        assertTrue(sent.get(0).contains("Checking"), "got: " + sent.get(0));
    }

    @Test
    @DisplayName("a staged release tells the admin to restart, not that it is done")
    void updateStagedAsksForARestart() {
        withUpdate(new UpdateOutcome(UpdateStatus.STAGED, "1.65.0", null)).update(sender);

        String text = sent.get(1);
        assertTrue(text.contains("1.65.0"), "got: " + text);
        assertTrue(text.contains("Restart"), "a staged jar is not applied yet, got: " + text);
    }

    @Test
    @DisplayName("a jar already waiting is not announced as a fresh download")
    void updateAlreadyStagedSaysSo() {
        withUpdate(new UpdateOutcome(UpdateStatus.ALREADY_STAGED, "1.65.0", null)).update(sender);

        String text = sent.get(1);
        assertTrue(text.contains("Already staged"), "got: " + text);
        assertTrue(text.contains("Restart"), "got: " + text);
    }

    @Test
    @DisplayName("a failed check says nothing changed and which version is still running")
    void updateFailureIsHonest() {
        withUpdate(new UpdateOutcome(UpdateStatus.FAILED, "1.64.0", "Manifest fetch returned HTTP 404"))
                .update(sender);

        String text = sent.get(1);
        assertTrue(text.contains("HTTP 404"), "the sender is told why, got: " + text);
        assertTrue(text.contains("1.64.0"), "got: " + text);
        assertTrue(text.contains("Nothing was changed"), "got: " + text);
    }

    @Test
    @DisplayName("auto-update being off does not make the typed command refuse")
    void updateDisabledStillAnswers() {
        withUpdate(new UpdateOutcome(UpdateStatus.DISABLED, "1.64.0", null)).update(sender);

        assertTrue(sent.get(1).contains("Up to date"), "got: " + sent.get(1));
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
    // ------------------------------------------------------------------ wipe

    /** The code the preview handed out, read back out of what it printed. */
    private static String codeFrom(String preview) {
        String[] words = preview.trim().split("\\s+");
        return words[words.length - 1];
    }

    @Test
    @DisplayName("the first wipe deletes nothing and hands back a code")
    void wipeAsksBeforeItDeletes() {
        ReloadCommand command = withTransfers();
        transfers.tables.put("Practice", List.of("practice_stats", "practice_kits"));

        command.wipe(sender, "Practice", "*", null);

        String text = sent.get(0);
        assertTrue(transfers.wiped.isEmpty(), "nothing may be deleted before a confirmation");
        assertTrue(text.contains("no undo"), "got: " + text);
        assertTrue(text.contains("practice_stats"), "what would go is named, got: " + text);
        assertTrue(text.contains("/exylialib wipe Practice *"), "got: " + text);
    }

    @Test
    @DisplayName("the code confirms, and the wipe takes a dump before it deletes")
    void wipeRunsAfterTheCode() {
        ReloadCommand command = withTransfers();
        transfers.tables.put("Practice", List.of("practice_stats"));

        command.wipe(sender, "Practice", "*", null);
        command.wipe(sender, "Practice", "*", codeFrom(sent.get(0)));

        assertEquals(List.of("Practice:*"), transfers.wiped);
        assertEquals(Path.of("/srv/dumps"), transfers.lastFile, "the backup export ran first");
        String panel = sent.get(sent.size() - 1);
        assertTrue(panel.contains("Practice-h2.exyliadump.gz"), "the dump is named, got: " + panel);
        assertTrue(panel.contains("/exylialib import Practice"), "restoring is spelled out, got: " + panel);
    }

    @Test
    @DisplayName("a code is spent once: the same line run twice does not wipe twice")
    void wipeCodeIsSingleUse() {
        ReloadCommand command = withTransfers();
        transfers.tables.put("Practice", List.of("practice_stats"));

        command.wipe(sender, "Practice", "*", null);
        String code = codeFrom(sent.get(0));
        command.wipe(sender, "Practice", "*", code);
        command.wipe(sender, "Practice", "*", code);

        assertEquals(1, transfers.wiped.size(), "the second run must refuse: " + transfers.wiped);
        assertTrue(sent.get(sent.size() - 1).contains("not valid"), "got: " + sent.get(sent.size() - 1));
    }

    @Test
    @DisplayName("a code issued for one table does not confirm another")
    void wipeCodeIsBoundToItsTarget() {
        ReloadCommand command = withTransfers();
        transfers.tables.put("Practice", List.of("practice_stats", "practice_kits"));

        command.wipe(sender, "Practice", "practice_stats", null);
        command.wipe(sender, "Practice", "practice_kits", codeFrom(sent.get(0)));

        assertTrue(transfers.wiped.isEmpty(), "got: " + transfers.wiped);
        assertTrue(sent.get(1).contains("Nothing was deleted"), "got: " + sent.get(1));
    }

    @Test
    @DisplayName("a failed backup cancels the wipe")
    void wipeStopsWhenTheBackupFails() {
        ReloadCommand command = withTransfers();
        transfers.tables.put("Practice", List.of("practice_stats"));
        transfers.exportAnswer = TransferReport.failed("The disk is full.", Duration.ZERO);

        command.wipe(sender, "Practice", "*", null);
        command.wipe(sender, "Practice", "*", codeFrom(sent.get(0)));

        assertTrue(transfers.wiped.isEmpty(), "a wipe without its dump must not run");
        String text = sent.get(sent.size() - 1);
        assertTrue(text.contains("Cancelled"), "got: " + text);
        assertTrue(text.contains("disk is full"), "got: " + text);
    }

    @Test
    @DisplayName("all is accepted as * , and the panel still prints the form it suggests")
    void wipeAcceptsTheWordAll() {
        ReloadCommand command = withTransfers();
        transfers.tables.put("Practice", List.of("practice_stats", "practice_kits"));

        command.wipe(sender, "Practice", "all", null);
        command.wipe(sender, "Practice", "all", codeFrom(sent.get(0)));

        assertEquals(List.of("Practice:*"), transfers.wiped);
        assertTrue(sent.get(0).contains("every registered table"), "got: " + sent.get(0));
    }

    @Test
    @DisplayName("a table the plugin does not have is refused, with the ones it does")
    void wipeRefusesAnUnknownTable() {
        ReloadCommand command = withTransfers();
        transfers.tables.put("Practice", List.of("practice_stats"));

        command.wipe(sender, "Practice", "practice_playres", null);

        String text = sent.get(0);
        assertTrue(transfers.wiped.isEmpty());
        assertTrue(text.contains("practice_stats"), "got: " + text);
    }

}
