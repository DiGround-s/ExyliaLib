package net.exylia.lib.npc.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.interaction.Click;
import net.exylia.lib.npc.NpcAnimation;
import net.exylia.lib.npc.NpcAnimations;
import net.exylia.lib.npc.NpcLook;
import net.exylia.lib.npc.NpcPerformance;
import net.exylia.lib.npc.NpcPose;
import net.exylia.lib.npc.NpcSkin;
import net.exylia.lib.npc.NpcSpec;
import net.exylia.lib.npc.Npcs;
import net.exylia.lib.npc.PlacedNpc;
import net.exylia.lib.npc.PlacedNpcClickEvent;
import net.exylia.lib.npc.PluginNpcs;
import net.exylia.lib.task.Tasks;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a placed NPC writes to each client, and what it never does.
 */
class PlacedNpcTest {

    private static final String PLUGIN = "PlacedNpcTest";

    private final AtomicLong clock = new AtomicLong(10_000L);
    private final AtomicInteger nextId = new AtomicInteger(500);
    private final AtomicInteger mojangCalls = new AtomicInteger();
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final List<String> glows = new CopyOnWriteArrayList<>();

    private Plugin plugin;
    private PluginNpcs npcs;
    private World world;
    private FakePlayer near;
    private FakePlayer far;

    private final PlacedSink sink = new PlacedSink() {
        @Override
        public void announce(Player viewer, UUID profile, String name, String[] skin, int glow) {
            sent.add("announce:" + viewer.getName() + ":" + (skin == null ? "default" : skin[0]));
            glows.add(viewer.getName() + ":" + glow);
        }

        @Override
        public void draw(Player viewer, int entityId, UUID profile, String name, NpcSpec spec,
                         Location at, float yaw, float pitch) {
            sent.add("draw:" + viewer.getName() + ":" + entityId);
        }

        @Override
        public void unlist(Player viewer, UUID profile) {
            sent.add("unlist:" + viewer.getName());
        }

        @Override
        public void look(Player viewer, int entityId, float yaw, float pitch) {
            sent.add("look:" + viewer.getName() + ":" + Math.round(yaw));
        }

        @Override
        public void teleport(Player viewer, int entityId, Location to) {
            sent.add("teleport:" + viewer.getName());
        }

        @Override
        public void equip(Player viewer, int entityId, EquipmentSlot slot, ItemStack item) {
            sent.add("equip:" + viewer.getName() + ":" + slot);
        }

        @Override
        public void pose(Player viewer, int entityId, NpcPose pose) {
            sent.add("pose:" + viewer.getName() + ":" + pose);
        }

        @Override
        public void swing(Player viewer, int entityId) {
            sent.add("swing:" + viewer.getName());
        }

        @Override
        public void hurt(Player viewer, int entityId) {
            sent.add("hurt:" + viewer.getName());
        }

        @Override
        public void swingOffhand(Player viewer, int entityId) {
            sent.add("offhand:" + viewer.getName());
        }

        @Override
        public void seat(Player viewer, int seatId, int entityId, Location at) {
            sent.add("seat:" + viewer.getName() + ":" + seatId + ">" + entityId);
        }

        @Override
        public void unseat(Player viewer, int seatId) {
            sent.add("unseat:" + viewer.getName() + ":" + seatId);
        }

        @Override
        public void destroy(Player viewer, int entityId, UUID profile, String name) {
            sent.add("destroy:" + viewer.getName() + ":" + entityId);
        }

        @Override
        public void glow(Player viewer, int entityId, String name, int glow, boolean hidden) {
            sent.add("glow:" + viewer.getName() + ":" + Integer.toHexString(glow));
        }

        @Override
        public void hide(Player viewer, int entityId, NpcSpec spec, boolean hidden) {
            sent.add("hide:" + viewer.getName() + ":" + (hidden ? "on" : "off"));
        }
    };

    /** When the fake body stands in its last pose; -1 loops. */
    private long standsAt = 200L;

