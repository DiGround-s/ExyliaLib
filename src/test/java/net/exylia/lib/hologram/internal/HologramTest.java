package net.exylia.lib.hologram.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.hologram.Hologram;
import net.exylia.lib.hologram.HologramConfig;
import net.exylia.lib.hologram.Holograms;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Colors;
import net.exylia.lib.text.Palette;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a hologram actually writes to a client.
 *
 * <p>A hologram is packets, so every promise the module makes is a statement
 * about which packets are sent and, more importantly, which are not: a player
 * out of range gets nothing, a line whose value did not change is not re-sent,
 * and a hologram with no placeholders never sends anything twice.
 */
class HologramTest {

    private static final String PLUGIN = "HologramTest";

    private final AtomicLong clock = new AtomicLong(10_000L);
    private final AtomicInteger nextId = new AtomicInteger(1000);
    private final AtomicReference<String> capturer = new AtomicReference<>("nobody");

    private FakeSink sink;
    private Plugin plugin;
    private World world;
    private FakePlayer near;
    private FakePlayer far;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Colors.apply(new Palette());

        plugin = FakeServer.newPlugin(PLUGIN, null);
        sink = new FakeSink();
        HologramRuntime.testHooks(Tasks.of(plugin), plugin.getLogger(),
                clock::get, nextId::getAndIncrement, sink);

        world = FakeServer.newWorld("world");
        near = new FakePlayer("Near").at(new Location(world, 0, 64, 0));
        far = new FakePlayer("Far").at(new Location(world, 500, 64, 500));
        FakeServer.online(near.player(), far.player());

