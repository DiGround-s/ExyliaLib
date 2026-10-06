package net.exylia.lib.replay.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleBlockStateData;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleType;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.sound.SoundCategory;
import com.github.retrooper.packetevents.protocol.sound.Sounds;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockBreakAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCollectItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntitySoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateHealth;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.exylia.lib.packet.internal.Broadcast;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every packet a playback sends, and the only class in the module that names
 * PacketEvents.
 *
 * <p>Everything goes out as packets, sounds and particles included, so a
 * playback never touches a Bukkit object from its own thread. That is what lets
 * one driver run every playback on Folia, where the viewers may be on any
 * region.
 *
 * <h2>Metadata indices</h2>
 * Written in the server's version and translated for older clients by
 * ViaVersion afterwards; see the NPC module for why the server's version is the
 * one that decides. The few used here have not moved between 1.20.5 and 1.21.11
 * except the skin layers, which are handled the way the NPC module handles them.
 * Every metadata write is guarded: an index that moved on a version nobody has
 * tested is a missing detail, not a disconnect.
 */
@ApiStatus.Internal
final class ReplayPackets {

    static final int FLAGS = 0;
    static final byte FLAG_ON_FIRE = 0x01;
    static final byte FLAG_CROUCHING = 0x02;
    static final byte FLAG_SPRINTING = 0x08;
    static final byte FLAG_SWIMMING = 0x10;
    static final byte FLAG_INVISIBLE = 0x20;
    static final byte FLAG_GLOWING = 0x40;
    static final byte FLAG_GLIDING = (byte) 0x80;

    private static final int CUSTOM_NAME = 2;
    private static final int NAME_VISIBLE = 3;
    private static final int NO_GRAVITY = 5;
    private static final int POSE = 6;
    private static final int CRYSTAL_BOTTOM = 9;
    private static final int ITEM = 8;
    private static final int HAND_STATES = 8;
    private static final int HEALTH = 9;
    private static final int BABY = 16;
    private static final int SKIN_LAYERS_AVATAR = 16;
    private static final int SKIN_LAYERS_LEGACY = 17;
    private static final byte ALL_LAYERS = 0x7F;

    /** Level event: a block broken, with its own sound and pieces. */
    private static final int BLOCK_BROKEN = 2001;

    /** Entity events, as the protocol numbers them. */
    static final byte STATUS_DEATH = 3;
    static final byte STATUS_SHIELD_BLOCK = 29;
    static final byte STATUS_SHIELD_BREAK = 30;
    static final byte STATUS_TOTEM = 35;

    private ReplayPackets() {
    }

    static boolean ready() {
        try {
            return PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static int newEntityId() {
        return SpigotReflectionUtil.generateEntityId();
    }

    private static boolean atLeast(ServerVersion version) {
        return PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(version);
    }

    // ---------------------------------------------------------------- bodies

    /**
     * Announces a player body's identity, unlisted: the entry carries the skin,
     * and being in the tab list is a separate flag nobody wants set.
     */
    static void announce(List<Player> viewers, UUID profile, String name,
                         @Nullable String texture, @Nullable String signature) {
        UserProfile user = new UserProfile(profile, name.length() > 16 ? name.substring(0, 16) : name);
        if (texture != null) {
            user.setTextureProperties(List.of(new TextureProperty("textures", texture, signature)));
        }
        send(viewers, new WrapperPlayServerPlayerInfoUpdate(
                EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED),
                new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                        user, false, 0, GameMode.SURVIVAL, Component.empty(), null)));
    }

    static void spawn(List<Player> viewers, int entityId, UUID uuid, EntityType type,
                      double x, double y, double z, float yaw, float pitch, float head, int data) {
        send(viewers, new WrapperPlayServerSpawnEntity(entityId, Optional.of(uuid), type,
                new Vector3d(x, y, z), pitch, yaw, head, data, Optional.empty()));
        send(viewers, new WrapperPlayServerEntityHeadLook(entityId, head));
    }

