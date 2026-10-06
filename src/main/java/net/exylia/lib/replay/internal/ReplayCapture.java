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

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

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
        World world = player.getWorld();
        if (TemporaryWorld.NAME.equals(world.getName())) return;
        try {
            if (sound) sound(world, new WrapperPlayServerSoundEffect(event));
            else blast(world, new WrapperPlayServerExplosion(event));
        } catch (RuntimeException unreadable) {
            // A layout this version of PacketEvents cannot read: that one is guessed.
        }
    }

    private static void sound(World world, WrapperPlayServerSoundEffect packet) {
        SoundCategory category = packet.getSoundCategory();
        if (category != SoundCategory.BLOCK && category != SoundCategory.HOSTILE
                && category != SoundCategory.NEUTRAL && category != SoundCategory.PLAYER
                && category != SoundCategory.AMBIENT) {
            return;
        }
        Sound played = packet.getSound();
        if (played == null || played.getSoundId() == null) return;
        String key = played.getSoundId().toString();
        // Eighths of a block: getPosition() only exists from packetevents 2.13.
        Vector3i at = packet.getEffectPosition();
        if (!first(Objects.hash(key, at.getX(), at.getY(), at.getZ(), packet.getSeed()))) return;
        ReplayRuntime.effect(ReplayMark.SOUND, new Location(world, at.getX() / 8.0, at.getY() / 8.0, at.getZ() / 8.0),
                key + '|' + category.name() + '|' + packet.getVolume() + '|' + packet.getPitch());
    }

    private static void blast(World world, WrapperPlayServerExplosion packet) {
        Vector3d at = packet.getPosition();
        Particle<?> particle = packet.getParticle();
        if (particle == null) particle = packet.getLargeExplosionParticles();
        Sound bang = packet.getExplosionSound();
        if (particle == null || bang == null || bang.getSoundId() == null) return;
        String particleKey = particle.getType().getName().toString();
        String soundKey = bang.getSoundId().toString();
        if (!first(Objects.hash(particleKey, at.getX(), at.getY(), at.getZ()))) return;
        ReplayRuntime.effect(ReplayMark.BLAST, new Location(world, at.getX(), at.getY(), at.getZ()),
                particleKey + '|' + soundKey);
    }

    /** Whether this is the first time this tick the packet is seen. */
    private static boolean first(int key) {
        int now = ReplayClock.now();
        if (SEEN.size() > SEEN_LIMIT) SEEN.values().removeIf(tick -> now - tick > 2);
        Integer was = SEEN.put(key, now);
        return was == null || now - was > 2;
    }
}
