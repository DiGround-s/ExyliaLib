package net.exylia.lib.screen.internal;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.screen.Screen;
import net.exylia.lib.screen.ScreenMarkup;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a screen puts on the wire: a markup rendered to looks and counted as packets and bytes for open, idle
 * tick, cursor move and hover. Bytes are estimated per field (a component as NBT, roughly), enough to compare frames.
 */
class ScreenWireTest {

    private static final String MARKUP = """
            <screen>
              <panel>
                <h1>Practice</h1>
                <row>
                  <button id="play" action="toast hi">Play</button>
                  <button id="kits" action="toast kits">Kits</button>
                </row>
                <text>Pick a mode</text>
                <text if="probe">never shown: counts renders</text>
              </panel>
            </screen>""";
    private static final String CSS = "panel { padding: 8px; gap: 6px; background: #15111d; }"
            + " button { padding: 4px 10px; background: #2a2238; } button:hover { background: #3c3150; }";

    /** Frames as sent, and as they were before the field diff. */
    private final List<List<PacketWrapper<?>>> sent = new ArrayList<>();
    private final List<List<PacketWrapper<?>>> raw = new ArrayList<>();

    /** PacketEvents' registries read their mappings through the API's settings: an API that only has those. */
    private static final class MappingsOnly extends com.github.retrooper.packetevents.PacketEventsAPI<Object> {
        @Override public boolean isLoaded() { return true; }
        @Override public void init() { }
        @Override public boolean isInitialized() { return true; }
        @Override public boolean isTerminated() { return false; }
        @Override public Object getPlugin() { return null; }
        @Override public com.github.retrooper.packetevents.manager.server.ServerManager getServerManager() {
            return () -> com.github.retrooper.packetevents.manager.server.ServerVersion.V_1_21_4;
        }
        @Override public com.github.retrooper.packetevents.manager.protocol.ProtocolManager getProtocolManager() { return null; }
        @Override public com.github.retrooper.packetevents.manager.player.PlayerManager getPlayerManager() { return null; }
        @Override public com.github.retrooper.packetevents.netty.NettyManager getNettyManager() { return null; }
        @Override public com.github.retrooper.packetevents.injector.ChannelInjector getInjector() { return null; }
    }

    @Test
    void framesSendOnlyWhatChanged() {
        var previous = com.github.retrooper.packetevents.PacketEvents.getAPI();
        if (previous == null) com.github.retrooper.packetevents.PacketEvents.setAPI(new MappingsOnly());
        try {
            run();
        } finally {
            com.github.retrooper.packetevents.PacketEvents.setAPI(previous);
        }
    }

