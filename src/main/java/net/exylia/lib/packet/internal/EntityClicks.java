package net.exylia.lib.packet.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Reads a click on an entity the same way on every version.
 *
 * <p>Up to 1.21.11 one packet carried every click: an attack, and a right-click sent
 * twice, as INTERACT_AT and then INTERACT. From 26.1 an attack is a packet of its own,
 * and a right-click is sent once, as INTERACT_AT. Reading only INTERACT there hears
 * no click at all.
 */
public final class EntityClicks {

    /**
     * One click.
     *
     * @param entityId what was clicked
     * @param attack   a left-click
     * @param hand     the hand it was made with; an attack is always the main hand
     * @param sneaking whether the client said it was sneaking, or {@code null} when the
     *                 packet does not say
     * @param twin     the duplicate half of a press that is also sent whole; refuse it like
     *                 the press, but act on it only once
     */
    public record Press(int entityId, boolean attack, InteractionHand hand,
                        @Nullable Boolean sneaking, boolean twin) {

        /** Whether this is the one packet of a press to act on: not the twin, and the main hand. */
        public boolean counts() {
            return !twin && hand == InteractionHand.MAIN_HAND;
        }
    }

    private EntityClicks() {
        throw new AssertionError("No instances.");
    }

    /** The click in a received packet, or {@code null} when it is not a click on an entity. */
    public static @Nullable Press read(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
            return new Press(new WrapperPlayClientAttack(event).getEntityId(), true,
                    InteractionHand.MAIN_HAND, null, false);
        }
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return null;
        }
        WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
        WrapperPlayClientInteractEntity.InteractAction action = packet.getAction();
        boolean attack = action == WrapperPlayClientInteractEntity.InteractAction.ATTACK;
        boolean twin = action == WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT && !oneInteract();
        return new Press(packet.getEntityId(), attack,
                attack ? InteractionHand.MAIN_HAND : packet.getHand(),
                packet.isSneaking().orElse(null), twin);
    }

    /** Whether a right-click arrives once, as INTERACT_AT: the server's format decides, not the client's. */
    private static boolean oneInteract() {
        return PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_26_1);
    }
}