        Placeholders.group(plugin, "koth")
                .add("capturer", request -> capturer.get())
                .register();
    }

    @AfterEach
    void tearDown() {
        HologramRuntime.removeEverything();
        Placeholders.unregisterAll(PLUGIN);
        FakeServer.reset();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static HologramConfig text(List<String> lines) {
        return new HologramConfig(true, HologramConfig.Kind.TEXT, lines, "", "",
                false, 32.0, 0, 0, 0,
                new HologramConfig.Properties(), new HologramConfig.Refresh());
    }

    private Location spawn() {
        return new Location(world, 0, 64, 0);
    }

    /** Runs the runtime driver once. The delay is 5 ticks, so the first
     * run happens at tick 6. */
    private static void drive() {
        FakeServer.tick(6);
    }

    private void advanceSeconds(double seconds) {
        clock.addAndGet((long) (seconds * 1000));
    }

    // ------------------------------------------------------------------
    // Visibility
    // ------------------------------------------------------------------

    @Test
    @DisplayName("only players inside the view distance are sent the hologram")
    void onlyNearbyPlayersSeeIt() {
        Holograms.show(plugin, "koth", spawn(), text(List.of("Line one", "Line two")));
        drive();

        assertEquals(2, sink.count("spawn"), "one spawn per line, for the near player only");
        assertTrue(sink.calls("spawn").stream().allMatch(call -> call.contains(":Near:")));
    }

    @Test
    @DisplayName("walking into range sends it, walking out takes it back")
    void viewersFollowTheViewDistance() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("Line")));
        drive();
        assertEquals(1, hologram.viewerCount());
        sink.clear();

        far.at(new Location(world, 5, 64, 5));
        drive();
        assertEquals(2, hologram.viewerCount());
        assertEquals(1, sink.count("spawn"));
        sink.clear();

        far.at(new Location(world, 500, 64, 500));
        drive();
        assertEquals(1, hologram.viewerCount());
        assertEquals(List.of("destroy:Far:1"), sink.calls("destroy"));
    }

    @Test
    @DisplayName("a player who stays in range is not sent it again every tick")
    void stayingInRangeSendsNothing() {
        Holograms.show(plugin, "koth", spawn(), text(List.of("Static line")));
        drive();
        sink.clear();

        advanceSeconds(10);
        drive();
        drive();

        assertEquals(List.of(), sink.calls());
    }

    @Test
    @DisplayName("a visibility filter hides it from players who fail it")
    void visibilityFilterIsApplied() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("Secret")));
        drive();
        assertEquals(1, hologram.viewerCount());
        sink.clear();

        hologram.visibleIf(player -> false);
        drive();

        assertEquals(0, hologram.viewerCount());
        assertEquals(1, sink.count("destroy"));
    }

    @Test
    @DisplayName("a player in another world never sees it")
    void otherWorldsDoNotSeeIt() {
        World nether = FakeServer.newWorld("nether");
        near.at(new Location(nether, 0, 64, 0));

        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("Line")));
        drive();

        assertEquals(0, hologram.viewerCount());
    }

    // ------------------------------------------------------------------
    // Refreshing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("only the line whose value changed is re-sent")
    void onlyChangedLinesAreSent() {
        Holograms.show(plugin, "koth", spawn(),
                text(List.of("Capturing: %koth_capturer%", "Static line")));
        drive();
        sink.clear();

        capturer.set("Steve");
        advanceSeconds(1.1);
        drive();

        assertEquals(List.of("text:Near:1000:Capturing: Steve"), sink.calls("text"));
    }

    @Test
    @DisplayName("a 1-tick refresh redraws every tick, not only when visibility is checked")
    void oneTickRefreshRunsEveryTick() {
        Holograms.show(plugin, "koth", spawn(), new HologramConfig(true, HologramConfig.Kind.TEXT,
                List.of("Capturing: %koth_capturer%"), "", "", false, 32.0, 0, 0, 0,
                new HologramConfig.Properties(), new HologramConfig.Refresh(1, true)));
        drive();
        sink.clear();

        for (int tick = 0; tick < 5; tick++) {
            capturer.set("Steve" + tick);
            advanceSeconds(0.05);
            FakeServer.tick(1);
        }

        assertEquals(5, sink.count("text"));
    }

    @Test
    @DisplayName("a flowing line is re-sent at most ten times a second")
    void flowIsCappedAtTenFramesASecond() {
        Holograms.show(plugin, "banner", spawn(), text(List.of("<flow><gradient:red:blue>Season II</gradient>")));
        drive();
        sink.clear();

        for (int tick = 0; tick < 20; tick++) {
            advanceSeconds(0.05);
            FakeServer.tick(1);
        }

        assertEquals(10, sink.count("text"));
    }

    @Test
    @DisplayName("a hologram with no placeholders never refreshes")
    void staticHologramsNeverRefresh() {
        Holograms.show(plugin, "koth", spawn(), text(List.of("Spawn", "Welcome")));
        drive();
        sink.clear();

        advanceSeconds(60);
        drive();

        assertEquals(List.of(), sink.calls(), "a static hologram should cost nothing after spawn");
    }

    @Test
    @DisplayName("a refresh with nothing changed sends nothing")
    void unchangedRefreshSendsNothing() {
        Holograms.show(plugin, "koth", spawn(), text(List.of("Capturing: %koth_capturer%")));
        drive();
        sink.clear();

        advanceSeconds(5);
        drive();

        assertEquals(List.of(), sink.calls());
    }

    @Test
    @DisplayName("replacing the lines of a static hologram redraws it")
    void staticLinesAreRedrawnWhenReplaced() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(),
                text(List.of("{success}&l\u2726 READY")));
        drive();
        sink.clear();

        hologram.lines(List.of("{warning}&l\u231a {highlight}8s"));
        advanceSeconds(1.1);
        drive();

        assertEquals(List.of("text:Near:1000:\u231a 8s"), sink.calls("text"));
    }

    @Test
    @DisplayName("new data reaches the placeholders")
    void updateDataIsPickedUp() {
        Placeholders.register(plugin, "koth_arena",
                request -> request.get("arena", String.class));

        Hologram hologram = Holograms.show(plugin, "koth", spawn(),
                text(List.of("Arena: %koth_arena%")), Map.of("arena", "alpha"));
        drive();
        sink.clear();

        hologram.updateData(Map.of("arena", "beta"));
        advanceSeconds(1.1);
        drive();

        assertEquals(List.of("text:Near:1000:Arena: beta"), sink.calls("text"));
    }

    // ------------------------------------------------------------------
    // Moving and layout
    // ------------------------------------------------------------------

    @Test
    @DisplayName("lines stack downwards, so the first line is on top")
    void linesStackDownwards() {
        Holograms.show(plugin, "koth", spawn(), text(List.of("top", "middle", "bottom")));
        drive();

        // Spacing is 0.25 by default: the last line sits at the anchor.
        assertEquals(List.of("spawn:Near:1000:64.5", "spawn:Near:1001:64.25",
                "spawn:Near:1002:64.0"), sink.calls("spawn"));
    }

    @Test
    @DisplayName("a sized line takes room in proportion, pushing the lines above it up")
    void sizedLinesMakeRoom() {
        Holograms.show(plugin, "koth", spawn(), text(List.of("top", "<size:2>BIG", "bottom")));
        drive();

        // bottom 0.25, BIG 0.5, so top stands 0.75 above the anchor.
        assertEquals(List.of("spawn:Near:1000:64.75", "spawn:Near:1001:64.25",
                "spawn:Near:1002:64.0"), sink.calls("spawn"));
        assertTrue(sink.calls("text").isEmpty());
    }

    @Test
    @DisplayName("an item line stands in the stack and is never sent text")
    void itemLinesAreItems() {
        Holograms.show(plugin, "koth", spawn(), text(List.of("<item:DIAMOND>", "%koth_capturer%")));
        drive();
        advanceSeconds(2);
        capturer.set("Steve");
        drive();

        // The item is half a block tall and centred, above the 0.25 text line.
        assertEquals(List.of("spawn:Near:1000:64.5", "spawn:Near:1001:64.0"), sink.calls("spawn"));
        assertTrue(sink.calls("text").contains("text:Near:1001:Steve"));
        assertTrue(sink.calls("text").stream().noneMatch(call -> call.startsWith("text:Near:1000:")));
    }

    @Test
    @DisplayName("line tags are stripped, unknown or broken ones stay in the text")
    void lineTagsParse() {
        LineStyle.Parsed parsed = LineStyle.parse("<size:1.5><opacity:120><shadow:false><bold>Hi");
        assertEquals(LineStyle.of(net.exylia.lib.hologram.HologramLine.builder()
                .size(1.5).opacity(120).shadow(false).build()), parsed.style());
        assertEquals(1.5, parsed.style().size());
        assertEquals(120, parsed.style().opacity());
        assertEquals(Boolean.FALSE, parsed.style().shadow());
        assertEquals("<bold>Hi", parsed.body());
        assertEquals("<size:big>Hi", LineStyle.parse("<size:big>Hi").body());
        assertEquals("<gradient:#fff:#000>Hi", LineStyle.parse("<gradient:#fff:#000>Hi").body());
    }

    @Test
    @DisplayName("motion, model and visibility tags parse, and a quoted value may hold '>'")
    void newTagsParse() {
        LineStyle.Parsed parsed = LineStyle.parse(
                "<block:beacon><spin><bob:0.2><glow:#8a51c4><if:\"%koth_capturer% > 3\"><range:8>Hi");
        LineStyle style = parsed.style();
        assertEquals("Hi", parsed.body());
        assertEquals("BEACON", style.model());
        assertTrue(style.block());
        assertEquals(90, style.spin());
        assertEquals(0.2, style.bob());
        assertEquals("#8a51c4", style.glow());
        assertEquals("%koth_capturer% > 3", style.condition());
        assertEquals(8, style.range());
        assertTrue(style.animated());
        assertTrue(style.conditional());
        assertEquals(24, LineStyle.parse("<typewriter>x").style().typewriter());
        assertEquals("<block>x", LineStyle.parse("<block>x").body());
    }

    @Test
    @DisplayName("a block turns around its own centre")
    void blocksSpinAroundTheirCentre() {
        LineStyle style = LineStyle.parse("<block:STONE><spin:90>").style();
        DisplayState block = new DisplayState(1, HologramConfig.Kind.BLOCK,
                style.apply(new HologramConfig.Properties()), org.bukkit.Material.STONE, style);

        DisplayState.Transform still = block.transformAt(0);
        assertEquals(-0.25, still.tx(), 1e-6);
        assertEquals(-0.25, still.tz(), 1e-6);
        // Half a turn later the corner offset has turned with it.
        DisplayState.Transform half = block.transformAt(2000);
        assertEquals(0.25, half.tx(), 1e-6);
        assertEquals(0.25, half.tz(), 1e-6);
        assertEquals(1.0, half.qy(), 1e-6);
    }

    @Test
    @DisplayName("only animated lines are sent transformations, once a keyframe")
    void animatedLinesGlide() {
        Holograms.show(plugin, "koth", spawn(), text(List.of("<spin>Spinning", "Still")));
        drive();
        long first = sink.count("transform");
        drive();

        assertTrue(first >= 1);
        assertTrue(sink.calls("transform").stream().allMatch(call -> call.startsWith("transform:Near:1000:")),
                "only the animated line moves: " + sink.calls("transform"));
        // The clock has not moved, so no new keyframe is due.
        assertEquals(first, sink.count("transform"));
        advanceSeconds(0.5);
        drive();
        assertEquals(first + 1, sink.count("transform"));
    }

    @Test
    @DisplayName("a typewriter line spawns empty and is typed in, then left alone")
    void typewriterTypesIn() {
        Holograms.show(plugin, "koth", spawn(), text(List.of("<typewriter:10>Hello")));
        drive();
        assertEquals(List.of(""), sink.spawnedText);

        advanceSeconds(0.3);
        drive();
        advanceSeconds(1);
        drive();
        sink.clear();
        advanceSeconds(1);
        drive();

        List<String> text = sink.calls("text");
        assertTrue(text.isEmpty(), "a finished line is not re-sent: " + text);
    }

    @Test
    @DisplayName("typing sends the growing text")
    void typewriterGrows() {
        Holograms.show(plugin, "koth", spawn(), text(List.of("<typewriter:10>Hello")));
        drive();
        advanceSeconds(0.3);
        drive();
        advanceSeconds(1);
        drive();

        List<String> text = sink.calls("text");
        assertEquals("text:Near:1000:Hel", text.get(0));
        assertEquals("text:Near:1000:Hello", text.get(text.size() - 1));
    }

    @Test
    @DisplayName("a ranged line appears as the viewer walks up, and the stack makes room")
    void rangedLinesAppearUpClose() {
        near.at(new Location(world, 10, 64, 0));
        Holograms.show(plugin, "koth", spawn(), text(List.of("Title", "<range:5>Details")));
        drive();
        assertEquals(List.of("spawn:Near:1000:64.0"), sink.calls("spawn"));

        sink.clear();
        near.at(new Location(world, 2, 64, 0));
        drive();
        assertEquals(List.of("spawn:Near:1001:64.0"), sink.calls("spawn"));
        assertEquals(List.of("teleport:Near:1000:64.25"), sink.calls("teleport"));

        sink.clear();
        near.at(new Location(world, 10, 64, 0));
        drive();
        assertEquals(List.of("destroy:Near:1"), sink.calls("destroy"));
        assertEquals(List.of("teleport:Near:1000:64.0"), sink.calls("teleport"));
    }

    @Test
    @DisplayName("permission lines are shown only to who has, or lacks, the permission")
    void permissionLines() {
        far.at(new Location(world, 1, 64, 0));
        near.grant("koth.vip");
        Holograms.show(plugin, "koth", spawn(), text(List.of("<perm:koth.vip>VIP", "<perm:!koth.vip>Buy VIP")));
        drive();

        assertEquals(List.of("spawn:Near:1000:64.0", "spawn:Far:1001:64.0"), sink.calls("spawn"));
    }

    @Test
    @DisplayName("a clickable hologram spawns its box and runs the handler, once per cooldown")
    void clicksRunTheHandler() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("A", "<size:2>B")));
        AtomicInteger clicks = new AtomicInteger();
        hologram.onClick(player -> clicks.incrementAndGet());
        drive();
        assertEquals(List.of("hitbox:Near:1002:0.75"), sink.calls("hitbox"));

        HologramRuntime.clicked(near.player(), 1002);
        HologramRuntime.clicked(near.player(), 1002);
        FakeServer.tick(1);
        assertEquals(1, clicks.get(), "a double-fired press counts once");
        assertTrue(HologramRuntime.isHitbox(1002));

        hologram.remove();
        assertFalse(HologramRuntime.isHitbox(1002));
    }

    @Test
    @DisplayName("click lines and the handler know left from right and sneaking")
    void clickKindsRunTheirLines() {
        HologramConfig config = new HologramConfig(true, HologramConfig.Kind.TEXT, List.of("A"),
                "", "", false, 32.0, 0, 0, 0, new HologramConfig.Properties(),
                new HologramConfig.Refresh(), new HologramConfig.Click(
                        List.of("left: hit", "right: use", "shift: edit", "always", "[NOPE] skipped"),
                        1.5, 500));
        Hologram hologram = Holograms.show(plugin, "koth", spawn(), config);
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        hologram.onClick((player, click) -> seen.add(click.name()));
        drive();
        int box = Integer.parseInt(sink.calls("hitbox").get(0).split(":")[2]);

        HologramRuntime.clicked(near.player(), box, net.exylia.lib.interaction.Click.LEFT);
        advanceSeconds(1);
        HologramRuntime.clicked(near.player(), box, net.exylia.lib.interaction.Click.SHIFT_RIGHT);
        HologramRuntime.clicked(near.player(), box, net.exylia.lib.interaction.Click.RIGHT);
        FakeServer.tick(1);

        assertEquals(List.of("hit", "always", "edit", "always"), near.commands(),
                "the second right click is inside the cooldown");
        assertEquals(List.of("LEFT", "SHIFT_RIGHT"), seen);
    }

    @Test
    @DisplayName("a shine sweeps a band of light over the line, then rests")
    void shineSweepsAndRests() {
        net.kyori.adventure.text.Component line = net.kyori.adventure.text.Component.text("ABCDEFGH",
                net.kyori.adventure.text.format.NamedTextColor.DARK_PURPLE);
        net.kyori.adventure.text.format.TextColor light =
                net.kyori.adventure.text.format.TextColor.color(0xFFFFFF);

        // 8 characters and a band 3 either side: the sweep lasts 1.4 s.
        List<net.kyori.adventure.text.format.TextColor> lit = colours(Shimmer.shine(line, light, 0.6));
        assertTrue(lit.contains(light), lit.toString());
        assertTrue(lit.contains(net.kyori.adventure.text.format.NamedTextColor.DARK_PURPLE),
                "the band is narrower than the line: " + lit);
        assertEquals(null, Shimmer.shine(line, light, 2.0));
        assertTrue(Shimmer.resting(line, 2.0));
        assertEquals("ABCDEFGH", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(Shimmer.shine(line, light, 0.6)));
    }

    private static List<net.kyori.adventure.text.format.TextColor> colours(
            net.kyori.adventure.text.Component component) {
        return component.children().stream().map(net.kyori.adventure.text.Component::color).toList();
    }

    @Test
    @DisplayName("flow moves colours along the line, even a line in one colour")
    void flowMoves() {
        net.kyori.adventure.text.Component line = net.kyori.adventure.text.Component.text("ABCDEF",
                net.kyori.adventure.text.format.NamedTextColor.DARK_PURPLE);
        assertFalse(colours(Shimmer.flow(line, 4, 0)).equals(colours(Shimmer.flow(line, 4, 0.25))));
    }

    @Test
    @DisplayName("riding an entity, lines are stacked by translation, offset included")
    void mountedLinesStackByTranslation() {
        HologramConfig config = new HologramConfig(true, HologramConfig.Kind.TEXT,
                List.of("Top", "Bottom"), "", "", false, 32.0, 0, 0.5, 0,
                new HologramConfig.Properties(), new HologramConfig.Refresh());
        Hologram hologram = Holograms.show(plugin, "koth", spawn(), config);
        hologram.attachTo(near.player());
        drive();

        assertEquals(List.of(0.75f, 0.5f), sink.spawnedLift);
        assertEquals(1, sink.count("mount"));
        assertTrue(sink.calls("spawn").stream().allMatch(call -> call.endsWith(":64.0")),
                "every display stands on the vehicle; only the translation stacks them");
    }

    @Test
    @DisplayName("riding, three lines with an offset stack by translation from the top down")
    void mountedTranslationStacksEveryLine() {
        HologramConfig config = new HologramConfig(true, HologramConfig.Kind.TEXT,
                List.of("A", "B", "C"), "", "", false, 32.0, 0, 1.0, 0,
                new HologramConfig.Properties(), new HologramConfig.Refresh());
        Holograms.show(plugin, "koth", spawn(), config).attachTo(near.player());
        drive();

        assertEquals(List.of(1.5f, 1.25f, 1.0f), sink.spawnedLift);
    }

    @Test
    @DisplayName("riding, who sees it follows the entity, not where it was placed")
    void mountedRangeFollowsTheEntity() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("Line")));
        hologram.attachTo(far.player());
        drive();

        assertEquals(1, hologram.viewerCount());
        assertTrue(sink.calls("spawn").stream().allMatch(call -> call.contains(":Far:")),
                "the player at the placed location is out of the entity's range");
        sink.clear();

        far.at(new Location(world, 5, 64, 5));
        drive();
        assertEquals(2, hologram.viewerCount(), "the entity walked over to the near player");

        hologram.attachTo(null);
        far.at(new Location(world, 500, 64, 500));
        drive();
        assertEquals(1, hologram.viewerCount(), "detached, it stays at the placed location");
        assertTrue(hologram.isViewing(near.player()));
    }

    @Test
    @DisplayName("riding, a viewer sent the entity again is mounted again")
    void mountedResentWhenTheVehicleIsShown() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("Line")));
        hologram.attachTo(near.player());
        drive();
        sink.clear();

        HologramRuntime.vehicleShown(near.player(), 0);
        assertEquals(List.of("mount:Near:0:1"), sink.calls("mount"));

        HologramRuntime.vehicleShown(near.player(), 42);
        HologramRuntime.vehicleShown(far.player(), 0);
        assertEquals(1, sink.count("mount"), "another entity, or somebody not shown it, gets nothing");
    }

    @Test
    @DisplayName("riding, a viewer who respawns is sent it whole again")
    void mountedResentAfterRespawn() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("Line")));
        hologram.attachTo(near.player());
        drive();
        sink.clear();

        HologramRuntime.respawned(near.player());
        drive();
        assertEquals(1, sink.count("spawn"));
        assertEquals(1, sink.count("mount"));
        assertEquals(0, sink.count("destroy"), "their client already dropped it");
    }

    @Test
    @DisplayName("looking at a clickable hologram grows it, looking away shrinks it back")
    void hoverGrows() {
        Hologram hologram = Holograms.show(plugin, "koth", new Location(world, 0, 65.5, 0),
                text(List.of("A", "B")));
        hologram.onClick(player -> { });
        near.at(new Location(world, 0, 64, -3, 0, 0));
        drive();
        assertEquals(0, sink.count("transform"), "the first pass only spawns it");

        drive();
        assertEquals(2, sink.count("transform"));
        assertEquals(1.08f, sink.lastScale.get(0), 1e-4);

        sink.clear();
        near.at(new Location(world, 0, 64, -3, 180, 0));
        drive();
        assertEquals(2, sink.count("transform"));
        assertEquals(1.0f, sink.lastScale.get(0), 1e-4);
    }

    @Test
    @DisplayName("every line faces the way the hologram's location does")
    void linesFaceTheLocation() {
        Location facing = new Location(world, 0, 64, 0, 135f, 0f);
        Holograms.show(plugin, "koth", facing, text(List.of("A", "B")));
        drive();

        assertEquals(List.of(135f, 135f), sink.spawnedYaw);
    }

    @Test
    @DisplayName("moving teleports the displays instead of respawning them")
    void movingTeleports() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("Line")));
        drive();
        sink.clear();

        hologram.moveTo(new Location(world, 0, 70, 0));

        assertEquals(List.of("teleport:Near:1000:70.0"), sink.calls("teleport"));
        assertEquals(0, sink.count("spawn"), "moving must not respawn the hologram");
        assertEquals(0, sink.count("destroy"));
    }

    @Test
    @DisplayName("the configured offset is applied to where it stands")
    void offsetIsApplied() {
        HologramConfig config = new HologramConfig(true, HologramConfig.Kind.TEXT,
                List.of("Line"), "", "", false, 32.0, 0, 2.0, 0,
                new HologramConfig.Properties(), new HologramConfig.Refresh());

        Hologram hologram = Holograms.show(plugin, "koth", spawn(), config);

        assertEquals(66.0, hologram.location().getY(), 0.001);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("removing takes it off every screen")
    void removingDespawnsForEveryone() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("a", "b")));
        drive();
        sink.clear();

        hologram.remove();

        assertTrue(hologram.removed());
        assertEquals(List.of("destroy:Near:2"), sink.calls("destroy"));
        assertEquals(0, HologramRuntime.count());
    }

    @Test
    @DisplayName("reusing an id replaces the hologram that had it")
    void reusingAnIdReplaces() {
        Hologram first = Holograms.show(plugin, "koth", spawn(), text(List.of("old")));
        drive();

        Holograms.show(plugin, "koth", spawn(), text(List.of("new")));
        drive();

        assertTrue(first.removed(), "the replaced hologram should be gone");
        assertEquals(1, HologramRuntime.count());
    }

    @Test
    @DisplayName("disabling a plugin removes its holograms")
    void disablingAPluginRemovesThem() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("Line")));
        drive();

        assertEquals(1, HologramRuntime.removeAll(PLUGIN));

        assertTrue(hologram.removed());
        assertEquals(0, HologramRuntime.count());
    }

    @Test
    @DisplayName("the driver only runs while there are holograms")
    void driverStopsWhenIdle() {
        assertEquals(0, FakeServer.liveRepeatingTasks());

        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("Line")));
        drive();
        assertEquals(1, FakeServer.liveRepeatingTasks());

        hologram.remove();
        assertEquals(0, FakeServer.liveRepeatingTasks(),
                "the driver should stop once the last hologram is gone");
    }

    @Test
    @DisplayName("a disabled config shows nothing and sends no packets")
    void disabledConfigShowsNothing() {
        HologramConfig disabled = new HologramConfig(false, HologramConfig.Kind.TEXT,
                List.of("Line"), "", "", false, 32.0, 0, 0, 0,
                new HologramConfig.Properties(), new HologramConfig.Refresh());

        Hologram hologram = Holograms.show(plugin, "koth", spawn(), disabled);
        drive();

        assertTrue(hologram.removed());
        assertEquals(List.of(), sink.calls());
        assertEquals(0, HologramRuntime.count());
    }

    @Test
    @DisplayName("a player who leaves is forgotten without sending them packets")
    void leavingPlayersAreForgotten() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("Line")));
        drive();
        sink.clear();

        near.disconnect();
        HologramRuntime.forget(near.player());

        assertFalse(hologram.isViewing(near.player()));
        assertEquals(List.of(), sink.calls(), "nothing should be sent to a client that is gone");
    }

    // ------------------------------------------------------------------
    // Pages
    // ------------------------------------------------------------------

    private static HologramConfig paged(long interval, List<String> firstClick, HologramConfig.Page... pages) {
        return new HologramConfig(true, HologramConfig.Kind.TEXT, List.of("One A", "One B"), "", "",
                false, 32.0, 0, 0, 0, new HologramConfig.Properties(), new HologramConfig.Refresh(),
                new HologramConfig.Click(firstClick, 1.5, 0), List.of(pages), interval);
    }

    @Test
    @DisplayName("a viewer sees only their page, and turning it swaps the lines")
    void pagesShowOneAtATime() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(),
                paged(0, List.of(), new HologramConfig.Page(List.of("Two"))));
        drive();
        assertEquals(2, hologram.pageCount());
        assertEquals(List.of("One A", "One B"), sink.spawnedText);
        sink.clear();

        hologram.nextPage(near.player());
        assertEquals(1, hologram.page(near.player()));
        FakeServer.tick(1);

        assertEquals(List.of("destroy:Near:2"), sink.calls("destroy"));
        assertEquals(List.of("Two"), sink.spawnedText);
        sink.clear();

        hologram.nextPage(near.player());
        FakeServer.tick(1);
        assertEquals(0, hologram.page(near.player()), "the last page turns back to the first");
        assertEquals(List.of("One A", "One B"), sink.spawnedText);
    }

    @Test
    @DisplayName("pages turn on their own every page-interval")
    void pagesTurnOnTheirOwn() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(),
                paged(20, List.of(), new HologramConfig.Page(List.of("Two"))));
        drive();
        advanceSeconds(0.5);
        FakeServer.tick(1);
        assertEquals(0, hologram.page(near.player()));

        advanceSeconds(0.6);
        FakeServer.tick(1);
        assertEquals(1, hologram.page(near.player()));
    }

    @Test
    @DisplayName("[PAGE] click lines turn the page, and each page runs its own click lines")
    void pageClicksTurnIt() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(), paged(0,
                List.of("[PAGE] next"),
                new HologramConfig.Page(List.of("Two"), List.of("two", "[PAGE] 1"))));
        drive();
        int box = Integer.parseInt(sink.calls("hitbox").get(0).split(":")[2]);

        HologramRuntime.clicked(near.player(), box);
        FakeServer.tick(1);
        assertEquals(1, hologram.page(near.player()));
        assertEquals(List.of(), near.commands());

        advanceSeconds(1);
        HologramRuntime.clicked(near.player(), box);
        FakeServer.tick(1);
        assertEquals(List.of("two"), near.commands());
        assertEquals(0, hologram.page(near.player()));
    }

    @Test
    @DisplayName("replacing the pages keeps the page each viewer is on")
    void replacingPagesKeepsThePage() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(),
                paged(0, List.of(), new HologramConfig.Page(List.of("Two"))));
        drive();
        hologram.page(near.player(), 1);
        FakeServer.tick(1);
        sink.clear();

        hologram.pages(List.of(List.of("One A", "One B"), List.of("Deux")));
        drive();
        assertEquals(1, hologram.page(near.player()));
        assertTrue(sink.calls("text").contains("text:Near:1002:Deux"), sink.calls().toString());
    }

    @Test
    @DisplayName("a head line is an item display standing in the stack")
    void headLinesAreItems() {
        LineStyle style = LineStyle.parse("<head:Notch><size:0.5>").style();
        assertTrue(style.isModel());
        assertFalse(style.block());
        assertEquals("PLAYER_HEAD", style.model());
        assertEquals("Notch", style.head());
    }

    // ------------------------------------------------------------------
    // Per-player
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a per-player hologram resolves its lines for each viewer")
    void perPlayerResolvesPerViewer() {
        Placeholders.register(plugin, "koth_viewer",
                request -> request.hasViewer() ? request.requireViewer().getName() : "nobody");

        HologramConfig config = new HologramConfig(true, HologramConfig.Kind.TEXT,
                List.of("Hello %koth_viewer%"), "", "", true, 32.0, 0, 0, 0,
                new HologramConfig.Properties(), new HologramConfig.Refresh());

        far.at(new Location(world, 1, 64, 1));
        Holograms.show(plugin, "koth", spawn(), config);
        drive();
        sink.clear();

        advanceSeconds(1.1);
        drive();

        assertEquals(List.of("text:Near:1000:Hello Near", "text:Far:1000:Hello Far"),
                sink.calls("text"));
    }

    // ------------------------------------------------------------------
    // 1.301.0: layout, size by distance, new motion, buttons
    // ------------------------------------------------------------------

    private static String plain(net.kyori.adventure.text.Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(component);
    }

    @Test
    @DisplayName("a gap pushes the lines above a line up by that much")
    void gapsMakeRoom() {
        Holograms.show(plugin, "koth", spawn(), text(List.of("A", "<gap:0.5>B", "C")));
        drive();
        assertEquals(List.of("spawn:Near:1000:65.0", "spawn:Near:1001:64.25", "spawn:Near:1002:64.0"),
                sink.calls("spawn"));
    }

    @Test
    @DisplayName("a line's own look overrides the hologram's")
    void lineOverrides() {
        HologramConfig.Properties base = new HologramConfig.Properties();
        HologramConfig.Properties line = LineStyle.parse(
                "<align:left><width:90><billboard:fixed><seethrough><brightness:12>x").style().apply(base);
        assertEquals("LEFT", line.alignment());
        assertEquals(90, line.lineWidth());
        assertEquals("FIXED", line.billboard());
        assertTrue(line.seeThrough());
        assertEquals(12, line.brightness());
        assertEquals(base, LineStyle.parse("x").style().apply(base), "a plain line inherits everything");
    }

    @Test
    @DisplayName("frames take turns, keeping the style that runs into them")
    void framesTakeTurns() {
        net.kyori.adventure.text.Component line = net.kyori.adventure.text.Component.text("A||BB||C",
                net.kyori.adventure.text.format.NamedTextColor.RED);
        assertEquals("A", plain(Shimmer.frame(line, 0)));
        assertEquals("BB", plain(Shimmer.frame(line, 1)));
        assertEquals("BB", plain(Shimmer.frame(line, 4)));
        assertEquals(net.kyori.adventure.text.format.NamedTextColor.RED,
                Shimmer.frame(line, 1).children().get(0).color());
        assertEquals("No frames", plain(Shimmer.frame(net.kyori.adventure.text.Component.text("No frames"), 3)));

        LineStyle style = LineStyle.parse("<frames:2>A||B").style();
        assertEquals(Shimmer.key(style, line, 0.1), Shimmer.key(style, line, 1.9), "same frame, nothing to send");
        assertFalse(Shimmer.key(style, line, 1.9) == Shimmer.key(style, line, 2.1));
    }

    @Test
    @DisplayName("a marquee scrolls a window through a long line and leaves a short one still")
    void marqueeScrolls() {
        net.kyori.adventure.text.Component line = net.kyori.adventure.text.Component.text("ABCDEFGHIJ");
        assertEquals("ABCD", plain(Shimmer.marquee(line, 4, 2, 0)));
        assertEquals("CDEF", plain(Shimmer.marquee(line, 4, 2, 1)));
        assertEquals("IJ  ", plain(Shimmer.marquee(line, 4, 2, 4)), "a gap before it comes round");
        assertEquals("ABCD", plain(Shimmer.marquee(line, 4, 2, 7)), "14 characters a lap");
        assertEquals("ABCDEFGHIJ", plain(Shimmer.marquee(line, 20, 2, 3)));
    }

    @Test
    @DisplayName("scrambled glyphs settle into the line from the left, spaces kept")
    void scrambleSettles() {
        net.kyori.adventure.text.Component line = net.kyori.adventure.text.Component.text("HELLO WORLD");
        String early = plain(Shimmer.scramble(line, 0.25, 20, 7));
        assertEquals(11, early.length());
        assertTrue(early.startsWith("HELLO "), early);
        assertFalse(early.endsWith("WORLD"), early);
        assertFalse(early.equals(plain(Shimmer.scramble(line, 0.25, 20, 8))), "each frame draws new glyphs");
        assertEquals("HELLO WORLD", plain(Shimmer.scramble(line, 1, 20, 9)));
    }

    @Test
    @DisplayName("rainbow, blink and fade follow the clock")
    void clockedLooks() {
        net.kyori.adventure.text.Component line = net.kyori.adventure.text.Component.text("ABCDEF");
        List<net.kyori.adventure.text.format.TextColor> now = colours(Shimmer.rainbow(line, 6, 0));
        assertEquals(6, now.stream().distinct().count(), now.toString());
        assertFalse(now.equals(colours(Shimmer.rainbow(line, 6, 0.5))));

        assertTrue(Shimmer.blinkShown(1, 0.2));
        assertFalse(Shimmer.blinkShown(1, 0.8));
        assertEquals("", plain(Shimmer.dress(LineStyle.parse("<blink:1>x").style(), line, 0.8)));

        assertEquals(255, Shimmer.fadeOpacity(255, 2, 0));
        assertEquals(Shimmer.FADE_FLOOR, Shimmer.fadeOpacity(255, 2, 1));
        int middle = Shimmer.fadeOpacity(255, 2, 0.5);
        assertEquals(0, middle % 8, "stepped, so it is only re-sent when it moves");
        assertTrue(middle > 100 && middle < 160, String.valueOf(middle));
        assertEquals(128, Shimmer.fadeOpacity(128, 2, 0), "never brighter than the line");
    }

    @Test
    @DisplayName("a fading line is sent its opacity as it steps, not every frame")
    void fadingSendsOpacity() {
        Holograms.show(plugin, "koth", spawn(), text(List.of("<fade:2>Hi")));
        drive();
        long first = sink.count("look");
        assertEquals(1, first);
        sink.clear();
        advanceSeconds(0.5);
        drive();
        assertEquals(1, sink.count("look"));
        sink.clear();
        drive();
        assertEquals(0, sink.count("look"), "the clock did not move");
    }

    @Test
    @DisplayName("sizes by distance step: re-sent only when a viewer's step changes")
    void distanceScaleSteps() {
        HologramConfig.Properties properties = new HologramConfig.Properties()
                .withDistanceScale(new HologramConfig.DistanceScale(true, 8, 32, 1, 2.5));
        HologramConfig config = new HologramConfig(true, HologramConfig.Kind.TEXT, List.of("A", "B"),
                "", "", false, 48.0, 0, 0, 0, properties, new HologramConfig.Refresh());
        Holograms.show(plugin, "koth", spawn(), config);
        drive();
        assertEquals(0, sink.count("transform"), "up close it is its own size");

        near.at(new Location(world, 0, 64, 20));
        drive();
        assertEquals(2, sink.count("transform"));
        assertEquals(1.8f, sink.lastScale.get(0), 1e-4, "1.75 rounded to its step");

        sink.clear();
        near.at(new Location(world, 0, 64, 20.2));
        drive();
        assertEquals(0, sink.count("transform"), "inside the same step");
    }

    @Test
    @DisplayName("an <autoscale> line grows alone and the stack makes room for it")
    void autoscaleLine() {
        Holograms.show(plugin, "koth", spawn(), text(List.of("A", "<autoscale:0,10,2>B")));
        near.at(new Location(world, 0, 64, 10));
        drive();
        // B is twice its size, so A stands on top of it: 0.5 up instead of 0.25.
        assertEquals(0.25f, sink.spawnedLift.get(0), 1e-4);
        assertEquals(0.0f, sink.spawnedLift.get(1), 1e-4);
    }

    @Test
    @DisplayName("clicking a button line runs its own lines and presses it; elsewhere runs the hologram's")
    void buttonLines() {
        HologramConfig.Click click = new HologramConfig.Click(List.of("whole"), 1.5, 0, 1.08,
                "BUTTON", "{primary}", "{secondary}", "", Map.of("join", List.of("joined")));
        HologramConfig config = new HologramConfig(true, HologramConfig.Kind.TEXT,
                List.of("Title", "<button:join>Join"), "", "", false, 32.0, 0, 0, 0,
                new HologramConfig.Properties(), new HologramConfig.Refresh(), click);
        Hologram hologram = Holograms.show(plugin, "koth", new Location(world, 0, 65, 0), config);
        // Join is 65.0 to 65.25, Title above it; the eye is at 65.62, three blocks back.
        near.at(new Location(world, 0, 64, -3, 0, (float) Math.toDegrees(Math.atan(0.5 / 3))));
        drive();
        assertEquals(2, sink.count("spawn"), "two lines: a button's background is its own, and no slab behind");
        int box = Integer.parseInt(sink.calls("hitbox").get(0).split(":")[2]);
        DisplayState join = sink.spawnedDisplays.get(1);
        assertEquals("  Join  ", sink.spawnedText.get(1), "a little room either side of the label");
        assertEquals(0x598a51c4, join.backgroundArgb(), "{primary} at rest, at 35%");
        assertFalse(join.properties().defaultBackground());
        assertEquals(0, sink.spawnedDisplays.get(0).backgroundArgb(), "the title keeps no background");

        sink.clear();
        drive();
        assertEquals(2, sink.count("transform"), "both lines follow the grown button");
        assertTrue(sink.lastScale.contains(1.0f), "the title keeps its size: " + sink.lastScale);
        assertTrue(sink.lastScale.contains(1.08f), sink.lastScale.toString());
        assertEquals(List.of("look:Near:" + join.entityId() + ":255:ccaa76de"), sink.calls("look"),
                "the button's own background lights to {secondary} at 80%");

        sink.clear();
        HologramRuntime.clicked(near.player(), box, net.exylia.lib.interaction.Click.RIGHT);
        FakeServer.tick(1);
        assertEquals(List.of("joined"), near.commands());
        assertTrue(sink.lastScale.stream().anyMatch(scale -> scale < 1), "pressed in");
        assertTrue(sink.calls("look").contains("look:Near:" + join.entityId() + ":255:ffaa76de"),
                "and flashes: " + sink.calls("look"));

        sink.clear();
        advanceSeconds(0.2);
        drive();
        assertTrue(sink.lastScale.stream().anyMatch(scale -> scale > 1), "springs back to its hover");

        near.at(new Location(world, 0, 64, -3, 0, (float) Math.toDegrees(Math.atan(0.22 / 3))));
        advanceSeconds(1);
        HologramRuntime.clicked(near.player(), box, net.exylia.lib.interaction.Click.RIGHT);
        FakeServer.tick(1);
        assertEquals(List.of("joined", "whole"), near.commands());
        assertTrue(hologram.isViewing(near.player()));
    }

    @Test
    @DisplayName("a hologram clicked from code only, or styled NONE, has no panel")
    void noPanelWithoutClickLines() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("A")));
        hologram.onClick(player -> { });
        drive();
        assertEquals(1, sink.count("spawn"));

        HologramConfig.Click none = new HologramConfig.Click(List.of("x"), 1.5, 0, 1.08,
                "NONE", null, null, null, null);
        Holograms.show(plugin, "other", spawn(), new HologramConfig(true, HologramConfig.Kind.TEXT,
                List.of("A"), "", "", false, 32.0, 0, 0, 0, new HologramConfig.Properties(),
                new HologramConfig.Refresh(), none));
        sink.clear();
        drive();
        assertEquals(1, sink.count("spawn"));
    }

    @Test
    @DisplayName("a panel colour takes a palette name or hex, with or without alpha")
    void panelColours() {
        assertEquals(0x50FF0000, HologramImpl.argb("#ff0000", 0x50));
        assertEquals(0x80123456, HologramImpl.argb("#80123456", 0x50));
        assertEquals(0x508a51c4, HologramImpl.argb("{primary}", 0x50));
        assertEquals(10, HologramImpl.step(1.04));
        assertEquals(11, HologramImpl.step(1.06));
    }

    private HologramConfig clickable(List<String> lines, HologramConfig.Click click) {
        return new HologramConfig(true, HologramConfig.Kind.TEXT, lines, "", "", false, 32.0, 0, 0, 0,
                new HologramConfig.Properties(), new HologramConfig.Refresh(), click);
    }

    @Test
    @DisplayName("a whole-hologram button gets one panel fitted to its lines, and the lines lose their own")
    void panelFitsTheLines() {
        HologramConfig.Click click = new HologramConfig.Click(List.of("next"), 1.5, 0, 1.0);
        Holograms.show(plugin, "koth", spawn(), clickable(List.of("<size:2>&lGET STARTED", "Line"), click));
        drive();
        assertEquals(3, sink.count("spawn"), "two lines and the panel");
        DisplayState title = sink.spawnedDisplays.get(0);
        assertEquals(0, title.backgroundArgb(), "no second background under the panel");
        assertEquals(1, title.billboard(), "upright, as the panel is, so they never part");

        DisplayState.Transform panel = sink.spawnedTransforms.get(2);
        // "GET STARTED" in bold is 74 pixels; at size 2 its background reaches 38 px * 0.05 = 1.9 either side.
        double half = (37 + 1) * HologramImpl.PX * 2;
        // The space's background spans -2 to +3 pixels before the panel's own scale and shift.
        double left = panel.tx() - 2 * HologramImpl.PX * panel.sx();
        double right = panel.tx() + 3 * HologramImpl.PX * panel.sx();
        assertEquals(-(half + 0.07), left, 1e-4);
        assertEquals(half + 0.07, right, 1e-4);
        // The title stands on Line (0.25) and is 11 pixels tall at size 2.
        double top = panel.ty() + 11 * HologramImpl.PX * panel.sy();
        assertEquals(0.25 + 11 * HologramImpl.PX * 2 + 0.07, top, 1e-4, "the title no longer pokes out");
        assertEquals(-0.07, panel.ty(), 1e-4);
        assertTrue(panel.tz() < 0, "behind the lines");
    }

    @Test
    @DisplayName("a display's extent is its background: width + 1 pixels, 10 a line + 1, wrapped past the line width")
    void extents() {
        double[] plain = HologramImpl.extent(HologramConfig.Kind.TEXT,
                net.kyori.adventure.text.Component.text("AB"), 200, 1, 1, 0.5);
        assertEquals((6 + 1) * HologramImpl.PX, plain[0], 1e-9);
        assertEquals(0.5, plain[1], 1e-9);
        assertEquals(0.5 + 11 * HologramImpl.PX, plain[2], 1e-9);

        double[] wrapped = HologramImpl.extent(HologramConfig.Kind.TEXT,
                net.kyori.adventure.text.Component.text("A".repeat(50)), 200, 1, 1, 0);
        assertEquals((100 + 1) * HologramImpl.PX, wrapped[0], 1e-9, "no wider than the line width");
        assertEquals(21 * HologramImpl.PX, wrapped[2], 1e-9, "300 pixels wrap onto a second line");

        double[] item = HologramImpl.extent(HologramConfig.Kind.ITEM, null, 200, 0.4, 0.4, 1);
        assertEquals(0.2, item[0], 1e-9);
        assertEquals(0.8, item[1], 1e-9);
        assertEquals(1.2, item[2], 1e-9);
    }

    @Test
    @DisplayName("a column of buttons is one width: shorter labels get more room")
    void buttonsShareAWidth() {
        HologramConfig.Click click = new HologramConfig.Click(List.of(), 1.5, 0, 1.08, "BUTTON",
                "{primary}", "{secondary}", "", Map.of("a", List.of("a"), "b", List.of("b")));
        Holograms.show(plugin, "koth", spawn(), clickable(List.of("<button:a>AAAA", "<button:b>AA"), click));
        drive();
        // 12 pixels short: 6 a side, a space and a half, rounded to two.
        assertEquals(List.of("  AAAA  ", "    AA    "), sink.spawnedText);
    }

    @Test
    @DisplayName("a swing or a use aimed at it clicks from as far as it lights up, and only once")
    void aimedClicksReachAsFarAsTheHover() {
        Hologram hologram = Holograms.show(plugin, "koth", new Location(world, 0, 65, 0),
                clickable(List.of("A"), new HologramConfig.Click(List.of("hit"), 1.5, 0)));
        // Six blocks back, the eye at 65.62 aims at 65.12.
        near.at(new Location(world, 0, 64, -6, 0, (float) Math.toDegrees(Math.atan(0.5 / 6))));
        drive();
        int box = Integer.parseInt(sink.calls("hitbox").get(0).split(":")[2]);

        HologramRuntime.aimed(near.player(), net.exylia.lib.interaction.Click.LEFT);
        FakeServer.tick(1);
        assertEquals(List.of("hit"), near.commands(), "well past the hand's reach of the box");

        advanceSeconds(1);
        HologramRuntime.clicked(near.player(), box, net.exylia.lib.interaction.Click.RIGHT);
        HologramRuntime.aimed(near.player(), net.exylia.lib.interaction.Click.LEFT);
        FakeServer.tick(1);
        assertEquals(List.of("hit", "hit"), near.commands(), "the box's click and the swing after it are one");

        advanceSeconds(1);
        near.at(new Location(world, 0, 64, -6, 180, 0));
        HologramRuntime.aimed(near.player(), net.exylia.lib.interaction.Click.LEFT);
        near.at(new Location(world, 0, 64, -9, 0, (float) Math.toDegrees(Math.atan(0.5 / 9))));
        HologramRuntime.aimed(near.player(), net.exylia.lib.interaction.Click.LEFT);
        FakeServer.tick(1);
        assertEquals(2, near.commands().size(), "looking away, or past its 8 blocks, clicks nothing");
        assertTrue(hologram.isViewing(near.player()));
    }
}
