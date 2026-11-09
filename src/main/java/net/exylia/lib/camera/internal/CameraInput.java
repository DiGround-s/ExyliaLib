package net.exylia.lib.camera.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import net.exylia.lib.packet.internal.EntityClicks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a viewer presses while looking through a held shot.
 *
 * <p>Several signals, because a client looking through another entity and drawn as a spectator is not sure to
 * send any one of them: a click on the box round the camera, the swing of a click that missed it, a sneak (the
 * action packet, or the input packet from 1.21.2) and a jump.
 */
final class CameraInput extends PacketListenerAbstract {

    private static final int SHIFT = 1;
    private static final int JUMP = 2;

    private static CameraInput installed;

    /** What each viewer held in their last input packet: a press is a key going down. */
    private final Map<UUID, Integer> held = new ConcurrentHashMap<>();

    private CameraInput() {
        super(PacketListenerPriority.LOW);
    }

    static synchronized void install() {
        if (installed != null || !CameraPackets.ready()) {
            return;
        }
        installed = new CameraInput();
        PacketEvents.getAPI().getEventManager().registerListener(installed);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getUser() == null || event.getUser().getUUID() == null) {
            return;
        }
        UUID who = event.getUser().getUUID();
        LiveCamera camera = CameraRuntime.filming(who);
        if (camera == null) {
            return;
        }
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.PLAYER_INPUT) {
            WrapperPlayClientPlayerInput input = new WrapperPlayClientPlayerInput(event);
            int now = (input.isShift() ? SHIFT : 0) | (input.isJump() ? JUMP : 0);
            Integer before = held.put(who, now);
            if ((now & ~(before == null ? 0 : before)) != 0) {
                camera.pressed(who);
            }
        } else if (type == PacketType.Play.Client.ENTITY_ACTION) {
            if (new WrapperPlayClientEntityAction(event).getAction() == WrapperPlayClientEntityAction.Action.START_SNEAKING) {
                camera.pressed(who);
            }
        } else if (type == PacketType.Play.Client.ANIMATION) {
            camera.pressed(who);
        } else {
            EntityClicks.Press click = EntityClicks.readSpectating(event);
            if (click != null && camera.catches(click.entityId())) {
                event.setCancelled(true);
                if (click.counts()) {
                    camera.pressed(who);
                }
            }
        }
    }
}
