package net.exylia.lib.spatial;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.exylia.lib.FakeServer;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.ConfigIssue;
import net.exylia.lib.config.Configs;
import net.exylia.lib.config.internal.RecordJson;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A layout as the web editor's {@code spatial/layout} screen gets it: its values and the details it draws them
 * with, and a change from the browser written back with the file's own comments kept.
 */
class LayoutEditorTest {

    @TempDir
    Path folder;

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Configs.releaseAll();
        plugin = FakeServer.newPlugin("LayoutEditorPlugin", folder.toFile());
    }

    private static String centreStage() throws Exception {
        try (InputStream in = LayoutEditorTest.class.getClassLoader().getResourceAsStream(SpatialMenus.CENTRE_STAGE)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    void theDetailsCarryTheBundledLayoutAndWhatTheMenuAnswers() throws Exception {
        SpatialMenu menu = new SpatialMenu("effects");
        menu.source("effects", ctx -> List.of()).action("replay", ctx -> { });
        menu.catalog = true;
        String text = centreStage();
        menu.samples(layout -> Map.of("effect_about_1", "First line", "%replay_label%", "Replay"));
        Map<String, Object> details = LayoutEditor.details(plugin, menu,
                () -> new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)), new SpatialLayout());
        JsonObject defaults = (JsonObject) details.get("defaults");
        assertEquals(4.5, defaults.getAsJsonObject("anchor").get("distance").getAsDouble(), 1e-9);
        assertTrue(defaults.getAsJsonObject("panels").has("action"), "the bundled panels, not the library's wardrobe");
        assertEquals(List.of("effects", SpatialCatalog.TABS), details.get("sources"));
        @SuppressWarnings("unchecked") List<String> actions = (List<String>) details.get("actions");
        assertTrue(actions.containsAll(List.of("replay", "save", "revert", "undo", "leave", "intro")), actions.toString());
        @SuppressWarnings("unchecked") Map<String, String> samples = (Map<String, String>) details.get("samples");
        assertTrue(samples.get("%catalog_action%").contains("EQUIP"));
        assertEquals("First line", samples.get("%effect_about_1%"), "the plugin's own, keyed with percent signs");
        assertEquals("Replay", samples.get("%replay_label%"));
        assertTrue(samples.values().stream().noneMatch(value -> value.contains("%name%") || value.contains("%price%")),
                "samples are filled: " + samples);
        // The whole thing is what Gson sends.
        String json = new Gson().toJson(details);
        assertTrue(json.contains("\"floorMargin\":0.15"), json);
    }

    @Test
    void aMenuWithNoBundledLayoutResetsToTheLibrarysWardrobe() {
        Map<String, Object> details = LayoutEditor.details(plugin, new SpatialMenu("plain"), () -> null, new SpatialLayout());
        JsonObject defaults = (JsonObject) details.get("defaults");
        assertTrue(defaults.getAsJsonObject("panels").has("header"));
    }

    @Test
    void aChangeFromTheBrowserIsWrittenWithTheFilesCommentsKept() throws Exception {
        Path file = folder.resolve("spatial/stage.yml");
        Files.createDirectories(file.getParent());
        String text = centreStage().replace("  action:\n", "  # A note the owner wrote.\n  action:\n");
        Files.writeString(file, text);
        ConfigFile<SpatialLayout> layout = Configs.define(plugin, "spatial/stage", SpatialLayout.class).load();
        JsonObject values = RecordJson.write(SpatialLayout.class, plugin.getName(), layout.get());
        // The browser moved the button up and made the backdrop fainter.
        values.getAsJsonObject("panels").getAsJsonObject("action").add("at", new Gson().toJsonTree(List.of(0.0, -1.4, 0.2)));
        values.getAsJsonObject("backdrop").addProperty("alpha", 120);
        List<ConfigIssue> issues = new ArrayList<>();
        SpatialLayout next = RecordJson.read(SpatialLayout.class, plugin.getName(), layout.get(), values, "spatial/stage", issues);
        assertTrue(issues.stream().noneMatch(issue -> issue.type() == ConfigIssue.Type.INVALID_VALUE), issues.toString());
        layout.update(current -> next);
        String written = Files.readString(file);
        assertTrue(written.contains("# A note the owner wrote."), written);
        assertTrue(written.contains("-1.4"), written);
        layout.reload();
        assertEquals(120, layout.get().backdrop().alpha());
        assertEquals(-1.4, layout.get().panel("action").up(), 1e-9);
    }

    @Test
    void aHiddenPieceReadsBackAndLeavesTheFloorAlone() throws Exception {
        Path file = folder.resolve("spatial/stage.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, centreStage());
        ConfigFile<SpatialLayout> layout = Configs.define(plugin, "spatial/stage", SpatialLayout.class).load();
        assertTrue(layout.get().panels().values().stream().allMatch(SpatialLayout.Panel::visible), "shown unless the file says");
        assertTrue(layout.get().preview().visible() && layout.get().carousel().present());
        double before = layout.get().lowest();
        SpatialLayout.Panel icons = layout.get().panel("icons");
        SpatialLayout hidden = layout.get().withPanel("icons", new SpatialLayout.Panel(icons.at(), icons.turn(), icons.size(),
                icons.title(), icons.hint(), icons.content(), icons.footer(), icons.footers(), icons.frame(), false));
        assertTrue(hidden.lowest() > before, "the hidden icon row no longer counts for the floor");
        layout.update(current -> hidden);
        layout.reload();
        assertTrue(!layout.get().panel("icons").visible());
        assertTrue(Files.readString(file).contains("visible: false"));
    }

    @Test
    void proseIsWrappedAsTheOpenMenuWrapsIt() throws Exception {
        Path file = folder.resolve("spatial/stage.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, centreStage().replace("  title:\n", "  about:\n    at: [0, 0.3, 0.1]\n    size: [3.1, 0.58]\n    frame: false\n    content: {type: TEXT, lines: ['%a%', '%b%', '%c%']}\n  title:\n"));
        SpatialLayout layout = Configs.define(plugin, "spatial/stage", SpatialLayout.class).load().get();
        List<String> lines = layout.wrap("about", "The blast lifts them into a backflip, bounces them twice, and leaves them in pieces.", 3);
        assertTrue(lines.size() >= 2 && lines.size() <= 3, lines.toString());
        assertEquals(List.of("one", "two"), layout.wrap("nowhere", "one\ntwo", 3), "an unknown panel keeps the text's own lines");
    }

    /**
     * Writes a subject payload of a layout file for the web editor's local tests: run with
     * {@code SPATIAL_DUMP_FROM=<layout.yml> SPATIAL_DUMP_TO=<out.json>}; does nothing otherwise.
     */
    @Test
    void dumpsAPayloadWhenAsked() throws Exception {
        String from = System.getenv("SPATIAL_DUMP_FROM");
        String to = System.getenv("SPATIAL_DUMP_TO");
        if (from == null || to == null) return;
        Path file = folder.resolve("spatial/effects.yml");
        Files.createDirectories(file.getParent());
        Files.copy(Path.of(from), file);
        ConfigFile<SpatialLayout> layout = Configs.define(plugin, "spatial/effects", SpatialLayout.class).load();
        SpatialMenu menu = new SpatialMenu("effects");
        menu.source("effects", ctx -> List.of()).action("replay", ctx -> { }).action("random", ctx -> { });
        menu.catalog = true;
        JsonObject subject = new JsonObject();
        subject.addProperty("key", "ExyliaKillEffect/spatial-effects");
        subject.addProperty("plugin", "ExyliaKillEffect");
        subject.addProperty("id", "spatial-effects");
        subject.addProperty("title", "Effects (3D layout)");
        subject.addProperty("kind", LayoutEditor.KIND);
        subject.addProperty("group", "content");
        subject.addProperty("file", "spatial/effects");
        subject.add("sensitive", new com.google.gson.JsonArray());
        subject.add("schema", RecordJson.schema(SpatialLayout.class, plugin.getName()));
        subject.add("values", RecordJson.write(SpatialLayout.class, plugin.getName(), layout.get()));
        subject.addProperty("hash", "local");
        subject.add("details", new Gson().toJsonTree(LayoutEditor.details(plugin, menu, () -> {
            try {
                return Files.newInputStream(Path.of(from));
            } catch (java.io.IOException e) {
                return null;
            }
        }, layout.get())));
        Files.writeString(Path.of(to), new GsonBuilder().setPrettyPrinting().create().toJson(subject));
    }
}
