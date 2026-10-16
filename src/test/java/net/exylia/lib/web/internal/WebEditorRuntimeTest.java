package net.exylia.lib.web.internal;

import com.google.gson.JsonObject;
import net.exylia.lib.FakeServer;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.internal.LibrarySettings;
import net.exylia.lib.web.Subject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Open, apply and trust against a fake editor: what is written, what is held back, what is said. */
class WebEditorRuntimeTest {

    @TempDir
    Path folder;

    private final List<String> sent = new ArrayList<>();
    private final FakeClient editor = new FakeClient();
    private final AtomicInteger applied = new AtomicInteger();
    private Plugin plugin;
    private ConfigFile<Board> file;
    private WebEditorRuntime runtime;
    private CommandSender admin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("ExyliaDisplays", folder.toFile());
        file = Configs.define(plugin, "board", Board.class).load();
        runtime = new WebEditorRuntime(LibrarySettings::new, () -> "server-1", "1.304.0",
                base -> editor, Runnable::run, Runnable::run, Debug.of(plugin));
        WebEditorRuntime.install(runtime);
        runtime.register(plugin, "board", file, "test/board")
                .title("Boards")
                .sensitive("entries.*.click")
                .onApplied(applied::incrementAndGet);
        admin = (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "sendMessage" -> {
                        if (args[0] instanceof Component component) {
                            sent.add(PlainTextComponentSerializer.plainText().serialize(component));
                        }
                        yield null;
                    }
                    case "hasPermission" -> true;
                    case "getName" -> "Admin";
                    default -> FakeServer.defaultValue(method.getReturnType());
                });
    }

    @AfterEach
    void tearDown() {
        WebEditorRuntime.install(null);
        Configs.release(plugin);
    }

    private String open() {
        runtime.open(admin, null);
        String code = editor.lastCode;
        assertNotNull(code, "the editor was not asked for a session");
        return code;
    }

    private JsonObject currentValues() {
        return ValueCodec.toJson(Board.class, plugin.getName(), file.get());
    }

    private void saveInBrowser(String code, JsonObject values, String baseHash) {
        JsonObject subjects = new JsonObject();
        subjects.add("ExyliaDisplays/board", values);
        JsonObject hashes = new JsonObject();
        if (baseHash != null) {
            hashes.addProperty("ExyliaDisplays/board", baseHash);
        }
        editor.pending = new EditorClient.Pending("change-1", subjects, hashes, "a browser");
    }

    @Test
    void openingUploadsTheSnapshotAndTellsTheLink() {
        String code = open();
        JsonObject snapshot = editor.lastSnapshot;
        assertEquals("server-1", snapshot.getAsJsonObject("server").get("id").getAsString());
        JsonObject subject = snapshot.getAsJsonArray("subjects").get(0).getAsJsonObject();
        assertEquals("ExyliaDisplays/board", subject.get("key").getAsString());
        assertEquals("Boards", subject.get("title").getAsString());
        assertEquals("test/board", subject.get("kind").getAsString());
        assertEquals("entries.*.click", subject.getAsJsonArray("sensitive").get(0).getAsString());
        assertTrue(subject.has("schema") && subject.has("values") && subject.has("hash"));
        assertNotNull(runtime.session(code));
        assertFalse(runtime.session(code).secret().isEmpty());
        assertEquals(editor.lastSecret, runtime.session(code).secret());
        String block = String.join("\n", sent);
        assertTrue(block.contains("OPEN THE EDITOR"));
        assertFalse(block.contains(code), "the code must never be printed");
    }

    @Test
    void aPlainChangeIsWrittenAndTheListenerRuns() {
        String code = open();
        JsonObject values = currentValues();
        values.getAsJsonObject("look").addProperty("shadow", true);
        saveInBrowser(code, values, ValueCodec.hash(currentValues()));

        runtime.apply(admin, code, false);

        assertTrue(file.get().look().shadow());
        assertEquals(1, applied.get());
        assertEquals("change-1", editor.appliedChange);
        assertEquals("ExyliaDisplays/board", editor.appliedOutcome.getAsJsonArray("applied").get(0).getAsString());
        assertTrue(sent.get(sent.size() - 1).contains("changes written"));
    }

    @Test
    void aSensitivePathIsHeldBackUntilConfirmed() {
        String code = open();
        JsonObject values = currentValues();
        JsonObject spawn = new JsonObject();
        spawn.addProperty("enabled", true);
        spawn.addProperty("scale", 1.0);
        JsonObject click = new JsonObject();
        com.google.gson.JsonArray commands = new com.google.gson.JsonArray();
        commands.add("say hello");
        click.add("commands", commands);
        spawn.add("click", click);
        values.getAsJsonObject("entries").add("spawn", spawn);
        saveInBrowser(code, values, ValueCodec.hash(currentValues()));

        runtime.apply(admin, code, false);
        assertTrue(file.get().entries().isEmpty(), "written without confirmation");
        assertNull(editor.appliedChange);
        assertTrue(String.join("\n", sent).contains("APPLY ANYWAY"));

        runtime.apply(admin, code, true);
        assertEquals(List.of("say hello"), file.get().entries().get("spawn").click().commands());
        assertEquals("change-1", editor.appliedChange);
    }

    @Test
    void aConsoleLineAnywhereIsHeldBackToo() {
        String code = open();
        JsonObject values = currentValues();
        com.google.gson.JsonArray lines = new com.google.gson.JsonArray();
        lines.add("console: op Steve");
        values.add("lines", lines);
        saveInBrowser(code, values, ValueCodec.hash(currentValues()));

        runtime.apply(admin, code, false);
        assertEquals(List.of("hello"), file.get().lines());
        assertTrue(sent.get(sent.size() - 1).contains("runs as the console"));
    }

    @Test
    void aFileThatChangedSinceTheSnapshotIsHeldBack() {
        String code = open();
        String stale = ValueCodec.hash(currentValues());
        file.update(board -> board.withLook(new Board.Look(true)));
        JsonObject values = currentValues();
        values.addProperty("mode", "slow-motion");
        saveInBrowser(code, values, stale);

        runtime.apply(admin, code, false);
        assertEquals(Board.Mode.FAST, file.get().mode());
        assertTrue(sent.get(sent.size() - 1).contains("changed on the server"));
    }

    @Test
    void aValueTheFileRefusesIsRejectedAndReported() {
        String code = open();
        JsonObject values = currentValues();
        values.addProperty("refresh", "not a time");
        saveInBrowser(code, values, ValueCodec.hash(currentValues()));

        runtime.apply(admin, code, false);
        assertEquals(20, file.get().refresh());
        assertEquals(0, applied.get());
        assertTrue(editor.appliedOutcome.getAsJsonObject("rejected").has("ExyliaDisplays/board"));
        assertTrue(sent.get(sent.size() - 1).contains("refused by the file's own rules"));
    }

    @Test
    void aSubjectOutsideTheSessionIsRejected() {
        String code = open();
        JsonObject subjects = new JsonObject();
        subjects.add("ExyliaOther/thing", new JsonObject());
        editor.pending = new EditorClient.Pending("change-2", subjects, new JsonObject(), null);

        runtime.apply(admin, code, false);
        assertTrue(sent.get(sent.size() - 1).contains("not registered"));
    }

    @Test
    void unknownSessionsAndEmptyBrowsersAreExplained() {
        runtime.apply(admin, "nope", false);
        assertTrue(sent.get(sent.size() - 1).contains("No session"));

        String code = open();
        runtime.apply(admin, code, false);
        assertTrue(sent.get(sent.size() - 1).contains("Nothing has been saved"));
    }

    @Test
    void trustIsForwardedWithTheSecret() {
        String code = open();
        runtime.trust(admin, code, "ABC123");
        assertEquals("ABC123", editor.trustedChallenge);
        assertEquals(editor.lastSecret, editor.trustSecret);
        assertTrue(sent.get(sent.size() - 1).contains("may now edit"));

        editor.trustAnswer = EditorClient.Trust.UNKNOWN_CHALLENGE;
        runtime.trust(admin, code, "ZZZ");
        assertTrue(sent.get(sent.size() - 1).contains("No browser is waiting"));
    }

    @Test
    void aDisabledEditorRefusesAndAPermissionHidesASubject() {
        LibrarySettings off = new LibrarySettings();
        WebEditorRuntime disabled = new WebEditorRuntime(() -> withEditor(off, false), () -> "server-1", "1.304.0",
                base -> editor, Runnable::run, Runnable::run, Debug.of(plugin));
        disabled.open(admin, null);
        assertTrue(sent.get(sent.size() - 1).contains("turned off"));

        Subject subject = runtime.register(plugin, "secret", file, Subject.GENERIC).permission("exylia.secret");
        CommandSender nobody = (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> false;
                    case "getName" -> "Nobody";
                    default -> FakeServer.defaultValue(method.getReturnType());
                });
        assertEquals(1, runtime.visible(nobody, null).size());
        assertEquals(2, runtime.visible(admin, null).size());
        assertEquals("secret", subject.id());
        assertTrue(runtime.editablePlugins().contains("ExyliaDisplays"));
        WebEditorRuntime.release("ExyliaDisplays");
        assertTrue(runtime.visible(admin, null).isEmpty());
    }

    private static LibrarySettings withEditor(LibrarySettings base, boolean enabled) {
        return new LibrarySettings(base.language(), base.autoUpdate(), base.updateCheckMinutes(), base.debug(),
                base.smallText(), base.textShadow(), base.fallbackHead(), base.timezone(), base.mineskinKey(),
                base.ragdollSkinQuality(), base.metrics(), base.pluginUpdates(),
                new LibrarySettings.WebEditor(enabled, base.webEditor().url()), base.webhookHead(),
                base.settings(), base.toasts(), base.notices(), base.logs());
    }

    /** The editor, remembered rather than reached. */
    private static final class FakeClient implements EditorClient {
        String lastCode;
        String lastSecret;
        JsonObject lastSnapshot;
        Pending pending;
        String appliedChange;
        JsonObject appliedOutcome;
        String trustedChallenge;
        String trustSecret;
        Trust trustAnswer = Trust.TRUSTED;
        private int opened;

        @Override
        public Opened open(String secret, JsonObject snapshot) {
            lastSecret = secret;
            lastSnapshot = snapshot;
            lastCode = "c0de" + (++opened);
            return new Opened(lastCode, "https://utils.test/e/" + lastCode, 0L);
        }

        @Override
        public Pending pending(String code, String secret) throws IOException {
            if (!secret.equals(lastSecret)) {
                throw new Refused(401, "wrong secret");
            }
            return pending;
        }

        @Override
        public void applied(String code, String secret, String changeId, JsonObject outcome, JsonObject snapshot) {
            appliedChange = changeId;
            appliedOutcome = outcome;
            pending = null;
        }

        @Override
        public Trust trust(String code, String secret, String challenge) {
            trustedChallenge = challenge;
            trustSecret = secret;
            return trustAnswer;
        }
    }
}