    static void skinLayers(List<Player> viewers, int entityId) {
        int index = atLeast(ServerVersion.V_1_21_11) ? SKIN_LAYERS_AVATAR : SKIN_LAYERS_LEGACY;
        metadata(viewers, entityId, List.of(new EntityData<>(index, EntityDataTypes.BYTE, ALL_LAYERS)));
    }

    static void destroy(List<Player> viewers, int entityId, @Nullable UUID profile) {
        send(viewers, new WrapperPlayServerDestroyEntities(entityId));
        if (profile != null) send(viewers, new WrapperPlayServerPlayerInfoRemove(profile));
    }

    /** A step the client draws smoothly, the same packet a walking player produces. */
    static void step(List<Player> viewers, int entityId, double dx, double dy, double dz,
                     float yaw, float pitch, boolean onGround) {
        send(viewers, new WrapperPlayServerEntityRelativeMoveAndRotation(
                entityId, dx, dy, dz, yaw, pitch, onGround));
    }

    static void head(List<Player> viewers, int entityId, float head) {
        send(viewers, new WrapperPlayServerEntityHeadLook(entityId, head));
    }

    /**
     * Says where the body is outright, still smoothed by the client: what the
     * server itself sends now and then to end any drift.
     */
    static void sync(List<Player> viewers, int entityId, double x, double y, double z,
                     float yaw, float pitch, boolean onGround) {
        if (atLeast(ServerVersion.V_1_21_2)) {
            send(viewers, new WrapperPlayServerEntityPositionSync(entityId,
                    new EntityPositionData(new Vector3d(x, y, z), Vector3d.zero(), yaw, pitch),
                    onGround));
            return;
        }
        teleport(viewers, entityId, x, y, z, yaw, pitch, onGround);
    }

    /** Puts the body somewhere at once: a seek, a pearl, a cut to another scene. */
    static void teleport(List<Player> viewers, int entityId, double x, double y, double z,
                         float yaw, float pitch, boolean onGround) {
        send(viewers, new WrapperPlayServerEntityTeleport(entityId, new Vector3d(x, y, z),
                yaw, pitch, onGround));
        // The steps that follow are counted from the last sync, not from a
        // teleport, on the clients that have both.
        if (atLeast(ServerVersion.V_1_21_2)) {
            send(viewers, new WrapperPlayServerEntityPositionSync(entityId,
                    new EntityPositionData(new Vector3d(x, y, z), Vector3d.zero(), yaw, pitch), onGround));
        }
    }

    static void equip(List<Player> viewers, int entityId, org.bukkit.inventory.EquipmentSlot slot,
                      @Nullable ItemStack item) {
        EquipmentSlot target = switch (slot) {
            case HAND -> EquipmentSlot.MAIN_HAND;
            case OFF_HAND -> EquipmentSlot.OFF_HAND;
            case HEAD -> EquipmentSlot.HELMET;
            case CHEST -> EquipmentSlot.CHEST_PLATE;
            case LEGS -> EquipmentSlot.LEGGINGS;
            case FEET -> EquipmentSlot.BOOTS;
            default -> null;
        };
        if (target == null) return;
        // Emptied rather than skipped: a slot left alone keeps whatever was
        // drawn there, so a sword that was put away would stay in the hand.
        send(viewers, new WrapperPlayServerEntityEquipment(entityId,
                List.of(new Equipment(target, item == null || item.getType().isAir()
                        ? com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY
                        : SpigotConversionUtil.fromBukkitItemStack(item)))));
    }

    /** The base flags, the pose and the raised hand, in one packet. */
    static void state(List<Player> viewers, int entityId, byte flags, String pose,
                      boolean living, int hands) {
        List<EntityData<?>> data = new ArrayList<>(3);
        data.add(new EntityData<>(FLAGS, EntityDataTypes.BYTE, flags));
        data.add(new EntityData<>(POSE, EntityDataTypes.ENTITY_POSE, poseOf(pose)));
        if (living) data.add(new EntityData<>(HAND_STATES, EntityDataTypes.BYTE, (byte) hands));
        metadata(viewers, entityId, data);
    }

