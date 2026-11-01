package net.exylia.lib.packet.internal;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockAction;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The packet side of {@link Masks}: on their way to a player who is not near, the masked blocks are
 * rewritten in every packet that carries them, and their block entities, block actions and world
 * events are dropped. Loaded only from {@link PacketHooks}, so it may name PacketEvents.
 */
final class MaskPackets {

    private MaskPackets() {
    }

    static boolean carries(PacketTypeCommon type) {
        return type == PacketType.Play.Server.CHUNK_DATA || type == PacketType.Play.Server.BLOCK_CHANGE
                || type == PacketType.Play.Server.MULTI_BLOCK_CHANGE || type == PacketType.Play.Server.BLOCK_ENTITY_DATA
                || type == PacketType.Play.Server.BLOCK_ACTION || type == PacketType.Play.Server.EFFECT;
    }

    /** Rewrites the packet for this viewer; {@code true} when it must not reach them at all. */
    static boolean apply(PacketSendEvent event, PacketTypeCommon type, Player viewer) {
        UUID id = viewer.getUniqueId();
        UUID world = viewer.getWorld().getUID();
        if (type == PacketType.Play.Server.CHUNK_DATA) {
            chunk(event, viewer, id, world);
            return false;
        }
        if (type == PacketType.Play.Server.BLOCK_CHANGE) {
            WrapperPlayServerBlockChange change = new WrapperPlayServerBlockChange(event);
            Vector3i at = change.getBlockPosition();
            Masks.Mask mask = Masks.hiddenAt(id, world, at.getX(), at.getY(), at.getZ());
            if (mask != null && change.getBlockId() != PacketHooks.globalId(mask.shown())) {
                change.setBlockID(PacketHooks.globalId(mask.shown()));
                event.markForReEncode(true);
            }
            return false;
        }
        if (type == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            WrapperPlayServerMultiBlockChange change = new WrapperPlayServerMultiBlockChange(event);
            Vector3i section = change.getChunkPosition();
            if (Masks.chunk(world, section.getX(), section.getZ()) == null) return false;
            boolean changed = false;
            for (WrapperPlayServerMultiBlockChange.EncodedBlock block : change.getBlocks()) {
                int x = (section.getX() << 4) | (block.getX() & 15);
                int y = (section.getY() << 4) | (block.getY() & 15);
                int z = (section.getZ() << 4) | (block.getZ() & 15);
                Masks.Mask mask = Masks.hiddenAt(id, world, x, y, z);
                if (mask != null && block.getBlockId() != PacketHooks.globalId(mask.shown())) {
                    block.setBlockId(PacketHooks.globalId(mask.shown()));
                    changed = true;
                }
            }
            if (changed) event.markForReEncode(true);
            return false;
        }
        Vector3i at;
        if (type == PacketType.Play.Server.BLOCK_ENTITY_DATA) {
            at = new WrapperPlayServerBlockEntityData(event).getPosition();
        } else if (type == PacketType.Play.Server.BLOCK_ACTION) {
            at = new WrapperPlayServerBlockAction(event).getBlockPosition();
        } else {
            at = new WrapperPlayServerEffect(event).getPosition();
        }
        return Masks.hiddenAt(id, world, at.getX(), at.getY(), at.getZ()) != null;
    }

    /**
     * Writes the masks into a whole chunk before it leaves and takes their block entities out of it.
     * Only a chunk holding a mask this viewer is not near is decoded at all.
     */
    private static void chunk(PacketSendEvent event, Player viewer, UUID id, UUID world) {
        int chunkX;
        int chunkZ;
        if (event.getLastUsedWrapper() != null) {
            Column column = new WrapperPlayServerChunkData(event).getColumn();
            chunkX = column.getX();
            chunkZ = column.getZ();
        } else {
            // The chunk's two coordinates lead the packet as plain ints: read without decoding the chunk.
            Object buffer = event.getByteBuf();
            int index = ByteBufHelper.readerIndex(buffer);
            try {
                chunkX = ByteBufHelper.readInt(buffer);
                chunkZ = ByteBufHelper.readInt(buffer);
            } finally {
                ByteBufHelper.readerIndex(buffer, index);
            }
        }
        Map<Long, Masks.Mask> here = Masks.chunk(world, chunkX, chunkZ);
        if (here == null) return;
        Set<Long> revealed = Masks.revealedOf(id);
        List<Map.Entry<Long, Masks.Mask>> hidden = new ArrayList<>();
        for (Map.Entry<Long, Masks.Mask> entry : here.entrySet()) {
            if (revealed == null || !revealed.contains(entry.getKey())) hidden.add(entry);
        }
        if (hidden.isEmpty()) return;

        WrapperPlayServerChunkData data = new WrapperPlayServerChunkData(event);
        Column column = data.getColumn();
        BaseChunk[] sections = column.getChunks();
        int minY = event.getUser().getMinWorldHeight();
        for (Map.Entry<Long, Masks.Mask> entry : hidden) {
            long packed = entry.getKey();
            int x = (int) (packed >> 38);
            int y = (int) (packed << 52 >> 52);
            int z = (int) (packed << 26 >> 38);
            int index = (y - minY) >> 4;
            if (index < 0 || index >= sections.length || sections[index] == null) continue;
            sections[index].set(x & 15, (y - minY) & 15, z & 15, PacketHooks.globalId(entry.getValue().shown()));
        }
        TileEntity[] tiles = column.getTileEntities();
        List<TileEntity> kept = new ArrayList<>(tiles.length);
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (TileEntity tile : tiles) {
            long packed = PacketRuntime.pack(baseX + tile.getX(), tile.getY(), baseZ + tile.getZ());
            Masks.Mask mask = here.get(packed);
            if (mask == null || revealed != null && revealed.contains(packed)) kept.add(tile);
        }
        if (kept.size() != tiles.length) {
            TileEntity[] left = kept.toArray(new TileEntity[0]);
            data.setColumn(column.hasHeightMaps()
                    ? new Column(chunkX, chunkZ, column.isFullChunk(), sections, left, column.getHeightmaps())
                    : new Column(chunkX, chunkZ, column.isFullChunk(), sections, left));
        }
        event.markForReEncode(true);
    }
}
