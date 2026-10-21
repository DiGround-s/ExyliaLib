package net.exylia.lib.packet.internal;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import org.jetbrains.annotations.ApiStatus;

import java.util.function.ToIntFunction;

/**
 * The entity an outgoing packet is about, read without decoding it.
 *
 * <p>Every listener that rewrites entity packets asks first whether the entity
 * is one of its own, and almost never is. A wrapper decodes the whole packet to
 * answer that — every metadata entry, every equipment item with its components —
 * on a Netty thread, for every entity packet to every player. The packets that
 * lead with the entity id as a VarInt have it read straight off the buffer, and
 * the wrapper is built only for the few that turn out to matter.
 *
 * <p>Only classes that have already decided PacketEvents is present may call
 * this.
 */
@ApiStatus.Internal
public final class PacketPeek {

    private PacketPeek() {
        throw new AssertionError("No instances.");
    }

    /**
     * The entity id a packet leads with.
     *
     * @param event   the packet on its way out
     * @param decoded how to read the id from a wrapper, for a packet that cannot
     *                be peeked; a non-capturing lambda, so asking costs nothing
     * @return the entity id
     */
    public static int entityId(PacketSendEvent event, ToIntFunction<PacketSendEvent> decoded) {
        return leadsWithEntityId(event.getPacketType()) ? leadingVarInt(event, decoded) : decoded.applyAsInt(event);
    }

    /**
     * The VarInt a packet leads with, such as the container id of
     * {@code OPEN_WINDOW} (a VarInt from 1.14 on, read before the title in
     * PacketEvents 2.13), whose wrapper would otherwise decode the title.
     *
     * @param event   the packet on its way out, which must lead with a VarInt
     * @param decoded how to read it from a wrapper an earlier listener built
     * @return the value
     */
    public static int leadingVarInt(PacketSendEvent event, ToIntFunction<PacketSendEvent> decoded) {
        // A wrapper an earlier listener decoded is what a new wrapper copies,
        // edits included, and its edits are not in the buffer until the end:
        // then only the wrapper gives the same answer.
        if (event.getLastUsedWrapper() != null) {
            return decoded.applyAsInt(event);
        }
        return peekVarInt(event.getByteBuf());
    }

    /**
     * Whether a packet's first field is the entity id as a VarInt.
     *
     * <p>Checked against each wrapper's {@code read()} in PacketEvents 2.13.
     * Left out on purpose: the entity status, which leads with a plain int, and
     * the entity sound, which leads with the sound.
     */
    static boolean leadsWithEntityId(PacketTypeCommon type) {
        return type == PacketType.Play.Server.ENTITY_METADATA
                || type == PacketType.Play.Server.ENTITY_EQUIPMENT
                || type == PacketType.Play.Server.SPAWN_ENTITY
                || type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE
                || type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION
                || type == PacketType.Play.Server.ENTITY_ROTATION
                || type == PacketType.Play.Server.ENTITY_HEAD_LOOK
                || type == PacketType.Play.Server.ENTITY_TELEPORT
                || type == PacketType.Play.Server.ENTITY_POSITION_SYNC
                || type == PacketType.Play.Server.ENTITY_VELOCITY
                || type == PacketType.Play.Server.ENTITY_ANIMATION
                || type == PacketType.Play.Server.ENTITY_EFFECT
                || type == PacketType.Play.Server.DAMAGE_EVENT
                || type == PacketType.Play.Server.HURT_ANIMATION;
    }

    /**
     * Reads the VarInt at the buffer's reader index and leaves the index where
     * it was. PacketEvents hands each listener the buffer just past the packet
     * id, so every listener after this one reads the packet untouched.
     */
    static int peekVarInt(Object buffer) {
        int at = ByteBufHelper.readerIndex(buffer);
        try {
            return ByteBufHelper.readVarInt(buffer);
        } finally {
            ByteBufHelper.readerIndex(buffer, at);
        }
    }
}
