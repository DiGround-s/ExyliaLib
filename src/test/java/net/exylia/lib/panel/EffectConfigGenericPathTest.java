package net.exylia.lib.panel;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.config.Configs;
import net.exylia.lib.effect.EffectConfig;
import net.exylia.lib.panel.internal.ControlKind;
import net.exylia.lib.panel.internal.PanelPrompts;
import net.exylia.lib.panel.internal.PanelRenderer;
import net.exylia.lib.panel.internal.PanelRuntime;
import net.exylia.lib.panel.internal.SettingsEngine;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole point of the change, stated as a test.
 *
 * <p>The effects editor is the settings panel pointed at {@code EffectConfig}
 * and <em>nothing else</em>. If a single class under {@code net.exylia.lib.panel}
 * ever names {@code EffectConfig} or one of its nested records, the abstraction
 * has failed and the next config record will need its own screen too.
 *
 * <p>Proved from compiled bytecode rather than from source text, because a
 * reference is what actually couples two classes: an import can be absent while
 * a fully-qualified name in the middle of a method does the coupling anyway, and
 * a comment mentioning the type couples nothing at all.
 *
 * <p>Three guards, for the reason the earlier sweeps in this change had to learn
 * twice: the absence assertion itself, a non-vacuity check naming the classes the
 * sweep must have examined, and a detector check feeding it a class that
 * <em>does</em> reference the banned type. An absence assertion that examined
 * nothing passes for the wrong reason.
 */
class EffectConfigGenericPathTest {

    @TempDir
    Path folder;

    /** The banned type and its nested records, by internal name. */
    private static final List<String> BANNED = List.of(
            "net/exylia/lib/effect/EffectConfig",
            "net/exylia/lib/effect/EffectConfig$Title",
            "net/exylia/lib/effect/EffectConfig$ActionBar",
            "net/exylia/lib/effect/EffectConfig$BossBar",
            "net/exylia/lib/effect/EffectConfig$Sound",
            "net/exylia/lib/effect/EffectConfig$Particle",
            "net/exylia/lib/effect/EffectConfig$Firework");

    private static final List<String> SWEPT_PACKAGES =
            List.of("net.exylia.lib.panel", "net.exylia.lib.panel.internal");

    private Plugin plugin;
    private Player viewer;
    private PanelRenderer.DrawSink previousSink;
    private final List<Drawn> drawn = new ArrayList<>();