    /** What a non-player looks like beyond its type. */
    static void appearance(List<Player> viewers, int entityId, Appearance look, boolean itemCarrier) {
        List<EntityData<?>> data = new ArrayList<>(3);
        if (itemCarrier && look.item() != null) {
            data.add(new EntityData<>(ITEM, EntityDataTypes.ITEMSTACK,
                    SpigotConversionUtil.fromBukkitItemStack(look.item())));
        }
        if (look.baby()) data.add(new EntityData<>(BABY, EntityDataTypes.BOOLEAN, true));
        if (look.name() != null) {
            data.add(new EntityData<>(CUSTOM_NAME, EntityDataTypes.OPTIONAL_ADV_COMPONENT,
                    Optional.of(LegacyComponentSerializer.legacySection().deserialize(look.name()))));
            data.add(new EntityData<>(NAME_VISIBLE, EntityDataTypes.BOOLEAN, look.nameVisible()));
        }
        if (!data.isEmpty()) metadata(viewers, entityId, data);
    }

    /**
     * Keeps the client's own physics off a body that is not alive.
     *
     * <p>The client moves a pearl, an arrow or a dropped item by itself between
     * packets, gravity included; with no velocity ever sent, that gravity only
     * piles up and the body twitches down and back every tick. A crystal a
     * player placed has no bedrock under it.
     */
    static void still(List<Player> viewers, int entityId, EntityType type) {
        List<EntityData<?>> data = new ArrayList<>(2);
        data.add(new EntityData<>(NO_GRAVITY, EntityDataTypes.BOOLEAN, true));
        if (type == EntityTypes.END_CRYSTAL) data.add(new EntityData<>(CRYSTAL_BOTTOM, EntityDataTypes.BOOLEAN, false));
        metadata(viewers, entityId, data);
    }

    /** Health to nothing: the client lays the body down and turns it red. */
    static void dying(List<Player> viewers, int entityId) {
        metadata(viewers, entityId, List.of(new EntityData<>(HEALTH, EntityDataTypes.FLOAT, 0f)));
        status(viewers, entityId, STATUS_DEATH);
    }

    private static void metadata(List<Player> viewers, int entityId, List<EntityData<?>> data) {
        try {
            send(viewers, new WrapperPlayServerEntityMetadata(entityId, data));
        } catch (RuntimeException unsupported) {
            // A detail missing on a version nobody has tested, not a disconnect.
        }
    }

    static EntityPose poseOf(String name) {
        try {
            return EntityPose.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return EntityPose.STANDING;
        }
    }

    // ------------------------------------------------------------ happenings

    static void swing(List<Player> viewers, int entityId, boolean offHand) {
        send(viewers, new WrapperPlayServerEntityAnimation(entityId, offHand
                ? WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_OFF_HAND
                : WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM));
    }

    /**
     * The red flash and the tilt away from the blow.
     *
     * <p>Not the animation packet's "hurt": the client has ignored that since
     * 1.19.4, which is why hits in older replays looked like nothing happened.
     */
    static void hurt(List<Player> viewers, int entityId, float direction) {
        send(viewers, new WrapperPlayServerHurtAnimation(entityId, direction));
    }

    static void critical(List<Player> viewers, int entityId, boolean magic) {
        send(viewers, new WrapperPlayServerEntityAnimation(entityId, magic
                ? WrapperPlayServerEntityAnimation.EntityAnimationType.MAGIC_CRITICAL_HIT
                : WrapperPlayServerEntityAnimation.EntityAnimationType.CRITICAL_HIT));
    }

    static void status(List<Player> viewers, int entityId, byte status) {
        send(viewers, new WrapperPlayServerEntityStatus(entityId, status));
    }

    /**
     * Paints one slot of the viewer's own inventory, on their screen only: the
     * hand they see in first person. Window -2 is the player's inventory, which
     * the hotbar overlay leaves alone.
     */
    static void hand(Player viewer, int slot, @Nullable ItemStack item) {
        send(List.of(viewer), new WrapperPlayServerSetSlot(-2, 0, slot,
                item == null ? com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY
                        : SpigotConversionUtil.fromBukkitItemStack(item)));
    }

    /** The hearts on the viewer's screen, with a full food bar. */
    static void health(List<Player> viewers, float health) {
        send(viewers, new WrapperPlayServerUpdateHealth(health, 20, 5f));
    }

