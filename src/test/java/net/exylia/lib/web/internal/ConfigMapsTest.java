package net.exylia.lib.web.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.exylia.lib.FakeServer;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.internal.LibrarySettings;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A record file's maps and lists, edited in game the way content files are. */
class ConfigMapsTest {

    enum Mode { SOFT_RESET, HARD }

    record Zone(int size, String name, Mode mode, Duration lasts) {
        Zone() {
            this(3, "zone", Mode.HARD, Duration.ofSeconds(5));
        }
    }

    record Root(Map<String, Zone> zones, List<Zone> steps, Map<String, Integer> weights) {
        Root() {
            this(Map.of("a", new Zone(7, "first", Mode.SOFT_RESET, Duration.ofMinutes(1))),
                    List.of(new Zone()), Map.of("x", 1));
        }
    }

    @TempDir
    Path folder;

    private Plugin plugin;
    private ConfigFile<Root> file;
    private WebEditorRuntime runtime;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("ExyliaMines", folder.toFile());
        file = Configs.define(plugin, "config", Root.class).load();
        runtime = new WebEditorRuntime(LibrarySettings::new, () -> "server-1", "1.304.0",
                base -> null, Runnable::run, Runnable::run, Debug.of(plugin));
        WebEditorRuntime.install(runtime);
    }

    @AfterEach
    void tearDown() {
        WebEditorRuntime.install(null);
        Configs.release(plugin);
    }

    private SubjectImpl subject() {
        return runtime.settingSubjectsOf(plugin).stream().filter(s -> s.file() == file).findFirst().orElseThrow();
    }

    private void apply(SubjectImpl subject, JsonObject after) {
        WebEditorRuntime.InGame change = runtime.inGame(subject, after);
        assertNull(change.problem(), change.problem());
        change.write().run();
    }

    private JsonElement fresh(SubjectImpl subject, List<String> path) {
        JsonObject schema = subject.source().schema(plugin.getName());
        JsonObject type = YamlFields.type(schema, path);
        return ConfigMenu.fresh(subject, schema, path, type.getAsJsonObject("of"), subject.source().values(plugin.getName()));
    }

    @Test
    void mapsAndListsOfARecordAreEntries() {
        for (String key : List.of("zones", "steps", "weights")) {
            assertEquals(ConfigFields.Kind.ENTRIES, ConfigFields.field(file.schema(), key).kind(), key);
        }
        JsonObject schema = subject().source().schema(plugin.getName());
        assertEquals(ConfigFields.Kind.ENTRIES, YamlFields.kind(YamlFields.type(schema, List.of("steps"))));
        assertEquals(ConfigFields.Kind.CHOICE, YamlFields.kind(YamlFields.type(schema, List.of("zones", "a", "mode"))));
        assertEquals(ConfigFields.Kind.TIME, YamlFields.kind(YamlFields.type(schema, List.of("steps", "0", "lasts"))));
        assertTrue(YamlFields.sections(schema, List.of()));
        assertFalse(YamlFields.sections(schema, List.of("zones", "a")), "inside a map is a content screen");
    }

    @Test
    void aMapOfRecordsIsCreatedFromDefaultsDuplicatedAndDeleted() {
        SubjectImpl subject = subject();
        List<String> zones = List.of("zones");
        JsonObject values = subject.source().values(plugin.getName());
        apply(subject, YamlFields.with(values, YamlFields.child(zones, "b"), fresh(subject, zones)));
        assertEquals(new Zone(), file.get().zones().get("b"), "a new entry holds the record's defaults");

        values = subject.source().values(plugin.getName());
        apply(subject, YamlFields.added(values, zones, "a-copy", "a"));
        assertEquals(file.get().zones().get("a"), file.get().zones().get("a-copy"));

        values = subject.source().values(plugin.getName());
        JsonElement mode = YamlFields.next(YamlFields.type(subject.source().schema(plugin.getName()), List.of("zones", "a", "mode")),
                YamlFields.value(values, List.of("zones", "a", "mode")), 1);
        apply(subject, YamlFields.with(values, List.of("zones", "a", "mode"), mode));
        assertEquals(Mode.HARD, file.get().zones().get("a").mode());

        values = subject.source().values(plugin.getName());
        apply(subject, YamlFields.with(values, YamlFields.child(zones, "b"), null));
        assertFalse(file.get().zones().containsKey("b"));
        assertEquals(2, file.get().zones().size());
    }

    @Test
    void aListOfRecordsGainsADefaultElementAndLosesOne() {
        SubjectImpl subject = subject();
        List<String> steps = List.of("steps");
        JsonObject values = subject.source().values(plugin.getName());
        apply(subject, YamlFields.inserted(values, steps, Integer.MAX_VALUE, fresh(subject, steps)));
        assertEquals(List.of(new Zone(), new Zone()), file.get().steps());

        values = subject.source().values(plugin.getName());
        JsonObject type = YamlFields.type(subject.source().schema(plugin.getName()), List.of("steps", "1", "lasts"));
        apply(subject, YamlFields.with(values, List.of("steps", "1", "lasts"), YamlFields.time(type, Duration.ofSeconds(90))));
        assertEquals(Duration.ofSeconds(90), file.get().steps().get(1).lasts());
        assertEquals(Duration.ofSeconds(90), YamlFields.duration(type,
                YamlFields.value(subject.source().values(plugin.getName()), List.of("steps", "1", "lasts"))));

        values = subject.source().values(plugin.getName());
        apply(subject, YamlFields.with(values, List.of("steps", "0"), null));
        assertEquals(Duration.ofSeconds(90), file.get().steps().get(0).lasts(), "the second moved up");
        assertEquals(1, file.get().steps().size());
    }

    @Test
    void aMapOfNumbersIsEditedEntryByEntry() {
        SubjectImpl subject = subject();
        List<String> weights = List.of("weights");
        assertEquals(new JsonPrimitive(0), fresh(subject, weights));
        JsonObject values = subject.source().values(plugin.getName());
        apply(subject, YamlFields.with(values, List.of("weights", "x"), new JsonPrimitive(7)));
        values = subject.source().values(plugin.getName());
        apply(subject, YamlFields.with(values, List.of("weights", "y"), fresh(subject, weights)));
        assertEquals(Map.of("x", 7, "y", 0), file.get().weights());
    }

    @Test
    void aRegisteredRecordSubjectIsContentBehindItsPermission() {
        ConfigFile<Root> boards = Configs.define(plugin, "boards", Root.class).load();
        runtime.register(plugin, "boards", boards, "test/board").title("Boards").permission("mines.boards");
        SubjectImpl listed = runtime.contentOf(plugin).stream().filter(s -> s.id().equals("boards")).findFirst().orElseThrow();
        assertEquals("mines.boards", listed.permission());
        assertTrue(runtime.settingsOf(plugin).stream().noneMatch(f -> f == boards), "a registered file is content, not a setting");
    }
}
