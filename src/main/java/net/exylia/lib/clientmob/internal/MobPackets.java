package net.exylia.lib.clientmob.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.exylia.lib.interaction.Click;
import net.exylia.lib.packet.internal.EntityClicks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Mob packets through PacketEvents. Loaded only when PacketEvents is installed. */
final class MobPackets implements MobSink {

    static final MobPackets INSTANCE = new MobPackets();

    // Entity metadata indices (1.21.1 to 1.21.4).
    private static final int FLAGS = 0;
    private static final int NO_GRAVITY = 5;
    private static final int POSE = 6;
    private static final int CUSTOM_NAME = 2;
    private static final int NAME_VISIBLE = 3;
    private static final int BABY = 16;
    /** Ageable mobs gained an age-locked flag after the baby flag in 26.1, which moves every later index one up. */
    private static final int AGEABLE_SHIFT = ageLocked() ? 1 : 0;
    /** Tameable animals (wolf, cat): sitting is bit 0 of this byte. */
    private static final int TAMEABLE_FLAGS = 17 + AGEABLE_SHIFT;
    private static final int FOX_FLAGS = 18 + AGEABLE_SHIFT;
    private static final byte FLAG_CROUCHING = 0x02;
    private static final byte FLAG_GLOWING = 0x40;

    /** Status 4: the attack animation of these types; every other living mob gets the arm swing. */
    private static final java.util.Set<EntityType> STATUS_ATTACK = java.util.Set.of(
            EntityType.IRON_GOLEM, EntityType.RAVAGER, EntityType.HOGLIN, EntityType.ZOGLIN, EntityType.WARDEN);

    private MobPackets() {
    }

