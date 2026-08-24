package net.exylia.lib.panel;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Configs;
import net.exylia.lib.debug.DebugCapture;
import net.exylia.lib.panel.internal.ControlKind;
import net.exylia.lib.panel.internal.ControlMapper;
import net.exylia.lib.panel.internal.PanelPrompts;
import net.exylia.lib.panel.internal.PanelRenderer;
import net.exylia.lib.panel.internal.PanelRuntime;
import net.exylia.lib.panel.internal.SettingsEngine;
import net.exylia.lib.panel.internal.UnsupportedTypes;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A component nobody can edit must cost that component and nothing else.
 *
 * <p>Three separate promises, and losing any one of them is a different bug: the
 * panel still opens and still saves, the value survives untouched, and the
 * console says so <em>once per server</em> rather than once per drawn item —
 * which is the {@code ItemComponents} lesson, applied before it could be
 * re-learned here.
 */
class UnsupportedComponentTest {

    @TempDir
    Path folder;

    private Plugin plugin;
    private Player viewer;
    private PanelRenderer.DrawSink previousSink;
    private final List<Drawn> drawn = new ArrayList<>();
    private List<String> console;

    record Drawn(int slot, ControlKind kind, Object entry) {
    }

    /**
     * A type the library has no control for.
     *
     * <p>Not a contrived one: {@code server-id} is a {@code UUID} in a real
     * config record, and there is no sensible way to let somebody type one into
     * an anvil.
     */
    record Settings(
            @Comment("How many at once.")
            int amount,

            @Comment("What it is called.")
            String label,

            @Comment("This server's identity.")
            @Comment("Generated once; changing it splits the network.")
            UUID serverId) {

        Settings() {
            this(1, "hello", new UUID(4, 9));
        }
    }

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Configs.releaseAll();
        UnsupportedTypes.forgetReportedForTests();
        plugin = FakeServer.newPlugin("UnsupportedComponentPlugin", folder.toFile());
        FakePlayer fake = new FakePlayer("Steve");
        viewer = fake.player();
        FakeServer.online(viewer);
        previousSink = PanelRenderer.sink((slot, kind, entry) -> drawn.add(new Drawn(slot, kind, entry)));
        console = DebugCapture.start();
    }

    @AfterEach
    void tearDown() {
        DebugCapture.stop();
        PanelRenderer.sink(previousSink);
        PanelPrompts.install(null);
        PanelRuntime.releaseAll();
        UnsupportedTypes.forgetReportedForTests();
        Configs.releaseAll();
        FakeServer.reset();
    }

    private FakeConfigFile<Settings> file() {
        return FakeConfigFile.of(plugin, "unsupported", new Settings());
    }

    // ------------------------------------------------------------------
    // 3.5 — it opens, it saves, and the value survives
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a record with an unsupported component still draws every component")
    void unsupportedComponentStillDraws() {
        SettingsEngine.forTests(plugin, viewer, file(), null).draw();

        List<SettingsEngine.Control> controls = controls();
        assertEquals(3, controls.size(),
                "the unsupported component must be drawn, not omitted: a value nobody can see "
                        + "is a value nobody notices losing");
        assertEquals(List.of(ControlKind.INTEGER, ControlKind.TEXT, ControlKind.UNSUPPORTED),
                controls.stream().map(SettingsEngine.Control::kind).toList());
    }

    @Test
    @DisplayName("editing the int and saving carries the new int, the old String and the untouched value")
    void savingCarriesTheUnsupportedValueThrough() {
        var prompts = new SettingsControlMappingTest.ScriptedPrompts();
        prompts.textAnswer = "7";
        PanelPrompts.install(prompts);

        FakeConfigFile<Settings> file = file();
        UUID original = file.get().serverId();
        SettingsEngine<Settings> engine = SettingsEngine.forTests(plugin, viewer, file, null);
        engine.draw();
        engine.activate(slotOf("amount"));

        assertTrue(engine.save(), "a panel with an unsupported component must still be savable");
        FakeServer.tick(2);

        assertEquals(1, file.updates(), "exactly one write, through update and nothing else");
        assertEquals(7, file.get().amount(), "the edited component must carry the new value");
        assertEquals("hello", file.get().label(), "the untouched one must be unchanged");
        assertSame(original, file.get().serverId(),
                "the unsupported component must be passed through by identity — not rebuilt, "
                        + "not defaulted, not dropped");
    }

    @Test
    @DisplayName("the unsupported component is excluded from the edit path, never from the rebuild")
    void unsupportedIsExcludedFromEditingNotFromSaving() {
        FakeConfigFile<Settings> file = file();
        SettingsEngine<Settings> engine = SettingsEngine.forTests(plugin, viewer, file, null);
        engine.draw();

        assertFalse(ControlMapper.isEditable(ControlKind.UNSUPPORTED),
                "an unsupported control must report itself as not editable");
        assertNotEquals(null, engine.value("serverId"),
                "and its value must still be in the working copy, so a rebuild can carry it");
    }

    // ------------------------------------------------------------------
    // 3.6 — clicking it does nothing at all
    // ------------------------------------------------------------------

    @Test
    @DisplayName("clicking the unsupported control changes nothing and opens no input request")
    void clickingTheUnsupportedControlDoesNothing() {
        var prompts = new SettingsControlMappingTest.ScriptedPrompts();
        prompts.textAnswer = "hijacked";
        PanelPrompts.install(prompts);

        FakeConfigFile<Settings> file = file();
        SettingsEngine<Settings> engine = SettingsEngine.forTests(plugin, viewer, file, null);
        engine.draw();
        Object before = engine.value("serverId");

        boolean handled = engine.activate(slotOf("serverId"));

        assertFalse(handled, "a read-only control must say it did nothing");
        assertSame(before, engine.value("serverId"), "and the working copy must be untouched");
        assertEquals(0, prompts.texts.size() + prompts.searches.size(),
                "no question may be asked about a value the panel could not apply an answer to");
        assertEquals(0, engine.undoDepth(), "and nothing may be pushed onto undo");
    }

    @Test
    @DisplayName("its @Comment lore still shows, and it says why it is read-only")
    void unsupportedControlKeepsItsCommentLore() {
        SettingsEngine.forTests(plugin, viewer, file(), null).draw();

        SettingsEngine.Control serverId = control("serverId");
        assertTrue(serverId.item().lore().containsAll(List.of(
                        "This server's identity.",
                        "Generated once; changing it splits the network.")),
                "documentation is the only thing a read-only row can still give: " + serverId.item().lore());
        assertTrue(serverId.item().lore().stream().anyMatch(line -> line.contains("Not editable")),
                "and the row must say why it cannot be clicked: " + serverId.item().lore());
    }

    // ------------------------------------------------------------------
    // 3.6 — once per server
    // ------------------------------------------------------------------

    @Test
    @DisplayName("three opens by two players report exactly once, naming the component and the type")
    void reportFiresOnceAcrossOpensAndPlayers() {
        Player second = new FakePlayer("Alex").player();
        FakeServer.online(viewer, second);

        SettingsEngine.forTests(plugin, viewer, file(), null).draw();
        SettingsEngine.forTests(plugin, viewer, file(), null).draw();
        SettingsEngine.forTests(plugin, second, file(), null).draw();

        List<String> about = console.stream()
                .filter(line -> line.contains(UUID.class.getName()))
                .toList();
        assertEquals(1, about.size(),
                "opening a panel renders every component, so reporting per draw puts one line "
                        + "per row in the console for a single screen. Captured: " + console);
        assertTrue(about.get(0).contains("serverId"),
                "the line must name the component an owner would go and look at: " + about.get(0));
        assertTrue(about.get(0).contains(UUID.class.getName()),
                "and the type there is no control for: " + about.get(0));
    }

    @Test
    @DisplayName("the once-per-server memory is what suppresses the second line, not the drawing")
    void secondLineIsSuppressedByTheMemoryNotByNotDrawing() {
        SettingsEngine.forTests(plugin, viewer, file(), null).draw();
        assertEquals(1, console.stream().filter(line -> line.contains(UUID.class.getName())).count());
        assertTrue(UnsupportedTypes.wasReported(UUID.class),
                "the report must be remembered, which is what makes the next one silent");

        // Forgetting is the only difference between this open and the last one.
        // Without it the assertion above could pass because nothing was drawn
        // the second time, which is a different — and much worse — behaviour.
        UnsupportedTypes.forgetReportedForTests();
        drawn.clear();
        SettingsEngine.forTests(plugin, viewer, file(), null).draw();

        assertEquals(3, controls().size(),
                "the panel must still draw every component after forgetting");
        assertEquals(2, console.stream().filter(line -> line.contains(UUID.class.getName())).count(),
                "and it must report again, which proves the silence was the memory and not a "
                        + "component that stopped being drawn");
    }

    @Test
    @DisplayName("two different unsupported types are two different reports")
    void twoUnsupportedTypesAreReportedSeparately() {
        SettingsEngine.forTests(plugin, viewer, file(), null).draw();
        SettingsEngine.forTests(plugin, viewer,
                FakeConfigFile.of(plugin, "other", new Other()), null).draw();

        assertEquals(1, console.stream().filter(line -> line.contains(UUID.class.getName())).count());
        assertEquals(1, console.stream().filter(line -> line.contains(BigDecimal.class.getName())).count(),
                "a second unsupported type is a second thing an owner may want to know about: "
                        + console);
    }

    /** A second unsupported type, so "once per type" is distinguishable from "once ever". */
    record Other(int count, BigDecimal balance) {
        Other() {
            this(1, new BigDecimal("1.50"));
        }
    }

    // ------------------------------------------------------------------

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
}
