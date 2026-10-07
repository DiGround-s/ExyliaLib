package net.exylia.lib.replay.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.sound.Sound;
import com.github.retrooper.packetevents.protocol.sound.SoundCategory;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
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
    public void onPacketSend(PacketSendEvent event) {
        boolean sound = event.getPacketType() == PacketType.Play.Server.SOUND_EFFECT;
        if (!sound && event.getPacketType() != PacketType.Play.Server.EXPLOSION) return;
        if (!ReplayRuntime.capturing()) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        try {
            if (sound) sound(player, new WrapperPlayServerSoundEffect(event));
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
        if (!TemporaryWorld.NAME.equals(world.getName())) return world;
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
