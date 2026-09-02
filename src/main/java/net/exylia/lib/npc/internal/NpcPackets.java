package net.exylia.lib.npc.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.exylia.lib.npc.NpcModel;
import net.exylia.lib.npc.NpcPose;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes a player-shaped entity to a client.
 *
 * <p>The only class in the module that names PacketEvents, so a server without
 * it never loads this one. An NPC sent this way is not an entity the server
 * knows about: it is not ticked, not saved, not in any chunk, has no hitbox on
 * the server and cannot be hit, and two players standing together can be shown
 * different ones.
 *
 * <h2>An identity, then a body</h2>
 * A player entity is the one thing the client refuses to draw from a spawn
 * packet alone: without an entry in the player list first it has no skin and no
 * name to hang on the entity, and renders as the default. So every NPC is
 * announced, drawn, and then withdrawn again in that order.
 *
 * <p>It is announced <em>unlisted</em>. The entry is what carries the skin;
 * being in the tab list is a separate flag, and an NPC that appears in the tab
 * beside real players is a bug report about ghost players.
 */
final class NpcPackets implements NpcSink {

    static final NpcPackets INSTANCE = new NpcPackets();

    /** Shared entity metadata, from the vanilla protocol. */
    private static final int ENTITY_FLAGS = 0;
    private static final byte FLAG_GLOWING = 0x40;
    private static final int POSE = 6;

    /**
     * Which skin layers the client draws, from the vanilla protocol.
     *
     * <p>Left alone, an NPC has no jacket, no sleeves and no hat: the field
     * defaults to nothing enabled, and a player who is used to seeing their own
     * skin notices immediately. Every bit set is every layer drawn.
     */
    private static final int SKIN_LAYERS = 17;
    private static final byte ALL_LAYERS = 0x7F;

    private NpcPackets() {
    }

    /** Whether PacketEvents is loaded and ready to send. */
    static boolean ready() {
        try {
            return PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Reserves an entity id that cannot collide with a real entity. */
    static int newEntityId() {
        return SpigotReflectionUtil.generateEntityId();
    }

    @Override
    public void spawn(List<Player> viewers, int entityId, NpcModel model, Location at) {
        UserProfile profile = profileOf(model);
        PacketWrapper<?> announce = new WrapperPlayServerPlayerInfoUpdate(
                EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED),
                new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                        profile, false, 0, GameMode.SURVIVAL, Component.empty(), null));
        PacketWrapper<?> body = new WrapperPlayServerSpawnEntity(
                entityId, Optional.of(model.id()), EntityTypes.PLAYER,
                new Vector3d(at.getX(), at.getY(), at.getZ()),
                at.getPitch(), at.getYaw(), at.getYaw(), 0, Optional.empty());
        PacketWrapper<?> state = new WrapperPlayServerEntityMetadata(entityId, fullState(model));
        PacketWrapper<?> head = new WrapperPlayServerEntityHeadLook(entityId, at.getYaw());
        List<Equipment> kit = kitOf(model);

        for (Player viewer : viewers) {
            send(viewer, announce);
            send(viewer, body);
            send(viewer, state);
            send(viewer, head);
            if (!kit.isEmpty()) {
                send(viewer, new WrapperPlayServerEntityEquipment(entityId, kit));
            }
        }
    }

    @Override
    public void look(List<Player> viewers, int entityId, float yaw, float pitch) {
        PacketWrapper<?> rotation =
                new WrapperPlayServerEntityRotation(entityId, yaw, pitch, false);
        PacketWrapper<?> head = new WrapperPlayServerEntityHeadLook(entityId, yaw);
        for (Player viewer : viewers) {
            send(viewer, rotation);
            send(viewer, head);
        }
    }

    @Override
    public void pose(List<Player> viewers, int entityId, NpcModel model, NpcPose pose) {
        PacketWrapper<?> packet = new WrapperPlayServerEntityMetadata(entityId,
                List.of(new EntityData<>(POSE, EntityDataTypes.ENTITY_POSE, poseOf(pose))));
        for (Player viewer : viewers) {
            send(viewer, packet);
        }
    }