    private static boolean ageLocked() {
        try {
            Class.forName("net.minecraft.world.entity.AgeableMob").getDeclaredField("AGE_LOCKED");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean ready() {
        try {
            return PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded()
                    && net.exylia.lib.internal.PacketEventsVersion.agrees();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static int newEntityId() {
        return SpigotReflectionUtil.generateEntityId();
    }

    private static String team(MobImpl mob) {
        return "cm" + Integer.toHexString(mob.id);
    }

    @Override
    public void spawn(MobImpl mob) {
        send(mob, new WrapperPlayServerSpawnEntity(mob.id, Optional.of(mob.uuid),
                SpigotConversionUtil.fromBukkitEntityType(mob.type),
                new Vector3d(mob.x(), mob.y(), mob.z()), mob.pitch(), mob.yaw(), mob.yaw(), 0, Optional.empty()));
        List<EntityData<?>> data = new ArrayList<>(stateData(mob));
        data.add(new EntityData<>(NO_GRAVITY, EntityDataTypes.BOOLEAN, true));
        send(mob, new WrapperPlayServerEntityMetadata(mob.id, data));
        send(mob, new WrapperPlayServerEntityHeadLook(mob.id, mob.yaw()));
        if (mob.glowColor() != null) {
            createTeam(mob);
        }
    }

    @Override
    public void despawn(MobImpl mob) {
        send(mob, new WrapperPlayServerDestroyEntities(mob.id));
        if (mob.glowColor() != null) {
            removeTeam(mob);
        }
    }

    @Override
    public void move(MobImpl mob, double dx, double dy, double dz) {
        // Client mobs walk on the ground: off it, chickens and parrots flap their wings as if they flew.
        send(mob, new WrapperPlayServerEntityRelativeMoveAndRotation(mob.id, dx, dy, dz,
                mob.yaw(), mob.pitch(), true));
        send(mob, new WrapperPlayServerEntityHeadLook(mob.id, mob.yaw()));
    }

    @Override
    public void teleport(MobImpl mob) {
        send(mob, new WrapperPlayServerEntityTeleport(mob.id, new Vector3d(mob.x(), mob.y(), mob.z()),
                mob.yaw(), mob.pitch(), true));
        // The head is its own field and a teleport does not carry it.
        send(mob, new WrapperPlayServerEntityHeadLook(mob.id, mob.yaw()));
    }

    @Override
    public void rotate(MobImpl mob) {
        send(mob, new WrapperPlayServerEntityRotation(mob.id, mob.yaw(), mob.pitch(), true));
        send(mob, new WrapperPlayServerEntityHeadLook(mob.id, mob.yaw()));
    }

    @Override
    public void swing(MobImpl mob) {
        if (STATUS_ATTACK.contains(mob.type)) {
            send(mob, new WrapperPlayServerEntityStatus(mob.id, 4));
        } else if (armSwing) {
            try {
                send(mob, new WrapperPlayServerEntityAnimation(mob.id,
                        WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM));
            } catch (IllegalStateException unsupported) {
                // PacketEvents has no id for the arm swing on some protocols (26.3): skip it from now on.
                armSwing = false;
            }
        }
    }

    private static volatile boolean armSwing = true;

    @Override
    public void status(MobImpl mob, byte status) {
        send(mob, new WrapperPlayServerEntityStatus(mob.id, status));
    }

    @Override
    public void state(MobImpl mob) {
        send(mob, new WrapperPlayServerEntityMetadata(mob.id, stateData(mob)));
    }

    @Override
    public void glow(MobImpl mob) {
        List<EntityData<?>> data = new ArrayList<>(stateData(mob));
        send(mob, new WrapperPlayServerEntityMetadata(mob.id, data));
        removeTeam(mob);
        if (mob.glowColor() != null) {
            createTeam(mob);
        }
    }

    /** Flags, pose, name and age as the mob is now. Always complete, so a cleared value is cleared. */
    private static List<EntityData<?>> stateData(MobImpl mob) {
        List<EntityData<?>> data = new ArrayList<>(6);
        boolean sitting = mob.isSitting();
        boolean tameable = mob.type == EntityType.WOLF || mob.type == EntityType.CAT;
        boolean fox = mob.type == EntityType.FOX;
        boolean camel = mob.type == EntityType.CAMEL;
        boolean crouch = sitting && !tameable && !fox && !camel;
        byte flags = 0;
        if (mob.glowColor() != null) flags |= FLAG_GLOWING;
        if (crouch) flags |= FLAG_CROUCHING;
        data.add(new EntityData<>(FLAGS, EntityDataTypes.BYTE, flags));
        data.add(new EntityData<>(POSE, EntityDataTypes.ENTITY_POSE,
                crouch ? EntityPose.CROUCHING : sitting && camel ? EntityPose.SITTING : EntityPose.STANDING));
        Object name = mob.nameComponent();
        data.add(new EntityData<>(CUSTOM_NAME, EntityDataTypes.OPTIONAL_ADV_COMPONENT,
                Optional.ofNullable((Component) name)));
        data.add(new EntityData<>(NAME_VISIBLE, EntityDataTypes.BOOLEAN, name != null));
        if (hasBabyFlag(mob.type)) {
            data.add(new EntityData<>(BABY, EntityDataTypes.BOOLEAN, mob.isBaby()));
        }
        if (tameable) {
            data.add(new EntityData<>(TAMEABLE_FLAGS, EntityDataTypes.BYTE, (byte) (sitting ? 1 : 0)));
        } else if (fox) {
            data.add(new EntityData<>(FOX_FLAGS, EntityDataTypes.BYTE, (byte) (sitting ? 1 : 0)));
        }
        return data;
    }

    /** Animals and villagers share the baby flag at index 16, and so do zombies. */
    private static boolean hasBabyFlag(EntityType type) {
        Class<?> cls = type.getEntityClass();
        return cls != null && (org.bukkit.entity.Ageable.class.isAssignableFrom(cls)
                || org.bukkit.entity.Zombie.class.isAssignableFrom(cls));
    }

    private static void createTeam(MobImpl mob) {
        NamedTextColor color = NamedTextColor.nearestTo(TextColor.color(mob.glowColor()));
        send(mob, new WrapperPlayServerTeams(team(mob), WrapperPlayServerTeams.TeamMode.CREATE,
                new WrapperPlayServerTeams.ScoreBoardTeamInfo(Component.empty(), Component.empty(),
                        Component.empty(), WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
                        WrapperPlayServerTeams.CollisionRule.ALWAYS, color,
                        WrapperPlayServerTeams.OptionData.fromValue((byte) 0)),
                List.of(mob.uuid.toString())));
    }

    private static void removeTeam(MobImpl mob) {
        send(mob, new WrapperPlayServerTeams(team(mob), WrapperPlayServerTeams.TeamMode.REMOVE,
                (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, List.<String>of()));
    }

    /** Hears clicks on mob ids, kept from the server, and respawns, after which the client has dropped every mob. */
    static Object listen() {
        PacketListenerAbstract listener = new PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                EntityClicks.Press press = EntityClicks.read(event);
                if (press == null || !MobRuntime.owns(press.entityId())
                        || !(event.getPlayer() instanceof Player player)) {
                    return;
                }
                event.setCancelled(true);
                if (press.counts()) {
                    boolean sneaking = press.sneaking() != null ? press.sneaking() : player.isSneaking();
                    MobRuntime.clicked(player, press.entityId(), Click.of(press.attack(), sneaking));
                }
            }

            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (event.getPacketType() == PacketType.Play.Server.RESPAWN
                        && event.getPlayer() instanceof Player player) {
                    event.getPostTasks().add(() -> MobRuntime.respawned(player));
                }
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(listener);
        return listener;
    }

    static void stopListening(Object listener) {
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener((PacketListenerAbstract) listener);
        } catch (Throwable ignored) {
            // Shutting down after PacketEvents already did is not a failure.
        }
    }

    /** A raw status for one viewer, on any entity id: a real mob's attack animation. */
    static void statusTo(Player viewer, int entityId, byte status) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, new WrapperPlayServerEntityStatus(entityId, status));
    }

    /** A real mob's outline colour for one viewer, as a team of its own; {@code null} removes it. */
    static void teamTo(Player viewer, java.util.UUID entity, Integer rgb) {
        String team = "rm" + Long.toHexString(entity.getMostSignificantBits()).substring(0, 8);
        PacketWrapper<?> packet = rgb == null
                ? new WrapperPlayServerTeams(team, WrapperPlayServerTeams.TeamMode.REMOVE,
                        (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, List.<String>of())
                : new WrapperPlayServerTeams(team, WrapperPlayServerTeams.TeamMode.CREATE,
                        new WrapperPlayServerTeams.ScoreBoardTeamInfo(Component.empty(), Component.empty(),
                                Component.empty(), WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
                                WrapperPlayServerTeams.CollisionRule.ALWAYS,
                                NamedTextColor.nearestTo(TextColor.color(rgb)),
                                WrapperPlayServerTeams.OptionData.fromValue((byte) 0)),
                        List.of(entity.toString()));
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }

    private static void send(MobImpl mob, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(mob.viewer, packet);
    }
}
