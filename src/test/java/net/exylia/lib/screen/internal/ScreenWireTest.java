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