    private final NpcStage stage = (owner, performance, at, audience, spec, texture) -> {
        List<String> names = new ArrayList<>();
        for (Player player : audience) {
            names.add(player.getName());
        }
        sent.add("stage:" + String.join("+", performance.lines()) + ":" + names + ":" + texture);
        long stands = standsAt;
        return new NpcStage.Act() {
            @Override
            public long standsAtMillis() {
                return stands;
            }

            @Override
            public void stop(long settleMillis) {
                sent.add("stop:" + settleMillis);
            }
        };
    };

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin(PLUGIN, null);
        PlacedRuntime.testHooks(Tasks.of(plugin), clock::get, nextId::getAndIncrement, sink,
                player -> new String[] {"skin-of-" + player.getName(), "signed"},
                name -> {
                    mojangCalls.incrementAndGet();
                    return new String[] {"mojang-" + name, "signed"};
                });
        PlacedRuntime.testStage(stage);
        npcs = Npcs.of(plugin);
        world = FakeServer.newWorld("world");
        near = new FakePlayer("Near").at(new Location(world, 3, 64, 0));
        far = new FakePlayer("Far").at(new Location(world, 500, 64, 500));
        FakeServer.online(near.player(), far.player());
    }

    @AfterEach
    void tearDown() {
        PlacedRuntime.releaseAll();
        PlacedRuntime.testStage(NpcStage.SEQUENCES);
        FakeServer.reset();
    }

    private Location origin() {
        return new Location(world, 0, 64, 0);
    }

    private static NpcSpec spec() {
        return NpcSpec.builder().build();
    }

    /** Two passes: one announces the identity, the next draws the body. */
    private static void show() {
        PlacedRuntime.tick();
        PlacedRuntime.tick();
    }

    private List<String> sent(String kind) {
        List<String> matching = new ArrayList<>();
        for (String line : sent) {
            if (line.startsWith(kind + ":")) {
                matching.add(line);
            }
        }
        return matching;
    }

    @Test
    @DisplayName("only players in range are sent it: the identity first, the body a pass later")
    void announcedThenDrawnForPlayersInRange() {
        PlacedNpc npc = npcs.place("guide", spec(), origin());

        PlacedRuntime.tick();
        assertEquals(List.of("announce:Near:default"), sent);
        assertFalse(npc.isViewing(near.player()), "announced is not on screen yet");

        PlacedRuntime.tick();
        assertEquals(List.of("draw:Near:500"), sent("draw"));
        assertTrue(npc.isViewing(near.player()));
        assertEquals(1, npc.viewerCount());

        sent.clear();
        PlacedRuntime.tick();
        assertEquals(List.of(), sent, "staying in range sends nothing");
    }

    @Test
    @DisplayName("the identity leaves the player list once the body is drawn, so no command completes it")
    void unlistedAfterDrawing() {
        npcs.place("guide", spec(), origin());
        show();
        for (int pass = 1; pass < PlacedNpcImpl.UNLIST_AFTER_PASSES; pass++) {
            PlacedRuntime.tick();
        }
        assertEquals(List.of(), sent("unlist"), "the skin is still on its way");

        PlacedRuntime.tick();
        PlacedRuntime.tick();
        assertEquals(List.of("unlist:Near"), sent("unlist"), "sent once");
    }

    @Test
    @DisplayName("walking out of range takes it back, walking in sends it again")
    void followsTheViewDistance() {
        PlacedNpc npc = npcs.place("guide", NpcSpec.builder().viewDistance(10).build(), origin());
        show();
        sent.clear();

        near.at(new Location(world, 20, 64, 0));
        PlacedRuntime.tick();
        assertEquals(List.of("destroy:Near:500"), sent);
        assertEquals(0, npc.viewerCount());

        near.at(new Location(world, 5, 64, 0));
        show();
        assertEquals(List.of("destroy:Near:500", "announce:Near:default", "draw:Near:500"), sent);
    }

    @Test
    @DisplayName("changing world drops it, and coming back sends it again")
    void worldChangeRespawns() {
        npcs.place("guide", spec(), origin());
        show();
        sent.clear();

        World nether = FakeServer.newWorld("nether");
        near.at(new Location(nether, 3, 64, 0));
        PlacedRuntime.tick();
        assertEquals(List.of("destroy:Near:500"), sent);

        near.at(new Location(world, 3, 64, 0));
        show();
        assertEquals(List.of("announce:Near:default", "draw:Near:500"),
                sent.subList(1, sent.size()));
    }

    @Test
    @DisplayName("an attack is a left click, a use a right one, sneaking adds shift, a double press counts once")
    void clicksMapAndDedupe() {
        PlacedNpc npc = npcs.place("guide", spec(), origin());
        List<Click> clicks = new CopyOnWriteArrayList<>();
        npc.onClick((player, click) -> clicks.add(click));
        show();

        PlacedRuntime.clicked(near.player(), 500, true);
        PlacedRuntime.clicked(near.player(), 500, true);
        clock.addAndGet(300);
        PlacedRuntime.clicked(near.player(), 500, false);
        clock.addAndGet(300);
        near.sneaking(true);
        PlacedRuntime.clicked(near.player(), 500, false);
        clock.addAndGet(300);
        PlacedRuntime.clicked(near.player(), 500, true);
        PlacedRuntime.clicked(far.player(), 500, true);
        FakeServer.tick(1);

        assertEquals(List.of(Click.LEFT, Click.RIGHT, Click.SHIFT_RIGHT, Click.SHIFT_LEFT), clicks,
                "a non-viewer's click is ignored too");
        assertTrue(PlacedRuntime.isPlaced(500));
        npc.remove();
        assertFalse(PlacedRuntime.isPlaced(500));
    }

    @Test
    @DisplayName("every counted click fires PlacedNpcClickEvent, with or without a handler")
    void clicksFireTheEvent() {
        PlacedNpc npc = npcs.place("guide", spec(), origin());
        show();

        PlacedRuntime.clicked(near.player(), 500, false);
        PlacedRuntime.clicked(near.player(), 500, false);
        PlacedRuntime.clicked(far.player(), 500, true);
        FakeServer.tick(1);

        List<PlacedNpcClickEvent> events = FakeServer.events(PlacedNpcClickEvent.class);
        assertEquals(1, events.size(), "the double press counts once; a non-viewer never");
        PlacedNpcClickEvent event = events.get(0);
        assertEquals("guide", event.npcId());
        assertEquals(plugin, event.owner());
        assertEquals(near.player(), event.getPlayer());
        assertEquals(Click.RIGHT, event.click());
        assertEquals(npc, event.npc());
    }

    @Test
    @DisplayName("a viewer's own outline reaches only them, now and on the next spawn")
    void glowPerViewer() {
        FakePlayer other = new FakePlayer("Other").at(new Location(world, -3, 64, 0));
        FakeServer.online(near.player(), far.player(), other.player());
        PlacedNpc npc = npcs.place("guide", spec(), origin());
        npc.glow(near.player(), 0xFFD700);
        show();
        assertTrue(glows.contains("Near:" + 0xFFD700), "announced with their colour");
        assertTrue(glows.contains("Other:-1"), "everybody else sees the spec's");

        sent.clear();
        npc.glow(other.player(), 0x00FF00);
        assertEquals(List.of("glow:Other:ff00"), sent, "sent to them alone, at once");

        sent.clear();
        npc.resetGlow(other.player());
        assertEquals(List.of("glow:Other:ffffffff"), sent);
        npc.resetGlow(other.player());
        assertEquals(1, sent.size(), "nothing changed, nothing sent");
    }

    @Test
    @DisplayName("placing under a used id replaces the NPC and takes the old one off screen")
    void reusingAnIdReplaces() {
        PlacedNpc first = npcs.place("guide", spec(), origin());
        show();
        sent.clear();

        PlacedNpc second = npcs.place("guide", spec(), origin());
        assertEquals(List.of("destroy:Near:500"), sent);
        assertTrue(first.removed());
        assertEquals(second, npcs.placed("guide").orElseThrow());
        assertEquals(1, npcs.allPlaced().size());
        assertFalse(PlacedRuntime.isPlaced(500));
        assertTrue(PlacedRuntime.isPlaced(501));
    }

    @Test
    @DisplayName("disabling the plugin takes every NPC it placed off every screen")
    void releasedWithThePlugin() {
        npcs.place("a", spec(), origin());
        npcs.place("b", spec(), origin());
        show();
        sent.clear();

        Npcs.release(PLUGIN);
        assertEquals(2, sent("destroy").size());
        assertEquals(0, PlacedRuntime.count());
        assertTrue(Npcs.of(plugin).allPlaced().isEmpty());

        sent.clear();
        PlacedRuntime.tick();
        assertEquals(List.of(), sent, "nothing comes back");
    }

    @Test
    @DisplayName("look angles follow Minecraft's yaw: east is -90, south is 0, above is negative pitch")
    void lookAngles() {
        Location at = origin();
        float[] east = PlacedNpcImpl.angles(at, PlacedNpcImpl.EYE_HEIGHT, new Location(world, 5, 64, 0));
        float[] south = PlacedNpcImpl.angles(at, PlacedNpcImpl.EYE_HEIGHT, new Location(world, 0, 64, 5));
        float[] west = PlacedNpcImpl.angles(at, PlacedNpcImpl.EYE_HEIGHT, new Location(world, -5, 64, 0));
        float[] above = PlacedNpcImpl.angles(at, PlacedNpcImpl.EYE_HEIGHT, new Location(world, 0, 69, 5));

        assertEquals(-90f, east[0], 0.01f);
        assertEquals(0f, east[1], 0.01f);
        assertEquals(0f, south[0], 0.01f);
        assertEquals(90f, west[0], 0.01f);
        assertEquals(-45f, above[1], 0.01f);
    }

    @Test
    @DisplayName("each viewer sees the head turned at themselves, and only again when they move enough")
    void eachViewerLooksAtThemselves() {
        far.at(new Location(world, 0, 64, 4));
        npcs.place("guide", NpcSpec.builder().look(NpcLook.EACH_VIEWER).build(), origin());
        show();

        assertTrue(sent.contains("look:Near:-90"), sent.toString());
        assertFalse(sent.toString().contains("look:Far"),
                "south is the yaw it was drawn with, so Far needs no turn");
        sent.clear();

        PlacedRuntime.tick();
        assertEquals(List.of(), sent("look"), "nobody moved");

        far.at(new Location(world, -4, 64, 0));
        PlacedRuntime.tick();
        assertEquals(List.of("look:Far:90"), sent("look"));
    }

    @Test
    @DisplayName("nearest: every viewer sees the head turned at the closest one")
    void nearestTurnsForEverybody() {
        far.at(new Location(world, 0, 64, 6));
        npcs.place("guide", NpcSpec.builder().look(NpcLook.NEAREST).build(), origin());
        show();
        assertTrue(sent.contains("look:Near:-90"), sent.toString());
        assertTrue(sent.contains("look:Far:-90"), sent.toString());
    }

    @Test
    @DisplayName("a mirrored skin is each viewer's own")
    void mirrorShowsTheViewersSkin() {
        far.at(new Location(world, 0, 64, 4));
        npcs.place("guide", NpcSpec.builder().skin(NpcSkin.mirror()).build(), origin());
        PlacedRuntime.tick();

        assertTrue(sent.contains("announce:Near:skin-of-Near"), sent.toString());
        assertTrue(sent.contains("announce:Far:skin-of-Far"), sent.toString());
    }

    @Test
    @DisplayName("an account skin is looked up once, in the background, and redrawn when it arrives")
    void accountSkinArrivesLater() {
        PlacedNpc npc = npcs.place("guide",
                NpcSpec.builder().skin(NpcSkin.player("Notch")).build(), origin());
        show();
        assertEquals(List.of("announce:Near:default"), sent("announce"));
        assertEquals(0, mojangCalls.get(), "never on the pass itself");

        FakeServer.tick(1);
        assertEquals(1, mojangCalls.get());
        assertEquals(0, npc.viewerCount(), "taken back so it can be sent with the skin");

        show();
        assertEquals(List.of("announce:Near:default", "announce:Near:mojang-Notch"), sent("announce"));

        npcs.place("other", NpcSpec.builder().skin(NpcSkin.player("notch")).build(), origin());
        PlacedRuntime.tick();
        FakeServer.tick(1);
        assertEquals(1, mojangCalls.get(), "cached, whatever the case");
    }

    @Test
    @DisplayName("a new pose is one packet; a new outline gives viewers the NPC again")
    void updateSendsOnlyWhatChanged() {
        PlacedNpc npc = npcs.place("guide", spec(), origin());
        show();
        sent.clear();

        npc.update(npc.spec().toBuilder().pose(NpcPose.SNEAKING).build());
        assertEquals(List.of("pose:Near:SNEAKING"), sent);
        sent.clear();

        npc.update(npc.spec().toBuilder().glow(0xFF0000).build());
        assertEquals(List.of("destroy:Near:500"), sent);
        show();
        assertEquals(1, npc.viewerCount());
    }

    @Test
    @DisplayName("moving in the same world is a teleport, not a respawn")
    void moveIsATeleport() {
        PlacedNpc npc = npcs.place("guide", spec(), origin());
        show();
        sent.clear();

        npc.moveTo(new Location(world, 1, 64, 1));
        assertEquals(List.of("teleport:Near"), sent);
        npc.swing();
        npc.hurt();
        assertEquals(List.of("teleport:Near", "swing:Near", "hurt:Near"), sent);
    }

    @Test
    @DisplayName("a filter hides it from who it rejects")
    void filterHides() {
        PlacedNpc npc = npcs.place("guide", spec(), origin());
        show();
        sent.clear();

        npc.visibleIf(player -> !player.getName().equals("Near"));
        assertEquals(List.of("destroy:Near:500"), sent);
        show();
        assertEquals(1, sent.size(), "not sent again");
    }

    @Test
    @DisplayName("a viewer who walks out of the look range sees the head ease home, not snap")
    void headEasesHomeOutOfRange() {
        npcs.place("guide", NpcSpec.builder().look(NpcLook.EACH_VIEWER).lookRange(8).build(), origin());
        show();
        assertTrue(sent.contains("look:Near:-90"), sent.toString());
        sent.clear();

        near.at(new Location(world, 20, 64, 0));
        PlacedRuntime.tick();
        PlacedRuntime.tick();
        PlacedRuntime.tick();
        assertEquals(List.of("look:Near:-45", "look:Near:0"), sent("look"),
                "45 degrees a pass, then still once home");
    }

    @Test
    @DisplayName("an animation sends its moves at their moments, and a crouch always stands back up")
    void playSchedulesMoves() {
        PlacedNpc npc = npcs.place("guide", spec(), origin());
        show();
        sent.clear();

        npc.play(NpcAnimation.parse("swing, 100ms, crouch"));
        assertEquals(List.of("swing:Near"), sent);
        FakeServer.tick(3);
        assertEquals(List.of("swing:Near", "pose:Near:SNEAKING", "pose:Near:STANDING"), sent);

        sent.clear();
        npc.play(NpcAnimation.parse("offhand"), far.player());
        assertEquals(List.of(), sent, "Far does not see it, so nothing is sent");
        npc.play(NpcAnimation.parse("hurt"), near.player());
        assertEquals(List.of("hurt:Near"), sent);
    }

    @Test
    @DisplayName("click triggers play for the click they are for, before the handler")
    void clickAnimations() {
        npcs.place("guide", NpcSpec.builder()
                .animations(new NpcAnimations("hurt", "swing", "", Duration.ZERO, "", 0, Duration.ZERO))
                .build(), origin());
        show();
        sent.clear();

        PlacedRuntime.clicked(near.player(), 500, true);
        clock.addAndGet(300);
        PlacedRuntime.clicked(near.player(), 500, false);
        assertEquals(List.of("hurt:Near", "swing:Near"), sent);
    }

    @Test
    @DisplayName("the idle animation plays every interval, only while somebody sees it")
    void idleInterval() {
        npcs.place("guide", NpcSpec.builder()
                .animations(new NpcAnimations("", "", "swing", Duration.ofSeconds(10), "", 0, Duration.ZERO))
                .build(), origin());
        show();
        sent.clear();

        clock.addAndGet(9_000);
        PlacedRuntime.tick();
        assertEquals(List.of(), sent("swing"));
        clock.addAndGet(1_000);
        PlacedRuntime.tick();
        assertEquals(List.of("swing:Near"), sent("swing"));
        PlacedRuntime.tick();
        assertEquals(1, sent("swing").size(), "not again before the next interval");
    }

    @Test
    @DisplayName("a greeting plays only for the player who walked up, once per cooldown")
    void greetsOnlyWhoCameClose() {
        far.at(new Location(world, 10, 64, 0));
        npcs.place("guide", NpcSpec.builder()
                .animations(new NpcAnimations("", "", "", Duration.ZERO, "swing", 4, Duration.ofSeconds(30)))
                .build(), origin());
        show();
        assertEquals(List.of("swing:Near"), sent("swing"), "Near was already close; Far is not");
        sent.clear();

        far.at(new Location(world, 2, 64, 0));
        PlacedRuntime.tick();
        assertEquals(List.of("swing:Far"), sent("swing"));

        far.at(new Location(world, 10, 64, 0));
        PlacedRuntime.tick();
        far.at(new Location(world, 2, 64, 0));
        PlacedRuntime.tick();
        assertEquals(1, sent("swing").size(), "inside the cooldown");

        clock.addAndGet(31_000);
        far.at(new Location(world, 10, 64, 0));
        PlacedRuntime.tick();
        far.at(new Location(world, 2, 64, 0));
        PlacedRuntime.tick();
        assertEquals(2, sent("swing").size());
    }

    @Test
    @DisplayName("a sitting NPC rides its own seat, which goes with it and respawns when it stands")
    void sitsOnASeat() {
        PlacedNpc npc = npcs.place("guide", NpcSpec.builder().sitting(true).build(), origin());
        show();
        assertTrue(sent.contains("seat:Near:501>500"), sent.toString());
        sent.clear();

        npc.moveTo(new Location(world, 1, 64, 0));
        assertEquals(List.of("teleport:Near", "teleport:Near"), sent("teleport"), "seat and body");
        sent.clear();

        npc.update(npc.spec().toBuilder().sitting(false).build());
        assertEquals(List.of("unseat:Near:501", "destroy:Near:500"), sent);
    }

    // ------------------------------------------------------------------ performances

    private static final String WAVE = "[RAGDOLL] {victim};intact:0;follow:1;breathe:1;anim:wave";

    @Test
    @DisplayName("a performance hides the NPC, plays one body, and puts the NPC back before the body goes")
    void performHidesThenReveals() {
        PlacedNpc npc = npcs.place("guide", NpcSpec.builder().skin(NpcSkin.texture("tex", "sig")).build(),
                origin());
        show();
        sent.clear();

        assertTrue(npc.perform(NpcPerformance.parse("anim:wave")));
        assertEquals(List.of("stage:" + WAVE + ":[Near]:tex", "hide:Near:on"), sent);
        assertTrue(npc.isPerforming());

        assertFalse(npc.perform(NpcPerformance.parse("anim:bow")), "one at a time: the second is skipped");
        assertFalse(npc.perform(NpcPerformance.parse("anim:bow"), near.player()));

        sent.clear();
        FakeServer.tick(6);
        assertEquals(List.of("hide:Near:off", "stop:0"), sent, "the NPC first, then the body");
        assertFalse(npc.isPerforming());
        assertTrue(npc.perform(NpcPerformance.parse("anim:wave")), "free again");
    }

    @Test
    @DisplayName("only viewers within the perform range get a body; with nobody there it is skipped")
    void performOnlyInRange() {
        PlacedNpc npc = npcs.place("guide", NpcSpec.builder()
                .animations(new NpcAnimations("", "", "", Duration.ZERO, "", 0, Duration.ZERO, 2.0))
                .build(), origin());
        show();
        sent.clear();

        assertFalse(npc.perform(NpcPerformance.parse("anim:wave")), "Near is 3 blocks away");
        assertFalse(npc.perform(NpcPerformance.parse("anim:wave"), far.player()), "Far does not see it");
        assertEquals(List.of(), sent);

        near.at(new Location(world, 1, 64, 0));
        assertTrue(npc.perform(NpcPerformance.parse("anim:wave"), near.player()));
    }

    @Test
    @DisplayName("removing, moving or changing the NPC ends the performance at once")
    void cancelledOnUnplaceAndMove() {
        PlacedNpc npc = npcs.place("guide", spec(), origin());
        show();
        npc.perform(NpcPerformance.parse("anim:wave"));
        sent.clear();
        npc.moveTo(new Location(world, 1, 64, 0));
        assertEquals(List.of("hide:Near:off", "stop:0", "teleport:Near"), sent);

        npc.perform(NpcPerformance.parse("anim:wave"));
        sent.clear();
        npcs.unplace("guide");
        assertEquals(List.of("stop:0", "destroy:Near:500"), sent, "nothing to reveal on a removed NPC");
        sent.clear();
        FakeServer.tick(10);
        assertEquals(List.of(), sent, "the scheduled end finds nothing");
        assertFalse(npc.isPerforming());
    }

    @Test
    @DisplayName("a loop plays to its limit, stands back up, and only then is the NPC back")
    void loopsEndAtTheirLimit() {
        standsAt = -1L;
        PlacedNpc npc = npcs.place("guide", spec(), origin());
        show();
        assertTrue(npc.perform(NpcPerformance.parse("loop:true;anim:wave").loopLimit(Duration.ofSeconds(1))));
        sent.clear();
        FakeServer.tick(22);
        assertEquals(List.of("stop:" + PlacedNpcImpl.SETTLE_MILLIS), sent);
        FakeServer.tick(10);
        assertEquals(List.of("stop:" + PlacedNpcImpl.SETTLE_MILLIS, "hide:Near:off"), sent);
    }

    @Test
    @DisplayName("emote:<id> is what the plugin's resolver says, and nothing without one")
    void emotesResolve() {
        PlacedNpc npc = npcs.place("guide", spec(), origin());
        show();
        assertFalse(npc.perform(NpcPerformance.parse("emote:wave")), "no resolver");

        npcs.emotes(id -> id.equals("wave") ? NpcPerformance.parse("anim:wave") : null);
        assertFalse(npc.perform(NpcPerformance.parse("emote:nope")));
        sent.clear();
        assertTrue(npc.perform(NpcPerformance.parse("emote:wave")));
        assertEquals("stage:" + WAVE + ":[Near]:null", sent.get(0), "default skin");
    }

    @Test
    @DisplayName("a trigger with a colon performs; a sitting NPC never does; a mirror wears the viewer")
    void triggersPerform() {
        PlacedNpc npc = npcs.place("guide", NpcSpec.builder().skin(NpcSkin.mirror())
                .animations(new NpcAnimations("", "anim:wave", "", Duration.ZERO, "", 0, Duration.ZERO))
                .build(), origin());
        show();
        sent.clear();

        PlacedRuntime.clicked(near.player(), 500, false);
        FakeServer.tick(1);
        assertEquals(List.of(), sent("swing"));
        assertEquals(List.of("stage:" + WAVE + ":[Near]:null"), sent("stage"),
                "one body for everybody wears the default skin");
        FakeServer.tick(6);

        sent.clear();
        assertTrue(npc.perform(NpcPerformance.parse("anim:wave"), near.player()));
        assertEquals("stage:" + WAVE + ":[Near]:skin-of-Near", sent.get(0));
        FakeServer.tick(6);

        npc.update(npc.spec().toBuilder().sitting(true).build());
        show();
        assertFalse(npc.perform(NpcPerformance.parse("anim:wave")));
    }
}