    static void passengers(List<Player> viewers, int vehicleId, int[] riders) {
        send(viewers, new WrapperPlayServerSetPassengers(vehicleId, riders));
    }

    static void collect(List<Player> viewers, int itemId, int collectorId, int amount) {
        send(viewers, new WrapperPlayServerCollectItem(itemId, collectorId, amount));
    }

    static void cracks(List<Player> viewers, int breakerId, int x, int y, int z, int stage) {
        send(viewers, new WrapperPlayServerBlockBreakAnimation(breakerId,
                new Vector3i(x, y, z), (byte) stage));
    }

    /** A block broken: the game's own effect, its sound and its pieces both. */
    static void broken(List<Player> viewers, int x, int y, int z, BlockData was) {
        int state = SpigotConversionUtil.fromBukkitBlockData(was).getGlobalId();
        send(viewers, new WrapperPlayServerEffect(BLOCK_BROKEN, new Vector3i(x, y, z), state, false));
    }

    /**
     * Through the eighths-of-a-block constructor: the only position form every
     * packetevents release we meet has (2.12 lacks the {@code Vector3d} one), and
     * the precision the packet carries anyway.
     */
    static void sound(List<Player> viewers, String key, SoundCategory category,
                      double x, double y, double z, float volume, float pitch) {
        send(viewers, new WrapperPlayServerSoundEffect(Sounds.getByNameOrCreate(key), category,
                new Vector3i((int) (x * 8), (int) (y * 8), (int) (z * 8)), volume, pitch));
    }

    /** An explosion as the client draws it, its particle and its sound, and no push. */
    static void blast(List<Player> viewers, double x, double y, double z, String particle, String sound) {
        ParticleType<?> type = ParticleTypes.getByName(particle);
        if (type == null) type = ParticleTypes.EXPLOSION;
        send(viewers, new WrapperPlayServerExplosion(new Vector3d(x, y, z), new Vector3d(0, 0, 0),
                new Particle<>(type), Sounds.getByNameOrCreate(sound)));
    }

    static void entitySound(List<Player> viewers, String key, SoundCategory category,
                            int entityId, float volume, float pitch) {
        send(viewers, new WrapperPlayServerEntitySoundEffect(Sounds.getByNameOrCreate(key),
                category, entityId, volume, pitch));
    }

    static void particle(List<Player> viewers, ParticleType<?> type, double x, double y, double z,
                         float spread, float speed, int count) {
        send(viewers, new WrapperPlayServerParticle(new Particle<>(type), false,
                new Vector3d(x, y, z), new Vector3f(spread, spread, spread), speed, count));
    }

    static void blockParticle(List<Player> viewers, BlockData block, double x, double y, double z,
                              float spread, int count) {
        send(viewers, new WrapperPlayServerParticle(new Particle<>(ParticleTypes.BLOCK,
                new ParticleBlockStateData(SpigotConversionUtil.fromBukkitBlockData(block))), false,
                new Vector3d(x, y, z), new Vector3f(spread, spread, spread), 0f, count));
    }

    /** Looks out of another entity's eyes, or back out of one's own. */
    static void camera(Player viewer, int entityId) {
        Broadcast.send(List.of(viewer), new WrapperPlayServerCamera(entityId));
    }

    /** The protocol's type for a Bukkit entity type name, or {@code null}. */
    static @Nullable EntityType typeOf(@Nullable String bukkitName) {
        if (bukkitName == null) return EntityTypes.PLAYER;
        try {
            return SpigotConversionUtil.fromBukkitEntityType(org.bukkit.entity.EntityType.valueOf(bukkitName));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return null;
        }
    }

    /** The data field of a spawn packet, which a falling block needs to be its block. */
    static int spawnData(EntityType type, Appearance look) {
        if (type == EntityTypes.FALLING_BLOCK && look.block() != null) {
            return SpigotConversionUtil.fromBukkitBlockData(look.block()).getGlobalId();
        }
        return 0;
    }

    private static void send(List<Player> viewers, PacketWrapper<?> packet) {
        Broadcast.send(viewers, packet);
    }
}
