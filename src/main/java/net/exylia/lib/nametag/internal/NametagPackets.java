package net.exylia.lib.nametag.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import net.exylia.lib.nametag.NametagStyle;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Writes teams and entity flags to a client.
 *
 * <p>The only class in the module that names PacketEvents types, so a server
 * without it never loads this one.
 *
 * <p>A team sent this way does not exist on the server: it is not on any
 * scoreboard, it survives no restart, and two viewers can be sent different
 * teams with the same name. That is the point — the same player is red to one
 * viewer and green to another with no server-side state to keep in step.
 */
final class NametagPackets extends PacketListenerAbstract implements NametagSink {

    /** The glowing bit of an entity's shared flags. */
    private static final byte GLOWING = 0x40;

    /** Where the shared flags live in an entity's metadata. */
    private static final int FLAGS_INDEX = 0;

    private final State state;

    private NametagPackets(State state) {
        // Lowest priority: this only ever adds a bit to a packet another plugin
        // may still want to rewrite, and reading last means reading the truth.
        super(PacketListenerPriority.LOWEST);
        this.state = state;
    }

    /** Returns whether PacketEvents is loaded and ready to send. */
    static boolean ready() {
        try {
            return PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Starts listening, or returns {@code null} when PacketEvents is absent. */
    static NametagSink install(State state) {
        if (!ready()) {
            return null;
        }
        NametagPackets packets = new NametagPackets(state);
        PacketEvents.getAPI().getEventManager().registerListener(packets);
        return packets;
    }

    @Override
    public void close() {
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(this);
        } catch (Throwable ignored) {
            // Shutting down after PacketEvents already did is not a failure.
        }
    }

    /**
     * Adds the glowing bit on its way to a viewer who should see it.
     *
     * <p>Glow cannot be sent once and left: the server re-sends an entity's
     * flags whenever anything about it changes, and each of those would put the
     * outline out again. Rewriting them as they pass is what makes it stick.
     */
    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.ENTITY_METADATA) {
            return;
        }
        if (!(event.getPlayer() instanceof Player viewer)) {
            return;
        }
        if (!state.anyGlowing(viewer.getUniqueId())) {
            return;
        }

        WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(event);
        UUID targetId = state.playerOf(packet.getEntityId());
        if (targetId == null || !state.isGlowing(viewer.getUniqueId(), targetId)) {
            return;
        }

        List<EntityData<?>> metadata = packet.getEntityMetadata();
        for (int i = 0; i < metadata.size(); i++) {
            EntityData<?> data = metadata.get(i);
            if (data.getIndex() != FLAGS_INDEX || !(data.getValue() instanceof Byte flags)) {
                continue;
            }
            if ((flags & GLOWING) != 0) {
                return;
            }
            metadata.set(i, new EntityData<>(FLAGS_INDEX, EntityDataTypes.BYTE,
                    (byte) (flags | GLOWING)));
            event.markForReEncode(true);
            return;
        }
    }

    @Override
    public void createTeam(Player viewer, String name, NametagStyle style,
                           Collection<String> members) {
        User user = userOf(viewer);
        if (user == null) {
            return;
        }
        user.sendPacket(new WrapperPlayServerTeams(
                name,
                WrapperPlayServerTeams.TeamMode.CREATE,
                info(style),
                members));
    }

    @Override
    public void addToTeam(Player viewer, String name, Collection<String> members) {
        User user = userOf(viewer);
        if (user == null) {
            return;
        }
        user.sendPacket(new WrapperPlayServerTeams(
                name,
                WrapperPlayServerTeams.TeamMode.ADD_ENTITIES,
                (WrapperPlayServerTeams.ScoreBoardTeamInfo) null,
                members));
    }

    @Override
    public void removeFromTeam(Player viewer, String name, Collection<String> members) {
        User user = userOf(viewer);
        if (user == null) {
            return;
        }
        user.sendPacket(new WrapperPlayServerTeams(
                name,
                WrapperPlayServerTeams.TeamMode.REMOVE_ENTITIES,
                (WrapperPlayServerTeams.ScoreBoardTeamInfo) null,
                members));
    }

    @Override
    public void removeTeam(Player viewer, String name) {
        User user = userOf(viewer);
        if (user == null) {
            return;
        }
        user.sendPacket(new WrapperPlayServerTeams(
                name,
                WrapperPlayServerTeams.TeamMode.REMOVE,
                (WrapperPlayServerTeams.ScoreBoardTeamInfo) null,
                List.of()));
    }

    /**
     * Re-sends a player's own flags, so a glow appears or disappears now.
     *
     * <p>Without this a change waits for the next thing that happens to the
     * player — a sprint, a hit, an item swap — which from a viewer's side looks
     * like the plugin did nothing.
     */
    @Override
    public void refreshFlags(Player viewer, Player target) {
        User user = userOf(viewer);
        if (user == null) {
            return;
        }
        // The value is irrelevant: the listener above rewrites it on the way
        // out, and a viewer who should no longer see the glow gets the truth.
        byte flags = 0;
        if (target.isSneaking()) {
            flags |= 0x02;
        }
        if (target.isSprinting()) {
            flags |= 0x08;
        }
        if (target.isSwimming()) {
            flags |= 0x10;
        }
        if (target.isInvisible()) {
            flags |= 0x20;
        }
        if (target.isGlowing()) {
            flags |= GLOWING;
        }
        user.sendPacket(new WrapperPlayServerEntityMetadata(
                target.getEntityId(),
                List.of(new EntityData<>(FLAGS_INDEX, EntityDataTypes.BYTE, flags))));
    }

    private static WrapperPlayServerTeams.ScoreBoardTeamInfo info(NametagStyle style) {
        // 0x01 friendly fire, 0x02 see friendly invisibles. Friendly fire stays
        // on: a team here is a look, not an alliance, and turning it off would
        // stop two players from hitting each other because of their colour.
        byte flags = (byte) (0x01 | (style.seeInvisible() ? 0x02 : 0x00));
        return new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                Component.empty(),
                Component.empty(),
                Component.empty(),
                WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
                style.collides()
                        ? WrapperPlayServerTeams.CollisionRule.ALWAYS
                        : WrapperPlayServerTeams.CollisionRule.NEVER,
                style.colour(),
                WrapperPlayServerTeams.OptionData.fromValue(flags));
    }

    private static User userOf(Player player) {
        try {
            return PacketEvents.getAPI().getPlayerManager().getUser(player);
        } catch (Throwable ignored) {
            // A player who left between the decision and the send.
            return null;
        }
    }
}
