package net.exylia.lib.packet.internal;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.GlobalPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.ListPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.Palette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.SingletonPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.BaseStorage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;

/**
 * The packet side of {@link Palettes}: every block state on its way to a viewer with a palette goes
 * through their table. Chunks, block changes, falling blocks and block-break effects. Loaded only from
 * {@link PacketHooks}, so it may name PacketEvents.
 */
final class PalettePackets {

    /** World event: a block broke, its data the state id (particles and sound). */
    private static final int BLOCK_BREAK = 2001;
    private static final int SECTION_BLOCKS = 4096;

    private PalettePackets() {
    }

    static boolean carries(PacketTypeCommon type) {
        return type == PacketType.Play.Server.CHUNK_DATA || type == PacketType.Play.Server.BLOCK_CHANGE
                || type == PacketType.Play.Server.MULTI_BLOCK_CHANGE || type == PacketType.Play.Server.SPAWN_ENTITY
                || type == PacketType.Play.Server.EFFECT;
    }

    static void apply(PacketSendEvent event, PacketTypeCommon type, PaletteTable table) {
        boolean changed = false;
        if (type == PacketType.Play.Server.CHUNK_DATA) {
            for (BaseChunk section : new WrapperPlayServerChunkData(event).getColumn().getChunks()) {
                // Every server this library runs on (1.21.1+) sends 1.18-format sections.
                if (section instanceof Chunk_v1_18 modern) changed |= section(modern.getChunkData(), table);
            }
        } else if (type == PacketType.Play.Server.BLOCK_CHANGE) {
            WrapperPlayServerBlockChange change = new WrapperPlayServerBlockChange(event);
            int shown = table.remap(change.getBlockId());
            if (shown != change.getBlockId()) {
                change.setBlockID(shown);
                changed = true;
            }
        } else if (type == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            for (WrapperPlayServerMultiBlockChange.EncodedBlock block : new WrapperPlayServerMultiBlockChange(event).getBlocks()) {
                int shown = table.remap(block.getBlockId());
                if (shown != block.getBlockId()) {
                    block.setBlockId(shown);
                    changed = true;
                }
            }
        } else if (type == PacketType.Play.Server.SPAWN_ENTITY) {
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(event);
            if (spawn.getEntityType() == EntityTypes.FALLING_BLOCK) {
                int shown = table.remap(spawn.getData());
                if (shown != spawn.getData()) {
                    spawn.setData(shown);
                    changed = true;
                }
            }
        } else {
            WrapperPlayServerEffect effect = new WrapperPlayServerEffect(event);
            if (effect.getType() == BLOCK_BREAK) {
                int shown = table.remap(effect.getData());
                if (shown != effect.getData()) {
                    effect.setData(shown);
                    changed = true;
                }
            }
        }
        if (changed) event.markForReEncode(true);
    }

    /**
     * Rewrites one section's block states; {@code true} when anything changed. A palette section only
     * has its palette entries swapped (two entries may end up the same state, which the client reads
     * fine); a section of global ids has every block swapped.
     */
    static boolean section(DataPalette data, PaletteTable table) {
        Palette palette = data.palette;
        if (palette instanceof SingletonPalette) {
            int state = palette.idToState(0);
            int shown = table.remap(state);
            if (shown == state) return false;
            data.palette = new SingletonPalette(shown);
            return true;
        }
        if (palette instanceof GlobalPalette) {
            BaseStorage storage = data.storage;
            boolean changed = false;
            for (int i = 0; i < SECTION_BLOCKS; i++) {
                int state = storage.get(i);
                int shown = table.remap(state);
                if (shown != state) {
                    storage.set(i, shown);
                    changed = true;
                }
            }
            return changed;
        }
        int size = palette.size();
        int[] entries = null;
        for (int i = 0; i < size; i++) {
            int state = palette.idToState(i);
            int shown = table.remap(state);
            if (shown == state && entries == null) continue;
            if (entries == null) {
                entries = new int[size];
                for (int j = 0; j < i; j++) entries[j] = palette.idToState(j);
            }
            entries[i] = shown;
        }
        if (entries == null) return false;
        data.palette = new ListPalette(palette.getBits(), entries);
        return true;
    }
}
