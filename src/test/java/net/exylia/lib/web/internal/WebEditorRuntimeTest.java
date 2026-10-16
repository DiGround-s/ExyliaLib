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

    /** Another plugin's data folder, outside the first one's. */
    @TempDir
    Path other;

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
        net.exylia.lib.reload.Reloads.release("ExyliaMines");
        net.exylia.lib.ui.Menus.releaseAll();
        net.exylia.lib.action.Actions.releaseAll();
        FakeServer.plugins();
    }

    private Plugin mines() throws IOException {
        Plugin mines = FakeServer.newPlugin("ExyliaMines", other.toFile());
        java.nio.file.Files.writeString(other.resolve("rewards.yml"), "daily:\n  money: 5\n");
        java.nio.file.Files.writeString(other.resolve("shop.yml"), "price: 1\n");
        java.nio.file.Files.createDirectories(other.resolve("menus"));
        java.nio.file.Files.writeString(other.resolve("menus/shop.yml"),
                "title: Shop\nsize: 27\nitems:\n  stone:\n    slot: 13\n    material: STONE\n");
        FakeServer.plugins(plugin, mines);
        return mines;
    }

    private JsonObject uploaded(String plugin, String key) {
        for (var element : editor.uploads.get(plugin).getAsJsonArray("subjects")) {
            if (element.getAsJsonObject().get("key").getAsString().equals(key)) {
                return element.getAsJsonObject();
            }
        }
        throw new AssertionError(key + " was not uploaded");
    }

    private static JsonObject listedPlugin(JsonObject index, String name) {
        for (var element : index.getAsJsonArray("plugins")) {
            if (element.getAsJsonObject().get("name").getAsString().equals(name)) {
                return element.getAsJsonObject();
            }
        }
        throw new AssertionError(name + " is not in the index");
    }

    @Test
    void openingSendsTheIndexThenEveryPluginInTurn() throws IOException {
        mines();
        Plugin quiet = FakeServer.newPlugin("ExyliaQuiet", folder.resolve("quiet-elsewhere").toFile());
        FakeServer.plugins(plugin, FakeServer.newPlugin("ExyliaMines", other.toFile()), quiet);
        runtime.discover("ExyliaQuiet", false);

        open();

        assertEquals(List.of("open", "upload ExyliaDisplays", "upload ExyliaMines"), editor.calls);
        JsonObject quietListed = listedPlugin(editor.lastIndex, "ExyliaQuiet");
        assertFalse(quietListed.get("editable").getAsBoolean());
        assertEquals(0, quietListed.getAsJsonArray("subjects").size());
        long boards = 0;
        for (var subject : listedPlugin(editor.lastIndex, "ExyliaDisplays").getAsJsonArray("subjects")) {
            boards += subject.getAsJsonObject().get("file").getAsString().equals("board") ? 1 : 0;
        }
        assertEquals(1, boards, "the registered board covers board.yml");
        assertEquals("files", uploaded("ExyliaMines", "ExyliaMines/rewards").get("group").getAsString());
        assertEquals("menus", uploaded("ExyliaMines", "ExyliaMines/menus-shop").get("group").getAsString());
        assertTrue(String.join("\n", sent).contains("OPEN THE EDITOR"));
    }

    @Test
    void aPluginThatFailsToUploadIsNamedAndTheNextStillGoes() throws IOException {
        mines();
        editor.failFor = "ExyliaDisplays";

        open();

        assertEquals(List.of("open", "upload ExyliaMines"), editor.calls);
        assertTrue(String.join("\n", sent).contains("ExyliaDisplays could not be sent to the editor"), String.join("\n", sent));
    }

    @Test
    void applyingDiscoveredFilesReloadsThePluginOnceAndUploadsWhatItTouched() throws IOException {
        Plugin mines = mines();
        AtomicInteger reloads = new AtomicInteger();
        net.exylia.lib.reload.Reloads.of(mines).step("files", reloads::incrementAndGet);
        String code = open();
        editor.calls.clear();

        JsonObject rewards = uploaded("ExyliaMines", "ExyliaMines/rewards").getAsJsonObject("values").deepCopy();
        rewards.getAsJsonObject("daily").addProperty("money", 9);
        JsonObject shop = uploaded("ExyliaMines", "ExyliaMines/shop").getAsJsonObject("values").deepCopy();
        shop.addProperty("price", 2);
        JsonObject subjects = new JsonObject();
        subjects.add("ExyliaMines/rewards", rewards);
        subjects.add("ExyliaMines/shop", shop);
        editor.pending = new EditorClient.Pending("change-1", subjects, new JsonObject(), "a browser");

        runtime.apply(admin, code, false);

        assertEquals(1, reloads.get(), "one reload per plugin, not one per file");
        assertEquals(List.of("applied", "upload ExyliaMines"), editor.calls);
        assertTrue(java.nio.file.Files.readString(other.resolve("rewards.yml")).contains("money: 9"));
        assertTrue(java.nio.file.Files.readString(other.resolve("shop.yml")).contains("price: 2"));
        assertEquals(2, editor.appliedOutcome.getAsJsonArray("applied").size());
    }

    @Test
    void aDiscoveredMenuThatWouldNotCompileIsRefused() throws IOException {
        mines();
        String code = open();
        JsonObject menu = uploaded("ExyliaMines", "ExyliaMines/menus-shop").getAsJsonObject("values").deepCopy();
        menu.addProperty("size", 7);
        JsonObject subjects = new JsonObject();
        subjects.add("ExyliaMines/menus-shop", menu);
        editor.pending = new EditorClient.Pending("change-1", subjects, new JsonObject(), "a browser");

        runtime.apply(admin, code, false);

        assertTrue(editor.appliedOutcome.getAsJsonObject("rejected").has("ExyliaMines/menus-shop"), editor.appliedOutcome.toString());
        assertTrue(java.nio.file.Files.readString(other.resolve("menus/shop.yml")).contains("size: 27"));

        menu.addProperty("size", 54);
        editor.pending = new EditorClient.Pending("change-2", subjects, new JsonObject(), "a browser");
        runtime.apply(admin, code, false);
        assertTrue(java.nio.file.Files.readString(other.resolve("menus/shop.yml")).contains("size: 54"));
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
        assertEquals("server-1", editor.lastIndex.getAsJsonObject("server").get("id").getAsString());
        assertEquals(2, editor.lastIndex.get("version").getAsInt());
        JsonObject listed = editor.lastIndex.getAsJsonArray("plugins").get(0).getAsJsonObject()
                .getAsJsonArray("subjects").get(0).getAsJsonObject();
        assertEquals("content", listed.get("group").getAsString());
        assertFalse(listed.has("values"), "the index carries metadata only");
        assertEquals(List.of("open", "upload ExyliaDisplays"), editor.calls);
        JsonObject subject = editor.firstSubject();
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
    void aYamlSubjectIsWrittenAfterThePluginsCheck() throws IOException {
        java.nio.file.Path catalog = folder.resolve("cosmetics/tags.yml");
        java.nio.file.Files.createDirectories(catalog.getParent());
        java.nio.file.Files.writeString(catalog, "tags:\n  star:\n    display: '&e*'\n");
        AtomicInteger reloaded = new AtomicInteger();
        runtime.register(plugin, "board", file, "test/board").permission("nobody");
        runtime.registerYaml(plugin, "tags", "cosmetics/tags", "test/catalog")
                .check(yaml -> yaml.getString("tags.star.display", "").isBlank() ? List.of("star has no display") : List.of())
                .onApplied(reloaded::incrementAndGet);
        java.util.function.Function<String, JsonObject> change = display -> {
            JsonObject star = new JsonObject();
            star.addProperty("display", display);
            JsonObject tags = new JsonObject();
            tags.add("star", star);
            JsonObject values = new JsonObject();
            values.add("tags", tags);
            return values;
        };

        CommandSender limited = (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> !"nobody".equals(args[0]);
                    case "getName" -> "Admin";
                    default -> FakeServer.defaultValue(method.getReturnType());
                });
        runtime.open(limited, null);
        String code = editor.lastCode;
        JsonObject subject = editor.firstSubject();
        assertEquals("ExyliaDisplays/tags", subject.get("key").getAsString());
        assertEquals("cosmetics/tags", subject.get("file").getAsString());
        assertEquals("map", subject.getAsJsonObject("schema").getAsJsonArray("fields").get(0).getAsJsonObject()
                .getAsJsonObject("type").get("type").getAsString());

        JsonObject subjects = new JsonObject();
        subjects.add("ExyliaDisplays/tags", change.apply(""));
        editor.pending = new EditorClient.Pending("change-1", subjects, new JsonObject(), "a browser");
        runtime.apply(limited, code, false);
        assertEquals(0, reloaded.get());
        assertTrue(editor.appliedOutcome.getAsJsonObject("rejected").get("ExyliaDisplays/tags").getAsString()
                .contains("star has no display"));

        subjects = new JsonObject();
        subjects.add("ExyliaDisplays/tags", change.apply("&6*"));
        editor.pending = new EditorClient.Pending("change-2", subjects, new JsonObject(), "a browser");
        runtime.apply(limited, code, false);
        assertEquals(1, reloaded.get());
        assertTrue(java.nio.file.Files.readString(catalog).contains("display: '&6*'"));
    }

    /** A store kept in memory, recording what it was asked to do. */
    private static final class Pits implements net.exylia.lib.web.EntryStore<Pit> {
        final Map<String, Pit> rows = new java.util.LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        java.util.concurrent.CompletableFuture<Void> answer = java.util.concurrent.CompletableFuture.completedFuture(null);

        @Override
        public Map<String, Pit> entries() {
            return Map.copyOf(rows).isEmpty() ? Map.of() : new java.util.LinkedHashMap<>(rows);
        }

        @Override
        public List<String> check(String id, Pit value, boolean created) {
            return value.name().isBlank() ? List.of("a pit needs a name") : List.of();
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> save(String id, Pit value, boolean created) {
            calls.add((created ? "create " : "save ") + id);
            if (answer.isDone() && !answer.isCompletedExceptionally()) {
                rows.put(id, value);
            }
            return answer;
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> delete(String id) {
            calls.add("delete " + id);
            rows.remove(id);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }

    private Pits pits() {
        Pits store = new Pits();
        store.rows.put("spawn", new Pit("Spawn", Pit.Kind.CLASSIC, List.of(new Pit.Layer("STONE", 3))));
        store.rows.put("deep", new Pit("Deep", Pit.Kind.REALISTIC, List.of()));
        runtime.register(plugin, "board", file, "test/board").permission("nobody");
        runtime.registerEntries(plugin, "pits", Pit.class, store, "test/pit").onApplied(applied::incrementAndGet);
        return store;
    }

    private CommandSender limited() {
        return (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "sendMessage" -> {
                        if (args[0] instanceof Component component) {
                            sent.add(PlainTextComponentSerializer.plainText().serialize(component));
                        }
                        yield null;
                    }
                    case "hasPermission" -> !"nobody".equals(args[0]);
                    case "getName" -> "Admin";
                    default -> FakeServer.defaultValue(method.getReturnType());
                });
    }

    private JsonObject pitValues() {
        JsonObject subject = editor.firstSubject();
        return subject.getAsJsonObject("values").deepCopy();
    }

    private void savePits(JsonObject values) {
        JsonObject subjects = new JsonObject();
        subjects.add("ExyliaDisplays/pits", values);
        editor.pending = new EditorClient.Pending("change-" + sent.size(), subjects, new JsonObject(), "a browser");
    }

    @Test
    void entriesAreDescribedAsAMapOfTheirRecord() {
        pits();
        runtime.open(limited(), null);
        JsonObject subject = editor.firstSubject();
        assertEquals("", subject.get("file").getAsString());
        JsonObject field = subject.getAsJsonObject("schema").getAsJsonArray("fields").get(0).getAsJsonObject();
        assertEquals("pits", field.get("key").getAsString());
        assertEquals("map", field.getAsJsonObject("type").get("type").getAsString());
        assertEquals("STONE", subject.getAsJsonObject("values").getAsJsonObject("pits").getAsJsonObject("spawn")
                .getAsJsonArray("layers").get(0).getAsJsonObject().get("block").getAsString());
    }

    @Test
    void anEntryAddedAndOneEditedAreWrittenAndOnlyThose() {
        Pits store = pits();
        CommandSender admin = limited();
        runtime.open(admin, null);
        String code = editor.lastCode;
        JsonObject values = pitValues();
        JsonObject entries = values.getAsJsonObject("pits");
        entries.getAsJsonObject("spawn").addProperty("name", "Spawn mine");
        JsonObject fresh = new JsonObject();
        fresh.addProperty("kind", "realistic");
        entries.add("lava", fresh);
        savePits(values);

        runtime.apply(admin, code, false);

        assertEquals(List.of("save spawn", "create lava"), store.calls);
        assertEquals("Spawn mine", store.rows.get("spawn").name());
        assertEquals(new Pit("Pit", Pit.Kind.REALISTIC, List.of()), store.rows.get("lava"), "a new entry starts from the defaults");
        assertEquals(1, applied.get());
        assertEquals("ExyliaDisplays/pits", editor.appliedOutcome.getAsJsonArray("applied").get(0).getAsString());
    }

    @Test
    void aDeletionIsHeldUntilConfirmed() {
        Pits store = pits();
        CommandSender admin = limited();
        runtime.open(admin, null);
        String code = editor.lastCode;
        JsonObject values = pitValues();
        values.getAsJsonObject("pits").remove("deep");
        savePits(values);

        runtime.apply(admin, code, false);
        assertTrue(store.calls.isEmpty());
        assertTrue(String.join("\n", sent).contains("deletes deep"), String.join("\n", sent));

        runtime.apply(admin, code, true);
        assertEquals(List.of("delete deep"), store.calls);
        assertFalse(store.rows.containsKey("deep"));
    }

    @Test
    void theBinderTheStoreAndTheDatabaseCanEachRefuse() {
        Pits store = pits();
        CommandSender admin = limited();
        runtime.open(admin, null);
        String code = editor.lastCode;

        JsonObject values = pitValues();
        values.getAsJsonObject("pits").getAsJsonObject("spawn").addProperty("kind", "volcanic");
        savePits(values);
        runtime.apply(admin, code, false);
        assertTrue(store.calls.isEmpty(), "a value the record refuses never reaches the store");
        assertTrue(editor.appliedOutcome.getAsJsonObject("rejected").get("ExyliaDisplays/pits").getAsString().contains("spawn"));

        values = pitValues();
        values.getAsJsonObject("pits").getAsJsonObject("spawn").addProperty("name", "");
        savePits(values);
        runtime.apply(admin, code, false);
        assertTrue(store.calls.isEmpty());
        assertTrue(editor.appliedOutcome.getAsJsonObject("rejected").get("ExyliaDisplays/pits").getAsString()
                .contains("a pit needs a name"));

        store.answer = java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("the database is down"));
        values = pitValues();
        values.getAsJsonObject("pits").getAsJsonObject("spawn").addProperty("name", "Other");
        savePits(values);
        runtime.apply(admin, code, false);
        assertEquals(List.of("save spawn"), store.calls);
        assertEquals(0, applied.get(), "nothing written, nothing to apply");
        assertTrue(editor.appliedOutcome.getAsJsonObject("rejected").get("ExyliaDisplays/pits").getAsString()
                .contains("spawn: the database is down"));
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
    static final class FakeClient implements EditorClient {
        String lastCode;
        String lastSecret;
        JsonObject lastIndex;
        final List<String> calls = new ArrayList<>();
        final Map<String, JsonObject> uploads = new java.util.LinkedHashMap<>();
        Pending pending;
        String appliedChange;
        JsonObject appliedOutcome;
        String trustedChallenge;
        String trustSecret;
        Trust trustAnswer = Trust.TRUSTED;
        String failFor;
        private int opened;

        JsonObject firstSubject() {
            return uploads.values().iterator().next().getAsJsonArray("subjects").get(0).getAsJsonObject();
        }

        @Override
        public Opened open(String secret, JsonObject index) {
            calls.add("open");
            lastSecret = secret;
            lastIndex = index;
            lastCode = "c0de" + (++opened);
            return new Opened(lastCode, "https://utils.test/e/" + lastCode, 0L);
        }

        @Override
        public void uploadPlugin(String code, String secret, String plugin, JsonObject payload) throws IOException {
            if (!secret.equals(lastSecret)) {
                throw new Refused(401, "wrong secret");
            }
            if (plugin.equals(failFor)) {
                throw new Refused(500, "boom");
            }
            calls.add("upload " + plugin);
            uploads.put(plugin, payload);
        }

        @Override
        public Pending pending(String code, String secret) throws IOException {
            if (!secret.equals(lastSecret)) {
                throw new Refused(401, "wrong secret");
            }
            return pending;
        }

        @Override
        public void applied(String code, String secret, String changeId, JsonObject outcome) {
            calls.add("applied");
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
