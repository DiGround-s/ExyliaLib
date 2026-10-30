package net.exylia.lib.replay.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.sound.Sound;
import com.github.retrooper.packetevents.protocol.sound.SoundCategory;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import net.exylia.lib.replay.ReplayMark;
import net.exylia.lib.util.world.TemporaryWorld;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps the sounds and explosions the server really sent.
 *
 * <p>Guessing them on playback is how a wind charge ends up sounding like TNT
 * and a pearl like nothing in particular. The server already worked out every
 * one of them; this writes them down as they leave, once each however many
 * players heard them, so the replay plays back what was heard.
 *
 * <p>Only the sounds of the world itself: a plugin's chime to one player is
 * {@link SoundCategory#MASTER} or {@link SoundCategory#UI}, which a fight
 * never made. Nothing sent to somebody on the temporary world either, which is
 * a replay being watched, not one being made.
 *
 * <p>And, from the other direction, what each client sent that the server's
 * own state does not keep: pongs, looks and attacks, handed to its
 * {@link ClientView}.
 */
@ApiStatus.Internal
final class ReplayCapture extends PacketListenerAbstract {

    /** One packet heard by several players is one sound: seen keys, by tick. */
    private static final Map<Integer, Integer> SEEN = new ConcurrentHashMap<>();
    private static final int SEEN_LIMIT = 4096;
    /** What {@link #claim} returns for a copy that was not the first. */
    static final int MISSED = Integer.MIN_VALUE;
    /** The tick of the last sweep of {@link #SEEN}. */
    private static final AtomicInteger lastSweep = new AtomicInteger(Integer.MIN_VALUE);

    /** The game's world events for a potion breaking: an ordinary one and an instant one. */
    private static final int SPLASH_POTION = 2002;
    private static final int INSTANT_SPLASH_POTION = 2007;

    private ReplayCapture() {
        super(PacketListenerPriority.MONITOR);
    }

    static void register() {
        try {
            PacketEvents.getAPI().getEventManager().registerListener(new ReplayCapture());
        } catch (Throwable missing) {
            // No PacketEvents: replays fall back to the sounds they work out.
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (!reads(type)) return;
        User user = event.getUser();
        UUID player = user == null ? null : user.getUUID();
        ClientView view = player == null ? null : ClientView.of(player);
        if (view == null) return;
        try {
            if (type == PacketType.Play.Client.PONG) {
                view.pong(new WrapperPlayClientPong(event).getId());
            } else if (type == PacketType.Play.Client.PLAYER_ROTATION
                    || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
                com.github.retrooper.packetevents.protocol.world.Location at =
                        new WrapperPlayClientPlayerFlying(event).getLocation();
                view.look(ReplayClock.now(), at.getYaw(), at.getPitch());
            } else if (type == PacketType.Play.Client.INTERACT_ENTITY) {
                WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
                if (interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                    view.attack(ReplayClock.now(), interact.getEntityId());
                }
            } else if (view.waiting()) {
                // A position, a bare flying packet or the end of the client's
                // tick: the look did not change, so the last one is the one the
                // attack was aimed with.
                view.settle();
            }
        } catch (RuntimeException unreadable) {
            // A layout this version of PacketEvents cannot read: that one is missed.
        }
    }

    /** Whether a packet from a client is one a {@link ClientView} reads; checked first, for every packet. */
    static boolean reads(PacketTypeCommon type) {
        return type == PacketType.Play.Client.PLAYER_ROTATION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                || type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_FLYING
                || type == PacketType.Play.Client.CLIENT_TICK_END
                || type == PacketType.Play.Client.INTERACT_ENTITY
                || type == PacketType.Play.Client.PONG;
    }

    /** A player's connection, to ping; {@code null} when PacketEvents has none for them. */
    static @Nullable Object connectionOf(Player player) {
        try {
            return PacketEvents.getAPI().getPlayerManager().getUser(player);
        } catch (Throwable missing) {
            return null;
        }
    }

    /**
     * One ping, silently: no listener of ours or anybody else's sees it go.
     * The client answers it after everything sent before it.
     */
    static void ping(Object connection, int id) {
        try {
            ((User) connection).sendPacketSilently(new WrapperPlayServerPing(id));
        } catch (Throwable gone) {
            // Disconnected meanwhile; the quit takes the view away.
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        boolean sound = event.getPacketType() == PacketType.Play.Server.SOUND_EFFECT;
        boolean world = event.getPacketType() == PacketType.Play.Server.EFFECT;
        if (!sound && !world && event.getPacketType() != PacketType.Play.Server.EXPLOSION) return;
        if (!ReplayRuntime.capturing()) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        try {
            if (sound) sound(player, new WrapperPlayServerSoundEffect(event));
            else if (world) worldEvent(player, new WrapperPlayServerEffect(event));
            else blast(player, new WrapperPlayServerExplosion(event));
        } catch (RuntimeException unreadable) {
            // A layout this version of PacketEvents cannot read: that one is guessed.
        }
    }

    private static void sound(Player player, WrapperPlayServerSoundEffect packet) {
        SoundCategory category = packet.getSoundCategory();
        if (category != SoundCategory.BLOCK && category != SoundCategory.HOSTILE
                && category != SoundCategory.NEUTRAL && category != SoundCategory.PLAYER
                && category != SoundCategory.AMBIENT) {
            return;
        }
        Sound played = packet.getSound();
        if (played == null || played.getSoundId() == null) return;
        // Eighths of a block: getPosition() only exists from packetevents 2.13.
        Vector3i at = packet.getEffectPosition();
        // Every copy but the first is thrown away, and there is one per player
        // in earshot: nothing is built until this one is known to be kept.
        int dedup = soundKey(played.getSoundId().hashCode(), at.getX(), at.getY(), at.getZ(), packet.getSeed());
        int claimed = claim(dedup);
        if (claimed == MISSED) return;
        World world = worldOf(player, dedup, claimed);
        if (world == null) return;
        String key = played.getSoundId().toString();
        ReplayRuntime.effect(ReplayMark.SOUND, new Location(world, at.getX() / 8.0, at.getY() / 8.0, at.getZ() / 8.0),
                key + '|' + category.name() + '|' + packet.getVolume() + '|' + packet.getPitch());
    }

    /**
     * A potion breaking. Its glass, its sound and its cloud are one world
     * event the client draws by itself, so without it a thrown potion flies
     * and silently disappears.
     */
    private static void worldEvent(Player player, WrapperPlayServerEffect packet) {
        int type = packet.getType();
        if (type != SPLASH_POTION && type != INSTANT_SPLASH_POTION) return;
        Vector3i at = packet.getPosition();
        int dedup = soundKey(type, at.getX(), at.getY(), at.getZ(), packet.getData());
        int claimed = claim(dedup);
        if (claimed == MISSED) return;
        World world = worldOf(player, dedup, claimed);
        if (world == null) return;
        ReplayRuntime.effect(ReplayMark.WORLD_EVENT, new Location(world, at.getX(), at.getY(), at.getZ()),
                type + "|" + packet.getData());
    }

    private static void blast(Player player, WrapperPlayServerExplosion packet) {
        Vector3d at = packet.getPosition();
        Particle<?> particle = packet.getParticle();
        if (particle == null) particle = packet.getLargeExplosionParticles();
        Sound bang = packet.getExplosionSound();
        if (particle == null || bang == null || bang.getSoundId() == null) return;
        String particleKey = particle.getType().getName().toString();
        int dedup = Objects.hash(particleKey, at.getX(), at.getY(), at.getZ());
        int claimed = claim(dedup);
        if (claimed == MISSED) return;
        World world = worldOf(player, dedup, claimed);
        if (world == null) return;
        ReplayRuntime.effect(ReplayMark.BLAST, new Location(world, at.getX(), at.getY(), at.getZ()),
                particleKey + '|' + bang.getSoundId());
    }

    /**
     * The world of whoever a kept packet was sent to, or {@code null} when it
     * is the temporary world. Read only for the one copy kept: this is a Netty
     * thread, and the other copies need no world at all.
     *
     * <p>A copy sent on the temporary world is not the sound being made, so it
     * gives its key back: the same sound reaching somebody on a real world is
     * then still written down.
     */
    private static @Nullable World worldOf(Player player, int dedup, int claimed) {
        World world = player.getWorld();
        if (!TemporaryWorld.is(world)) return world;
        // Only our own claim, by the tick it was stamped with: reading the clock
        // again could see the next tick and leave the key held.
        SEEN.remove(dedup, claimed);
        return null;
    }

    /** The dedup key of a sound, with no allocation: it runs for every copy sent. */
    static int soundKey(int soundId, int x, int y, int z, long seed) {
        int hash = soundId;
        hash = 31 * hash + x;
        hash = 31 * hash + y;
        hash = 31 * hash + z;
        return 31 * hash + Long.hashCode(seed);
    }

    /**
     * Whether this is the first time this tick the packet is seen.
     *
     * <p>This runs on the Netty threads for every sound sent. The sweep therefore
     * happens at most once per tick, by one thread: a busy server can hold more
     * than {@link #SEEN_LIMIT} recent keys, and sweeping on every packet then
     * walked the whole map thousands of times a second and stalled the
     * connections. If the map is still too big after a sweep it is cleared,
     * which only lets a few duplicate sounds through.
     */
    static boolean first(int key) {
        return claim(key) != MISSED;
    }

    /** {@link #first} that also says which tick the key was claimed on, or {@link #MISSED}. */
    static int claim(int key) {
        int now = ReplayClock.now();
        if (SEEN.size() > SEEN_LIMIT) {
            int swept = lastSweep.get();
            if (swept != now && lastSweep.compareAndSet(swept, now)) {
                SEEN.values().removeIf(tick -> now - tick > 2);
                if (SEEN.size() > SEEN_LIMIT * 4) SEEN.clear();
            }
        }
        Integer was = SEEN.put(key, now);
        return was == null || now - was > 2 ? now : MISSED;
    }
}