    @Override
    public void destroy(List<Player> viewers, int entityId, UUID profile) {
        PacketWrapper<?> body = new WrapperPlayServerDestroyEntities(entityId);
        PacketWrapper<?> identity = new WrapperPlayServerPlayerInfoRemove(profile);
        for (Player viewer : viewers) {
            send(viewer, body);
            send(viewer, identity);
        }
    }

    /**
     * The identity the NPC is announced under.
     *
     * <p>Its own UUID rather than the player's, always. Announcing a second
     * entry under a real player's id is how an NPC takes that player's skin off
     * their own body, and it is not recoverable without a relog.
     */
    private static UserProfile profileOf(NpcModel model) {
        UserProfile profile = new UserProfile(model.id(), model.name());
        String texture = model.texture();
        if (texture != null) {
            profile.setTextureProperties(List.of(
                    new TextureProperty("textures", texture, model.signature())));
            return profile;
        }
        Player source = model.skinFrom();
        if (source == null) {
            return profile;
        }
        // Read from the connection this server already holds rather than asked
        // of Mojang: it costs nothing, blocks on nothing, and the moment an NPC
        // is usually wanted is a moment nothing may wait in.
        try {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(source);
            if (user != null && user.getProfile() != null) {
                profile.setTextureProperties(
                        new ArrayList<>(user.getProfile().getTextureProperties()));
            }
        } catch (Throwable gone) {
            // Left with no skin, which is a default-looking NPC rather than none.
        }
        return profile;
    }

    private static List<EntityData<?>> fullState(NpcModel model) {
        List<EntityData<?>> data = new ArrayList<>(3);
        if (model.glowArgb() >= 0) {
            data.add(new EntityData<>(ENTITY_FLAGS, EntityDataTypes.BYTE, FLAG_GLOWING));
        }
        data.add(new EntityData<>(SKIN_LAYERS, EntityDataTypes.BYTE, ALL_LAYERS));
        if (model.pose() != NpcPose.STANDING) {
            data.add(new EntityData<>(POSE, EntityDataTypes.ENTITY_POSE, poseOf(model.pose())));
        }
        return data;
    }

    /** What the NPC is dressed in, read now rather than when the model was built. */
    private static List<Equipment> kitOf(NpcModel model) {
        List<Equipment> kit = new ArrayList<>(6);
        Player wearing = model.wearing();
        if (wearing != null) {
            PlayerInventory inventory = wearing.getInventory();
            add(kit, EquipmentSlot.HELMET, inventory.getHelmet());
            add(kit, EquipmentSlot.CHEST_PLATE, inventory.getChestplate());
            add(kit, EquipmentSlot.LEGGINGS, inventory.getLeggings());
            add(kit, EquipmentSlot.BOOTS, inventory.getBoots());
            add(kit, EquipmentSlot.MAIN_HAND, inventory.getItemInMainHand());
            add(kit, EquipmentSlot.OFF_HAND, inventory.getItemInOffHand());
        }
        if (model.held() != null) {
            kit.removeIf(piece -> piece.getSlot() == EquipmentSlot.MAIN_HAND);
            add(kit, EquipmentSlot.MAIN_HAND, model.held());
        }
        return kit;
    }

    private static void add(List<Equipment> kit, EquipmentSlot slot,
                            org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        kit.add(new Equipment(slot, SpigotConversionUtil.fromBukkitItemStack(item)));
    }

    private static EntityPose poseOf(NpcPose pose) {
        return switch (pose) {
            case LYING -> EntityPose.SLEEPING;
            case CRAWLING -> EntityPose.SWIMMING;
            case SNEAKING -> EntityPose.CROUCHING;
            case SPINNING -> EntityPose.SPIN_ATTACK;
            default -> EntityPose.STANDING;
        };
    }

    /**
     * Sends one packet, unless the player has already gone.
     *
     * <p>An NPC outlives the moment it was created by design, and a player can
     * quit inside that window. Their connection is gone but this module's list
     * of viewers is not, and writing to it is an exception nobody caused.
     */
    private static void send(Player viewer, PacketWrapper<?> packet) {
        if (!viewer.isOnline()) {
            return;
        }
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
        } catch (Throwable gone) {
            // Disconnected between the check and the write.
        }
    }
}