    private void run() {
        FakeServer.install();
        FakeServer.reset();
        FakePlayer viewer = new FakePlayer("Viewer").at(new Location(null, 0, 64, 0));
        viewer.inventory(Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PlayerInventory.class},
                (self, method, args) -> FakeServer.defaultValue(method.getReturnType())));
        AtomicInteger renders = new AtomicInteger();
        ScreenMarkup markup = ScreenMarkup.parse("wire", MARKUP, CSS).value("probe", s -> {
            renders.incrementAndGet();
            return false;
        });
        assertTrue(markup.problems().isEmpty(), markup.problems().toString());
        Screen screen = markup.screen();
        AtomicInteger ids = new AtomicInteger(1000);
        ScreenPackets.Wire wire = new ScreenPackets.Wire(sent::add) {
            @Override
            synchronized void send(List<? extends PacketWrapper<?>> packets) {
                raw.add(List.copyOf(packets));
                super.send(packets);
            }
        };
        LiveScreen live = new LiveScreen(FakeServer.newPlugin("ScreenWire"), viewer.player(), screen, wire,
                ids::incrementAndGet);

        live.start();
        FakeServer.tick(5); // the first render, then the settle a tick later
        Stats open = take();
        assertTrue(open.packets > 10, open.toString());

        FakeServer.tick(40); // two one-second polls with nothing changed
        Stats idle = take();
        assertEquals(0, idle.packets, "an unchanged screen sends nothing: " + idle);
        assertEquals(0, idle.rawPackets, "and skips the diff: " + idle);

        // Where the play button is, and how far a degree moves the cursor.
        Layout.Placed play = Layout.lay(screen.render().apply(live), screen.width(), screen.height()).stream()
                .filter(box -> box.key().equals("#play")).findFirst().orElseThrow();
        live.turned(0, 0);
        double[] start = live.pointer();
        live.turned(1, 0);
        double perYaw = live.pointer()[0] - start[0];
        live.turned(1, 1);
        double perPitch = live.pointer()[1] - start[1];
        float yaw = (float) (1 + (play.x() + play.w() / 2 - live.pointer()[0]) / perYaw);
        float pitch = (float) (1 + (play.y() + play.h() / 2 - live.pointer()[1]) / perPitch);
        FakeServer.tick(1);
        take();

        live.turned(yaw, pitch);
        Stats arrive = take();
        FakeServer.tick(1); // the hover is drawn on the viewer's thread
        Stats hover = take();
        assertTrue(hover.packets > 0, "the hover is drawn: " + hover);
        assertTrue(hover.bytes < 1024, "a hover sends the box that changed, not the cursor: " + hover);
        assertTrue(arrive.bytes < 200, "the cursor is moved, never redrawn: " + arrive);

        live.turned(yaw + 0.05f, pitch + 0.05f);
        Stats move = take();
        FakeServer.tick(1);
        Stats after = take();
        assertEquals(4, move.packets, "one metadata per arrow copy: " + move);
        assertEquals(Set.of(8, 9, 11), move.indices, "a cursor move is translation only: " + move);
        assertEquals(0, after.packets, "a move within the same box redraws nothing: " + after);

        int before = renders.get();
        live.set("a", 1);
        live.set("b", 2);
        live.redraw();
        FakeServer.tick(1);
        assertEquals(before + 1, renders.get(), "set() twice and redraw() in one tick render once");

        live.close();
        System.out.println("[screen wire] open " + open + "\n[screen wire] idle tick " + idle
                + "\n[screen wire] cursor onto button " + arrive + "\n[screen wire] hover frame " + hover
                + "\n[screen wire] cursor move " + move);
    }

    private static final String MOTION = """
            <screen>
              <panel>
                <row>
                  <card id="a" action="set dialog true">A</card>
                  <card id="b" action="toast success Saved">B</card>
                  <box id="pulse" class="pulse"><text>Live</text></box>
                </row>
                <modal id="dlg" if="dialog" dismiss="clear dialog"><text>Sure?</text></modal>
              </panel>
            </screen>""";
    private static final String MOTION_CSS = "panel { padding: 8px; gap: 6px; background: #15111d; }"
            + " card { padding: 6px 10px; background: #2a2238; transition: transform 4t ease-out; }"
            + " card:hover { background: #3c3150; transform: translateY(-2px); }"
            + " modal { padding: 10px; background: #1a1424; enter: scale(0.9) 5t back; leave: scale(0.94) 3t ease-in; }"
            + " .pulse { padding: 2px 4px; background: #2a2238; animation: pulse 40t infinite; }"
            + " @keyframes pulse { 0% { transform: scale(1); } 50% { transform: scale(1.06); } 100% { transform: scale(1); } }";

    @Test
    void motionStaysWithinBudget() {
        var previous = com.github.retrooper.packetevents.PacketEvents.getAPI();
        if (previous == null) com.github.retrooper.packetevents.PacketEvents.setAPI(new MappingsOnly());
        try {
            motion();
        } finally {
            com.github.retrooper.packetevents.PacketEvents.setAPI(previous);
        }
    }

    private void motion() {
        FakeServer.install();
        FakeServer.reset();
        FakePlayer viewer = new FakePlayer("Mover").at(new Location(null, 0, 64, 0));
        viewer.inventory(Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PlayerInventory.class},
                (self, method, args) -> FakeServer.defaultValue(method.getReturnType())));
        ScreenMarkup markup = ScreenMarkup.parse("motion", MOTION, MOTION_CSS);
        assertTrue(markup.problems().isEmpty(), markup.problems().toString());
        Screen screen = markup.screen();
        AtomicInteger ids = new AtomicInteger(1000);
        List<int[]> destroyed = new ArrayList<>();
        ScreenPackets.Wire wire = new ScreenPackets.Wire(frame -> {
            sent.add(frame);
            for (PacketWrapper<?> p : frame) if (p instanceof WrapperPlayServerDestroyEntities d) destroyed.add(d.getEntityIds());
        });
        LiveScreen live = new LiveScreen(FakeServer.newPlugin("ScreenMotion"), viewer.player(), screen, wire,
                ids::incrementAndGet);
        live.start();
        FakeServer.tick(12);
        Stats open = take();

        // The badge pulses on its own: a second of it.
        FakeServer.tick(20);
        Stats pulse = take();
        assertTrue(pulse.packets > 0, "an animation keeps moving: " + pulse);
        assertTrue(pulse.packets <= 30, "a second of one pulse is a handful of packets: " + pulse);

        Layout.Placed card = Layout.lay(screen.render().apply(live), screen.width(), screen.height()).stream()
                .filter(box -> box.key().equals("#a")).findFirst().orElseThrow();
        live.turned(0, 0);
        double[] start = live.pointer();
        live.turned(1, 0);
        double perYaw = live.pointer()[0] - start[0];
        live.turned(1, 1);
        double perPitch = live.pointer()[1] - start[1];
        float yaw = (float) (1 + (card.x() + card.w() / 2 - live.pointer()[0]) / perYaw);
        float pitch = (float) (1 + (card.y() + card.h() / 2 - live.pointer()[1]) / perPitch);
        FakeServer.tick(1);
        take();
        live.turned(yaw, pitch);
        FakeServer.tick(6); // the lift and its eased steps
        Stats lift = take();
        assertTrue(lift.packets > 0 && lift.bytes < 3000, "an eased hover lift is a few steps of one card: " + lift);

        live.set("dialog", true);
        FakeServer.tick(8);
        Stats enter = take();
        assertTrue(enter.packets > 0 && enter.packets < 60, "a dialog arrives in a few frames: " + enter);
        destroyed.clear();
        live.set("dialog", null);
        FakeServer.tick(1);
        int gone = destroyed.stream().mapToInt(a -> a.length).sum();
        FakeServer.tick(6);
        int later = destroyed.stream().mapToInt(a -> a.length).sum() - gone;
        assertTrue(later > 0, "a leaving dialog is removed after its departure, not at once");
        Stats leave = take();

        live.toast("success", "Saved");
        FakeServer.tick(8);
        Stats toast = take();
        assertTrue(toast.packets > 0 && toast.packets < 20, "a toast is a box and its words: " + toast);

        live.close();
        System.out.println("[screen motion] open " + open + "\n[screen motion] pulse second " + pulse
                + "\n[screen motion] hover lift " + lift + "\n[screen motion] dialog enter " + enter
                + "\n[screen motion] dialog leave " + leave + "\n[screen motion] toast " + toast);
    }

    @Test
    void backgroundsStayWithinBudget() {
        var previous = com.github.retrooper.packetevents.PacketEvents.getAPI();
        if (previous == null) com.github.retrooper.packetevents.PacketEvents.setAPI(new MappingsOnly());
        try {
            backgrounds();
        } finally {
            com.github.retrooper.packetevents.PacketEvents.setAPI(previous);
        }
    }

    private void backgrounds() {
        FakeServer.install();
        FakeServer.reset();
        FakePlayer viewer = new FakePlayer("Painter").at(new Location(null, 0, 64, 0));
        viewer.inventory(Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PlayerInventory.class},
                (self, method, args) -> FakeServer.defaultValue(method.getReturnType())));
        int[] stars = new int[96 * 54];
        for (int i = 0; i < stars.length; i += 87) stars[i] = 0xFF6C5A8A; // about 60 stars, as the gallery's
        int[] tile = new int[16 * 16];
        java.util.Arrays.fill(tile, 0xFF8A51C4);
        Screen screen = Screen.of(session -> net.exylia.lib.screen.Node.column(
                                net.exylia.lib.screen.Node.text("Gradient").padding(6),
                                net.exylia.lib.screen.Node.box().size(60, 30).backgroundImage(
                                        net.exylia.lib.screen.Node.image(16, 16, tile)))
                        .size(300, 180).background("linear-gradient(to bottom, {primary}30, {primary}08)"))
                .backdrop("linear-gradient(to bottom, {primary}26, {info}0c)")
                .scene(net.exylia.lib.screen.Node.image(96, 54, stars)).parallax(3);
        AtomicInteger ids = new AtomicInteger(1000);
        ScreenPackets.Wire wire = new ScreenPackets.Wire(sent::add);
        LiveScreen live = new LiveScreen(FakeServer.newPlugin("ScreenPaint"), viewer.player(), screen, wire,
                ids::incrementAndGet);
        live.start();
        FakeServer.tick(8);
        Stats open = take();
        // Hitbox, up to 12 backdrop bands, 4 scene and 4 cursor copies, up to 12 panel bands, 4 picture copies, text.
        assertTrue(open.packets < 120, "a painted screen opens in a bounded number of packets: " + open);
        // A 96 x 54 scene is four copies of 54 lines of blocks and gaps: the bulk of this, and sent once.
        assertTrue(open.bytes < 100_000, "and a sparse scene stays within a chunk or two, sent once: " + open);
        FakeServer.tick(40);
        assertEquals(0, take().packets, "and sends nothing while idle");

        live.turned(0, 0);
        take();
        live.turned(2, 1);
        Stats move = take();
        assertEquals(8, move.packets, "a move with parallax: the arrow's and the scene's four copies: " + move);
        assertEquals(Set.of(8, 9, 11), move.indices, "translation only: " + move);
        live.close();
        System.out.println("[screen paint] open " + open + "\n[screen paint] parallax move " + move);
    }

    private record Stats(int frames, int packets, int bytes, int rawPackets, int rawBytes, Set<Integer> indices) {
        @Override
        public String toString() {
            return frames + " frames, " + packets + " packets, ~" + bytes + " B (before the diff: " + rawPackets
                    + " packets, ~" + rawBytes + " B), fields " + indices;
        }
    }

    private Stats take() {
        int packets = 0, bytes = 0, rawPackets = 0, rawBytes = 0;
        Set<Integer> indices = new TreeSet<>();
        for (List<PacketWrapper<?>> frame : sent) {
            packets += frame.size();
            for (PacketWrapper<?> packet : frame) {
                bytes += bytes(packet);
                if (packet instanceof WrapperPlayServerEntityMetadata data) {
                    for (EntityData<?> field : data.getEntityMetadata()) indices.add(field.getIndex());
                }
            }
        }
        for (List<PacketWrapper<?>> frame : raw) {
            rawPackets += frame.size();
            for (PacketWrapper<?> packet : frame) rawBytes += bytes(packet);
        }
        Stats stats = new Stats(sent.size(), packets, bytes, rawPackets, rawBytes, indices);
        sent.clear();
        raw.clear();
        return stats;
    }

    private static int bytes(PacketWrapper<?> packet) {
        if (packet instanceof WrapperPlayServerEntityMetadata data) {
            int n = 1 + varint(data.getEntityId()) + 1;
            for (EntityData<?> field : data.getEntityMetadata()) n += 2 + size(field.getValue());
            return n;
        }
        if (packet instanceof WrapperPlayServerSpawnEntity spawn) return 1 + varint(spawn.getEntityId()) + 16 + 1 + 24 + 3 + 1 + 6;
        if (packet instanceof WrapperPlayServerDestroyEntities destroy) {
            int n = 2;
            for (int id : destroy.getEntityIds()) n += varint(id);
            return n;
        }
        return 8;
    }

    private static int size(Object value) {
        return switch (value) {
            case Integer i -> varint(i);
            case Byte b -> 1;
            case Float f -> 4;
            case Vector3f v -> 12;
            case Quaternion4f q -> 16;
            case Component c -> component(c);
            default -> 32;
        };
    }

    /** About what a component costs as NBT: its words in UTF-8, a tag per part, its colour and decoration. */
    private static int component(Component c) {
        int n = 8;
        if (c instanceof net.kyori.adventure.text.TextComponent text) {
            n += text.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        if (c.color() != null) n += 16;
        if (c.decoration(net.kyori.adventure.text.format.TextDecoration.BOLD)
                == net.kyori.adventure.text.format.TextDecoration.State.TRUE) n += 8;
        for (Component child : c.children()) n += component(child);
        return n;
    }

    private static int varint(int value) {
        int n = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            n++;
        }
        return n;
    }
}
