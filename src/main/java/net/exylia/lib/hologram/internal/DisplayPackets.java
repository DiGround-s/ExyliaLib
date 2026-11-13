package net.exylia.lib.hologram.internal;

import net.exylia.lib.packet.internal.EntityClicks;
import net.exylia.lib.packet.internal.PacketPeek;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnLivingEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.exylia.lib.hologram.HologramConfig;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes display entities to a client.
 *
 * <p>The only class in the module that names PacketEvents types, so a server
 * without it never loads this one. A hologram sent this way is not an entity
 * the server knows about: it is not ticked, not saved, not in any chunk, and
 * two players can be shown different text at the same coordinates.
 *
 * <p>Entity ids come from {@link SpigotReflectionUtil#generateEntityId()},
 * which draws from the same counter the server uses, so a hologram can never
 * collide with a real entity.
 */
final class DisplayPackets implements DisplaySink {

    static final DisplayPackets INSTANCE = new DisplayPackets();

    private DisplayPackets() {
    }

    /** Returns whether PacketEvents is loaded and ready to send. */
    static boolean ready() {
        try {
            return PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded()
                    && net.exylia.lib.internal.PacketEventsVersion.agrees();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Reserves an entity id that cannot collide with a real entity. */
    static int newEntityId() {
        return SpigotReflectionUtil.generateEntityId();
    }

    /**
     * Spawns a display and sends its full state.
     *
     * <p>Spawn and metadata go together because a display with no metadata is a
     * zero-sized invisible nothing: sending them apart is a visible flicker.
     */
    @Override
    public void spawn(Player viewer, DisplayState display, Location at, Component text,
                      DisplayState.Transform transform) {
        if (display.mob() != null) {
            spawnMob(viewer, display, at, transform);
            return;
        }
        send(viewer, new WrapperPlayServerSpawnEntity(
                display.entityId(),
                Optional.of(UUID.randomUUID()),
                typeOf(display.kind()),
                new Vector3d(at.getX(), at.getY(), at.getZ()),
                // Which way it faces: what a FIXED hologram is read from, and what the other
                // billboards keep for the axes they do not turn.
                at.getPitch(), at.getYaw(), at.getYaw(), 0, Optional.empty()));
        send(viewer, new WrapperPlayServerEntityMetadata(display.entityId(),
                fullState(display, text, transform)));
    }

    @Override
    public void transform(Player viewer, DisplayState display, DisplayState.Transform transform,
                          int ticks) {
        // A delay of zero is what restarts the interpolation: without it the
        // client snaps to the new frame instead of gliding there.
        send(viewer, new WrapperPlayServerEntityMetadata(display.entityId(), List.of(
                new EntityData<>(DisplayState.INTERPOLATION_DELAY, EntityDataTypes.INT, 0),
                new EntityData<>(DisplayState.TRANSFORMATION_INTERPOLATION_DURATION,
                        EntityDataTypes.INT, ticks),
                new EntityData<>(DisplayState.TRANSLATION, EntityDataTypes.VECTOR3F,
                        new Vector3f(transform.tx(), transform.ty(), transform.tz())),
                new EntityData<>(DisplayState.SCALE, EntityDataTypes.VECTOR3F, scaleOf(display, transform)),
                new EntityData<>(DisplayState.ROTATION_LEFT, EntityDataTypes.QUATERNION,
                        new Quaternion4f(0, transform.qy(), 0, transform.qw())))));
    }

    @Override
    public void hitbox(Player viewer, int entityId, Location at, float width, float height) {
        send(viewer, new WrapperPlayServerSpawnEntity(entityId, Optional.of(UUID.randomUUID()),
                EntityTypes.INTERACTION, new Vector3d(at.getX(), at.getY(), at.getZ()),
                0f, 0f, 0f, 0, Optional.empty()));
        send(viewer, new WrapperPlayServerEntityMetadata(entityId, List.of(
                new EntityData<>(DisplayState.INTERACTION_WIDTH, EntityDataTypes.FLOAT, width),
                new EntityData<>(DisplayState.INTERACTION_HEIGHT, EntityDataTypes.FLOAT, height),
                new EntityData<>(DisplayState.INTERACTION_RESPONSIVE, EntityDataTypes.BOOLEAN, true))));
    }

    /**
     * Starts telling {@code clicked} about clicks on a hologram's hitbox.
     *
     * <p>Every packet aimed at a hitbox is kept from the server, which has never
     * heard of that id. The same listener watches spawns and respawns going
     * out, so a hologram riding an entity is mounted again whenever a client
     * is sent that entity anew.
     *
     * @return what to hand to {@link #stopListening}
     */
    static Object listen(HitboxClicks clicked) {
        PacketListenerAbstract listener = new PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                if (aimedClick(event)) {
                    return;
                }
                EntityClicks.Press press = EntityClicks.read(event);
                if (press == null || !(event.getPlayer() instanceof Player player)
                        || !HologramRuntime.isHitbox(press.entityId())) {
                    return;
                }
                event.setCancelled(true);
                if (press.counts()) {
                    boolean sneaking = press.sneaking() != null ? press.sneaking() : player.isSneaking();
                    clicked.clicked(player, press.entityId(),
                            net.exylia.lib.interaction.Click.of(press.attack(), sneaking));
                }
            }

            /**
             * Keeps ridden holograms on their vehicle: an entity the client is
             * sent anew, or a viewer who respawns, arrives with no passengers.
             */
            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (!HologramRuntime.anyMounted() || !(event.getPlayer() instanceof Player player)) {
                    return;
                }
                PacketTypeCommon type = event.getPacketType();
                if (type == PacketType.Play.Server.RESPAWN) {
                    event.getPostTasks().add(() -> HologramRuntime.respawned(player));
                    return;
                }
                int entityId;
                if (type == PacketType.Play.Server.SPAWN_ENTITY) {
                    entityId = PacketPeek.entityId(event, e -> new WrapperPlayServerSpawnEntity(e).getEntityId());
                } else if (type == PacketType.Play.Server.SPAWN_PLAYER) {
                    entityId = new WrapperPlayServerSpawnPlayer(event).getEntityId();
                } else if (type == PacketType.Play.Server.SPAWN_LIVING_ENTITY) {
                    entityId = new WrapperPlayServerSpawnLivingEntity(event).getEntityId();
                } else {
                    return;
                }
                // After the spawn, or the client has no vehicle to seat them on.
                event.getPostTasks().add(() -> HologramRuntime.vehicleShown(player, entityId));
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(listener);
        return listener;
    }

    /**
     * Hands a click that touched no entity to the hologram the player aims
     * at: an arm swing is a left-click, a use of the held item or of a block
     * a right-click. Only the main hand's, as with clicks on the box, and
     * none of them is kept from the server: the swing still swings, the item
     * is still used. A right-click with an empty hand at nothing sends no
     * packet at all, so that one only reaches as far as the box does.
     *
     * @return whether the packet was one of these
     */
    private static boolean aimedClick(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        InteractionHand hand;
        if (type == PacketType.Play.Client.ANIMATION) {
            hand = new WrapperPlayClientAnimation(event).getHand();
        } else if (type == PacketType.Play.Client.USE_ITEM) {
            hand = new WrapperPlayClientUseItem(event).getHand();
        } else if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            hand = new WrapperPlayClientPlayerBlockPlacement(event).getHand();
        } else {
            return false;
        }
        if (hand == InteractionHand.MAIN_HAND && event.getPlayer() instanceof Player player
                && net.exylia.lib.tuner.Tuner.active(player.getUniqueId()).isEmpty()) {
            HologramRuntime.aimed(player, net.exylia.lib.interaction.Click.of(
                    type == PacketType.Play.Client.ANIMATION, player.isSneaking()));
        }
        return true;
    }

    /** Told about a click on a hologram's hitbox. */
    @FunctionalInterface
    interface HitboxClicks {
        void clicked(Player player, int entityId, net.exylia.lib.interaction.Click click);
    }

    static void stopListening(Object listener) {
        try {
            PacketEvents.getAPI().getEventManager()
                    .unregisterListener((PacketListenerAbstract) listener);
        } catch (Throwable ignored) {
            // Shutting down after PacketEvents already did is not a failure.
        }
    }

    /** Sends only the text, for a line whose value changed. */
    @Override
    public void text(Player viewer, DisplayState display, Component text) {
        send(viewer, new WrapperPlayServerEntityMetadata(display.entityId(),
                List.of(new EntityData<>(DisplayState.TEXT,
                        EntityDataTypes.ADV_COMPONENT, text))));
    }

    @Override
    public void item(Player viewer, DisplayState display, org.bukkit.inventory.ItemStack item) {
        send(viewer, new WrapperPlayServerEntityMetadata(display.entityId(),
                List.of(new EntityData<>(DisplayState.ITEM, EntityDataTypes.ITEMSTACK,
                                SpigotConversionUtil.fromBukkitItemStack(item)),
                        new EntityData<>(DisplayState.ITEM_TRANSFORM, EntityDataTypes.BYTE,
                                offCentre(item.getType()) ? FIXED : (byte) 0))));
    }

    @Override
    public void look(Player viewer, DisplayState display, int opacity, int background) {
        send(viewer, new WrapperPlayServerEntityMetadata(display.entityId(), List.of(
                new EntityData<>(DisplayState.BACKGROUND_COLOR, EntityDataTypes.INT, background),
                new EntityData<>(DisplayState.TEXT_OPACITY, EntityDataTypes.BYTE, (byte) opacity))));
    }

    /** Moves a display without respawning it. */
    @Override
    public void teleport(Player viewer, int entityId, Location to) {
        send(viewer, new WrapperPlayServerEntityTeleport(entityId,
                new Vector3d(to.getX(), to.getY(), to.getZ()), to.getYaw(), to.getPitch(), false));
    }

    /** Makes displays ride an entity so the client moves them along with it. */
    @Override
    public void mount(Player viewer, int vehicleId, int[] passengers) {
        send(viewer, new WrapperPlayServerSetPassengers(vehicleId, passengers));
    }

    /** Removes displays from a client. */
    @Override
    public void destroy(Player viewer, int[] entityIds) {
        send(viewer, new WrapperPlayServerDestroyEntities(entityIds));
    }

    /** Mob metadata, from the vanilla protocol: silent, and no gravity. */
    private static final int MOB_SILENT = 4;
    private static final int MOB_NO_GRAVITY = 5;

    /**
     * A mob line: a living entity only this client knows of, so the server
     * never ticks it. It has no transformation, so where the stack puts it and
     * how it moves are its position and heading, and its size the scale
     * attribute (1.20.5+; an older client draws it at its own size).
     *
     * <p>A team of its own, named after its id, hides any nametag, keeps it from
     * pushing the viewer, and carries the colour of its glow.
     */
    private void spawnMob(Player viewer, DisplayState display, Location at, DisplayState.Transform transform) {
        EntityType type = SpigotConversionUtil.fromBukkitEntityType(org.bukkit.entity.EntityType.valueOf(display.mob()));
        UUID uuid = UUID.randomUUID();
        float yaw = yawOf(at, transform);
        send(viewer, new WrapperPlayServerSpawnEntity(display.entityId(), Optional.of(uuid), type,
                new Vector3d(at.getX() + transform.tx(), at.getY() + transform.ty(), at.getZ() + transform.tz()),
                0f, yaw, yaw, 0, Optional.empty()));
        Integer glow = display.glowRgb();
        send(viewer, new WrapperPlayServerEntityMetadata(display.entityId(), List.of(
                new EntityData<>(DisplayState.ENTITY_FLAGS, EntityDataTypes.BYTE,
                        glow != null ? DisplayState.FLAG_GLOWING : (byte) 0),
                new EntityData<>(MOB_SILENT, EntityDataTypes.BOOLEAN, true),
                new EntityData<>(MOB_NO_GRAVITY, EntityDataTypes.BOOLEAN, true))));
        send(viewer, new WrapperPlayServerEntityHeadLook(display.entityId(), yaw));
        NamedTextColor colour = glow == null || glow < 0 ? NamedTextColor.WHITE
                : NamedTextColor.nearestTo(TextColor.color(glow));
        send(viewer, new WrapperPlayServerTeams(team(display), WrapperPlayServerTeams.TeamMode.CREATE,
                new WrapperPlayServerTeams.ScoreBoardTeamInfo(Component.empty(), Component.empty(),
                        Component.empty(), WrapperPlayServerTeams.NameTagVisibility.NEVER,
                        WrapperPlayServerTeams.CollisionRule.NEVER, colour,
                        WrapperPlayServerTeams.OptionData.NONE), uuid.toString()));
        if (transform.sx() != 1f) {
            size(viewer, display, transform.sx());
        }
    }

    @Override
    public void pose(Player viewer, DisplayState mob, Location base, DisplayState.Transform transform,
                     boolean resize) {
        float yaw = yawOf(base, transform);
        send(viewer, new WrapperPlayServerEntityTeleport(mob.entityId(), new Vector3d(base.getX() + transform.tx(),
                base.getY() + transform.ty(), base.getZ() + transform.tz()), yaw, 0f, false));
        send(viewer, new WrapperPlayServerEntityHeadLook(mob.entityId(), yaw));
        if (resize) {
            size(viewer, mob, transform.sx());
        }
    }

    @Override
    public void unteam(Player viewer, DisplayState mob) {
        send(viewer, new WrapperPlayServerTeams(team(mob), WrapperPlayServerTeams.TeamMode.REMOVE,
                Optional.<WrapperPlayServerTeams.ScoreBoardTeamInfo>empty(), List.of()));
    }

    private static String team(DisplayState mob) {
        return "exhm" + mob.entityId();
    }

    private static void size(Player viewer, DisplayState mob, float scale) {
        if (PacketEvents.getAPI().getServerManager().getVersion()
                .isOlderThan(com.github.retrooper.packetevents.manager.server.ServerVersion.V_1_20_5)) {
            return;
        }
        send(viewer, new WrapperPlayServerUpdateAttributes(mob.entityId(), List.of(
                new WrapperPlayServerUpdateAttributes.Property(Attributes.SCALE, Math.max(0.0625, scale), List.of()))));
    }

    /** The heading a turned transformation stands for: a display turns the other way round from a yaw. */
    static float yawOf(Location base, DisplayState.Transform transform) {
        double turned = Math.toDegrees(2 * Math.atan2(transform.qy(), transform.qw()));
        return (float) (base.getYaw() - turned);
    }

    /**
     * Every metadata entry a display needs, in one packet.
     *
     * <p>Only what differs from the client's own default is written: the fewer
     * entries, the smaller the packet, and this one is sent once per viewer per
     * hologram.
     */
    private static List<EntityData<?>> fullState(DisplayState display, Component text,
                                                DisplayState.Transform transform) {
        HologramConfig.Properties properties = display.properties();
        List<EntityData<?>> data = new ArrayList<>(12);

        data.add(new EntityData<>(DisplayState.BILLBOARD, EntityDataTypes.BYTE, display.billboard()));
        data.add(new EntityData<>(DisplayState.SCALE, EntityDataTypes.VECTOR3F, scaleOf(display, transform)));
        if (transform.hasTranslation()) {
            data.add(new EntityData<>(DisplayState.TRANSLATION, EntityDataTypes.VECTOR3F,
                    new Vector3f(transform.tx(), transform.ty(), transform.tz())));
        }
        if (transform.hasRotation()) {
            data.add(new EntityData<>(DisplayState.ROTATION_LEFT, EntityDataTypes.QUATERNION,
                    new Quaternion4f(0, transform.qy(), 0, transform.qw())));
        }
        Integer glow = display.glowRgb();
        if (glow != null) {
            data.add(new EntityData<>(DisplayState.ENTITY_FLAGS, EntityDataTypes.BYTE,
                    DisplayState.FLAG_GLOWING));
            data.add(new EntityData<>(DisplayState.GLOW_COLOR, EntityDataTypes.INT, glow));
        }

        int brightness = display.packedBrightness();
        if (brightness >= 0) {
            data.add(new EntityData<>(DisplayState.BRIGHTNESS, EntityDataTypes.INT, brightness));
        }

        switch (display.kind()) {
            case TEXT -> {
                data.add(new EntityData<>(DisplayState.TEXT, EntityDataTypes.ADV_COMPONENT, text));
                data.add(new EntityData<>(DisplayState.LINE_WIDTH, EntityDataTypes.INT, properties.lineWidth()));
                data.add(new EntityData<>(DisplayState.BACKGROUND_COLOR, EntityDataTypes.INT,
                        display.backgroundArgb()));
                data.add(new EntityData<>(DisplayState.TEXT_OPACITY, EntityDataTypes.BYTE,
                        (byte) properties.textOpacity()));
                data.add(new EntityData<>(DisplayState.TEXT_FLAGS, EntityDataTypes.BYTE, display.textFlags()));
            }
            case ITEM -> {
                data.add(new EntityData<>(DisplayState.ITEM, EntityDataTypes.ITEMSTACK,
                        SpigotConversionUtil.fromBukkitItemStack(
                                display.item() != null ? display.item()
                                        : new org.bukkit.inventory.ItemStack(display.material()))));
                if (offCentre(display)) {
                    data.add(new EntityData<>(DisplayState.ITEM_TRANSFORM, EntityDataTypes.BYTE, FIXED));
                }
            }
            case BLOCK -> data.add(new EntityData<>(DisplayState.BLOCK_STATE, EntityDataTypes.BLOCK_STATE,
                    SpigotConversionUtil.fromBukkitBlockData(
                            display.material().createBlockData()).getGlobalId()));
        }
        return data;
    }

    /** The item frame's display context: the one where a hand-held model sits on its own centre. */
    private static final byte FIXED = 8;

    /** The shield's FIXED context shrinks it to 0.55; undone so size:1 stays the size it was. */
    private static final float SHIELD_FIXED_SCALE = 0.55f;

    /**
     * A shield or a trident drawn as is (context NONE) sits half a block off its own axis, built
     * for a hand, so a spinning one orbits the line instead of turning in place. Every other
     * special model (chests, heads, beds, banners) fills its block and turns on its centre.
     */
    static boolean offCentre(DisplayState display) {
        return display.kind() == HologramConfig.Kind.ITEM && offCentre(shown(display));
    }

    static boolean offCentre(org.bukkit.Material type) {
        return type == org.bukkit.Material.SHIELD || type == org.bukkit.Material.TRIDENT;
    }

    private static org.bukkit.Material shown(DisplayState display) {
        return display.item() != null ? display.item().getType() : display.material();
    }

    private static Vector3f scaleOf(DisplayState display, DisplayState.Transform transform) {
        float grow = display.kind() == HologramConfig.Kind.ITEM && shown(display) == org.bukkit.Material.SHIELD
                ? 1 / SHIELD_FIXED_SCALE : 1;
        return new Vector3f(transform.sx() * grow, transform.sy() * grow, transform.sz() * grow);
    }

    private static EntityType typeOf(HologramConfig.Kind kind) {
        return switch (kind) {
            case ITEM -> EntityTypes.ITEM_DISPLAY;
            case BLOCK -> EntityTypes.BLOCK_DISPLAY;
            default -> EntityTypes.TEXT_DISPLAY;
        };
    }

    private static void send(Player viewer, com.github.retrooper.packetevents.wrapper.PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }
}