    record Drawn(int slot, ControlKind kind, Object entry) {
    }

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Configs.releaseAll();
        plugin = FakeServer.newPlugin("EffectConfigGenericPathPlugin", folder.toFile());
        viewer = new FakePlayer("Steve").player();
        FakeServer.online(viewer);
        previousSink = PanelRenderer.sink((slot, kind, entry) -> drawn.add(new Drawn(slot, kind, entry)));
    }

    @AfterEach
    void tearDown() {
        PanelRenderer.sink(previousSink);
        PanelPrompts.install(null);
        PanelRuntime.releaseAll();
        Configs.releaseAll();
        FakeServer.reset();
    }

    // ------------------------------------------------------------------
    // 3.7 — no EffectConfig-specific code exists
    // ------------------------------------------------------------------

    @Test
    @DisplayName("no class in panel references EffectConfig or any of its nested records")
    void panelNeverNamesEffectConfig() {
        List<String> offenders = new ArrayList<>();

        for (Path classFile : productionClasses()) {
            for (String banned : referencesIn(classFile)) {
                offenders.add(classFile.getFileName() + " references " + banned);
            }
        }

        assertEquals(List.of(), offenders,
                "the effects editor must be the settings panel pointed at a record, with zero "
                        + "code that knows what an effect is — one branch here and the next "
                        + "config record needs its own screen too");
    }

    @Test
    @DisplayName("the sweep actually reads the production classes")
    void sweepIsNotVacuous() {
        List<Path> swept = productionClasses();
        List<String> names = swept.stream().map(path -> path.getFileName().toString()).toList();

        assertTrue(names.contains("SettingsEngine.class"),
                "the sweep must reach the engine that would do the special-casing, found: " + names);
        assertTrue(names.contains("ControlMapper.class"),
                "and the mapper, which is where a per-record switch would live, found: " + names);
        assertTrue(names.contains("RecordRebuilder.class"),
                "and the rebuild, found: " + names);
        assertTrue(swept.size() >= 12,
                "the module has more than a dozen classes; a sweep that found fewer read one "
                        + "classpath root instead of all of them, found: " + swept.size());
    }

    @Test
    @DisplayName("the detector recognises a reference to EffectConfig when it sees one")
    void detectorRecognisesTheBannedReference() {
        // This very test class names EffectConfig, so its own compiled form is
        // a known-positive fixture. Without this, "no offender found" could
        // mean the constant-pool reader never matches anything at all.
        Path self = compiledForm(EffectConfigGenericPathTest.class);
        assertFalse(referencesIn(self).isEmpty(),
                "a class that does reference EffectConfig must be detected, otherwise the sweep "
                        + "above proves nothing");

        Path mapper = compiledForm(net.exylia.lib.panel.internal.ControlMapper.class);
        assertEquals(List.of(), referencesIn(mapper),
                "and one that does not must come back clean, so the detector is not simply "
                        + "answering yes to everything");
    }

    // ------------------------------------------------------------------
    // 3.8 — and it works, through the generic path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("EffectConfig generates a control per component and a sub-panel per nested record")
    void effectConfigGeneratesItsControls() {
        SettingsEngine.forTests(plugin, viewer, effectFile(), null).draw();

        List<SettingsEngine.Control> controls = controls();
        assertEquals(List.of("title", "actionBar", "bossBar", "sound", "particle", "firework"),
                controls.stream().map(control -> control.field().name()).toList(),
                "one control per component, in the record's own order");
        assertTrue(controls.stream().allMatch(control -> control.kind() == ControlKind.SUB_PANEL),
                "every one of EffectConfig's components is a nested record, so every one is a "
                        + "sub-panel: " + controls.stream().map(SettingsEngine.Control::kind).toList());
    }

    @Test
    @DisplayName("a nested record's own components map to their own controls")
    void nestedRecordsMapToTheirOwnControls() {
        SettingsEngine<EffectConfig> engine = SettingsEngine.forTests(plugin, viewer, effectFile(), null);
        engine.draw();
        int title = slotOf("title");
        drawn.clear();

        engine.activate(title);

        assertEquals(
                List.of(ControlKind.TEXT, ControlKind.TEXT, ControlKind.DECIMAL,
                        ControlKind.DECIMAL, ControlKind.DECIMAL, ControlKind.DECIMAL,
                        ControlKind.TEXT),
                controls().stream().map(SettingsEngine.Control::kind).toList(),
                "Title's two strings, four doubles and a string, chosen from the declared types "
                        + "and nothing else");
    }

    @Test
    @DisplayName("the 45 @Comment lines reach the screen as lore")
    void commentsReachTheScreen() {
        SettingsEngine<EffectConfig> engine = SettingsEngine.forTests(plugin, viewer, effectFile(), null);
        engine.draw();

        SettingsEngine.Control title = control("title");
        assertEquals(List.of("Big text in the middle of the screen. Remove to show nothing."),
                title.field().comments());
        assertTrue(title.item().lore().contains(
                        "Big text in the middle of the screen. Remove to show nothing."),
                "documentation an owner only saw by opening the .yml must now be on the item: "
                        + title.item().lore());
    }

    @Test
    @DisplayName("editing a nested value and saving persists it through ConfigFile.update")
    void editingANestedValueSavesThroughUpdate() {
        var prompts = new SettingsControlMappingTest.ScriptedPrompts();
        prompts.textAnswer = "{primary}VICTORY";
        PanelPrompts.install(prompts);

        FakeConfigFile<EffectConfig> file = effectFile();
        SettingsEngine<EffectConfig> engine = SettingsEngine.forTests(plugin, viewer, file, null);
        engine.draw();
        int title = slotOf("title");
        engine.activate(title);
        drawn.clear();
        engine.draw();
        engine.activate(slotOf("text"));

        assertTrue(engine.save(), "the edit must be worth writing");
        FakeServer.tick(2);

        assertEquals(1, file.updates(),
                "exactly one write, and through update — never a FileConfiguration and never YAML");
        assertEquals("{primary}VICTORY", file.get().title().text(),
                "the nested value must have reached the record the config module received");
        assertEquals(3.0, file.get().title().stay(),
                "and the nested record's untouched components must be unchanged");
        assertEquals(new EffectConfig().sound(), file.get().sound(),
                "as must the sibling records the player never opened");
    }

    @Test
    @DisplayName("opening EffectConfig and closing it writes nothing")
    void openingAndClosingWritesNothing() {
        FakeConfigFile<EffectConfig> file = effectFile();
        SettingsEngine<EffectConfig> engine = SettingsEngine.forTests(plugin, viewer, file, null);
        engine.draw();

        assertFalse(engine.save(), "an empty diff must not be written");
        FakeServer.tick(2);
        assertEquals(0, file.updates(),
                "opening the effects editor to look at it must not rewrite the owner's file");
    }

    // ------------------------------------------------------------------

    private FakeConfigFile<EffectConfig> effectFile() {
        return FakeConfigFile.of(plugin, "effects", new EffectConfig());
    }

    private List<SettingsEngine.Control> controls() {
        return drawn.stream()
                .filter(entry -> entry.entry() instanceof SettingsEngine.Control)
                .map(entry -> (SettingsEngine.Control) entry.entry())
                .toList();
    }

    private SettingsEngine.Control control(String component) {
        return controls().stream()
                .filter(control -> control.field().name().equals(component))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no control was drawn for " + component));
    }

    private int slotOf(String component) {
        return drawn.stream()
                .filter(entry -> entry.entry() instanceof SettingsEngine.Control control
                        && control.field().name().equals(component))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no control was drawn for " + component))
                .slot();
    }

    // ------------------------------------------------------------------
    // The sweep
    // ------------------------------------------------------------------

    /**
     * Which banned names a compiled class refers to.
     *
     * <p>Read as raw bytes rather than parsed: every class a method touches ends
     * up in the constant pool as its internal name, so searching the file is
     * both simpler and stricter than reading imports — a fully-qualified name
     * written inline has no import to find.
     */
    private static List<String> referencesIn(Path classFile) {
        String bytes;
        try {
            bytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        List<String> found = new ArrayList<>();
        for (String banned : BANNED) {
            if (bytes.contains(banned)) {
                found.add(banned);
            }
        }
        return found;
    }

    /** Every compiled class of the module, excluding this suite's own test classes. */
    private static List<Path> productionClasses() {
        List<Path> classes = new ArrayList<>();
        for (String packageName : SWEPT_PACKAGES) {
            for (Path directory : directoriesOf(packageName)) {
                try (Stream<Path> entries = Files.list(directory)) {
                    for (Path entry : entries.sorted().toList()) {
                        String file = entry.getFileName().toString();
                        // Test classes share these package names on a second
                        // classpath root — sweeping them is how this proves it
                        // reads every root — but the rule is about the module.
                        if (file.endsWith(".class") && !file.contains("Test")
                                && !file.startsWith("FakeConfigFile")) {
                            classes.add(entry);
                        }
                    }
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        }
        assertNotEquals(0, classes.size(), "the sweep found no classes at all");
        return classes;
    }

    private static Path compiledForm(Class<?> type) {
        String resource = type.getName().replace('.', '/') + ".class";
        var url = EffectConfigGenericPathTest.class.getClassLoader().getResource(resource);
        if (url == null) {
            throw new AssertionError("not on the classpath: " + resource);
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException exception) {
            throw new AssertionError(exception);
        }
    }

    private static List<Path> directoriesOf(String packageName) {
        try {
            List<Path> roots = new ArrayList<>();
            var found = EffectConfigGenericPathTest.class.getClassLoader()
                    .getResources(packageName.replace('.', '/'));
            while (found.hasMoreElements()) {
                roots.add(Path.of(found.nextElement().toURI()));
            }
            if (roots.isEmpty()) {
                throw new AssertionError("package not on the classpath: " + packageName);
            }
            return roots;
        } catch (IOException | URISyntaxException exception) {
            throw new AssertionError(exception);
        }
    }
}
