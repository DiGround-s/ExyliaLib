package net.exylia.lib.packet.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.netty.buffer.ByteBufOperator;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The entity id peeked off a packet without decoding it, and the flattened-line cache. */
class PacketHooksTest {

    private static PacketEventsAPI<?> previous;

    @BeforeAll
    static void installApi() {
        previous = PacketEvents.getAPI();
        PacketEvents.setAPI(new FakeApi());
    }

    @AfterAll
    static void restoreApi() {
        PacketEvents.setAPI(previous);
    }

    @Test
    @DisplayName("the peeked id is the id the wrapper writes, and the buffer is left where it was")
    void peekMatchesWrapper() {
        // Ids past one VarInt byte, so a peek that reads only the first would fail.
        int id = 1_234_567;
        List<PacketWrapper<?>> packets = List.of(
                new WrapperPlayServerEntityMetadata(id, List.of(new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x20))),
                new WrapperPlayServerEntityRelativeMove(id, 0.5, -0.25, 1, true),
                new WrapperPlayServerEntityRelativeMoveAndRotation(id, 0.5, -0.25, 1, 90f, 10f, true),
                new WrapperPlayServerEntityRotation(id, 90f, 10f, true),
                new WrapperPlayServerEntityHeadLook(id, 45f),
                new WrapperPlayServerEntityTeleport(id, new Vector3d(1, 2, 3), 90f, 10f, true),
                new WrapperPlayServerEntityVelocity(id, new Vector3d(0.1, 0.2, 0.3)),
                new WrapperPlayServerEntityAnimation(id, WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM),
                new WrapperPlayServerEntityEquipment(id, List.of()),
                new WrapperPlayServerHurtAnimation(id, 12f));
        for (PacketWrapper<?> packet : packets) {
            assertTrue(PacketHooks.leadsWithEntityId(packet.getPacketTypeData().getPacketType()),
                    packet.getClass().getSimpleName());
            Buf buf = written(packet);
            int before = ByteBufHelper.readerIndex(buf);

            assertEquals(id, PacketHooks.peekVarInt(buf), packet.getClass().getSimpleName());
            assertEquals(before, ByteBufHelper.readerIndex(buf), packet.getClass().getSimpleName());
        }
    }

    @Test
    @DisplayName("a packet that leads with a plain int is never peeked: a VarInt read would get it wrong")
    void statusIsNotPeeked() {
        Buf buf = written(new WrapperPlayServerEntityStatus(1_234_567, 2));

        assertFalse(PacketHooks.leadsWithEntityId(PacketType.Play.Server.ENTITY_STATUS));
        assertFalse(PacketHooks.leadsWithEntityId(PacketType.Play.Server.ENTITY_SOUND_EFFECT));
        assertNotEquals(1_234_567, PacketHooks.peekVarInt(buf));
    }

    @Test
    @DisplayName("an equal line is flattened once; a different one is flattened again")
    void flattenCache() {
        Component line = Component.text("hello ", NamedTextColor.GOLD).append(Component.text("world"));
        String first = PacketHooks.flatten(line);
        // An equal component decoded separately, the way each receiver's is.
        String again = PacketHooks.flatten(Component.text("hello ", NamedTextColor.GOLD).append(Component.text("world")));

        assertEquals("hello world", first);
        assertSame(first, again);
        assertEquals("other", PacketHooks.flatten(Component.text("other")));
        assertEquals("hello world", PacketHooks.flatten(line));
    }

    private static Buf written(PacketWrapper<?> packet) {
        Buf buf = new Buf();
        packet.setBuffer(buf);
        packet.write();
        return buf;
    }

    /** A growable byte buffer with Netty's two indices, standing in for a ByteBuf. */
    private static final class Buf {
        ByteBuffer data = ByteBuffer.allocate(4096);
        int reader;
        int writer;
    }

    private static Object operate(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
        Buf b = (Buf) args[0];
        ByteBuffer d = b.data;
        switch (method.getName()) {
            case "readerIndex":
                if (args.length == 1) return b.reader;
                b.reader = (int) args[1];
                return b;
            case "writerIndex":
                if (args.length == 1) return b.writer;
                b.writer = (int) args[1];
                return b;
            case "readableBytes": return b.writer - b.reader;
            case "isReadable": return b.writer > b.reader;
            case "readByte": return d.get(b.reader++);
            case "readShort": { short v = d.getShort(b.reader); b.reader += 2; return v; }
            case "readInt": { int v = d.getInt(b.reader); b.reader += 4; return v; }
            case "readLong": { long v = d.getLong(b.reader); b.reader += 8; return v; }
            case "writeByte": d.put(b.writer++, (byte) (int) args[1]); return null;
            case "writeShort": d.putShort(b.writer, (short) (int) args[1]); b.writer += 2; return null;
            case "writeMedium": {
                int v = (int) args[1];
                d.put(b.writer, (byte) (v >>> 16)).put(b.writer + 1, (byte) (v >>> 8)).put(b.writer + 2, (byte) v);
                b.writer += 3;
                return null;
            }
            case "writeInt": d.putInt(b.writer, (int) args[1]); b.writer += 4; return null;
            case "writeLong": d.putLong(b.writer, (long) args[1]); b.writer += 8; return null;
            case "writeBytes":
                if (args[1] instanceof byte[] bytes) {
                    d.put(b.writer, bytes);
                    b.writer += bytes.length;
                    return b;
                }
                break;
            default:
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
        }
        throw new UnsupportedOperationException(method.getName());
    }

    /** Just enough PacketEvents for wrappers to write and ByteBufHelper to read. */
    private static final class FakeApi extends PacketEventsAPI<Object> {
        private final ByteBufOperator operator = (ByteBufOperator) Proxy.newProxyInstance(
                PacketHooksTest.class.getClassLoader(), new Class<?>[]{ByteBufOperator.class},
                PacketHooksTest::operate);
        private final NettyManager netty = (NettyManager) Proxy.newProxyInstance(
                PacketHooksTest.class.getClassLoader(), new Class<?>[]{NettyManager.class},
                (proxy, method, args) -> method.getName().equals("getByteBufOperator") ? operator : null);

        @Override public boolean isLoaded() { return true; }
        @Override public void init() { }
        @Override public boolean isInitialized() { return true; }
        @Override public boolean isTerminated() { return false; }
        @Override public Object getPlugin() { return null; }
        @Override public ServerManager getServerManager() { return () -> ServerVersion.V_1_21_4; }
        @Override public ProtocolManager getProtocolManager() { return null; }
        @Override public PlayerManager getPlayerManager() { return null; }
        @Override public NettyManager getNettyManager() { return netty; }
        @Override public ChannelInjector getInjector() { return null; }
    }
}
