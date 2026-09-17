package net.exylia.lib.replay.internal;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * What a replay sounds like.
 *
 * <h2>Derived, not recorded</h2>
 * A recording holds no audio. It does not need to: a hit, a block breaking and
 * a blast are already in it as marks, and what each of those sounds like is
 * something every client already knows. Playing them from the marks costs
 * nothing in the file and is the difference between watching a fight and
 * watching a silent film of one &mdash; which is what the first version of this
 * module was, and it read as broken rather than as quiet.
 *
 * <p>Everything here goes to one viewer at a time through Bukkit's own
 * per-player methods. Sounds and particles are deliberately not packets in this
 * library: an unmapped sound name written straight to a connection disconnects
 * the client, and the server's own method knows what it is allowed to send.
 */
@ApiStatus.Internal
final class Ambience {

    /**
     * How many block sounds one tick may play.
     *
     * <p>A blast takes out dozens of blocks on the same tick, and dozens of
     * overlapping break sounds is a burst of noise rather than an explosion.
     * The blast has its own bang; these are the rubble under it.
     */
    private static final int MAX_BLOCK_SOUNDS = 4;

    private Ambience() {
    }

    /** Somebody took a hit. */
    static void hurt(List<Player> viewers, Location at) {
        for (Player viewer : viewers) {
            if (notHere(viewer)) continue;
            viewer.playSound(at, Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 1f, 1f);
            viewer.spawnParticle(Particle.DAMAGE_INDICATOR, at.clone().add(0, 1, 0), 4,
                    0.25, 0.3, 0.25, 0.0);
        }
    }

    /** Somebody swung and connected with nothing in particular. */
    static void swing(List<Player> viewers, Location at) {
        for (Player viewer : viewers) {
            if (notHere(viewer)) continue;
            viewer.playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS,
                    0.25f, 1.4f);
        }
    }

    /**
     * The arena changing: one sound and one shower of pieces per block, up to
     * the ceiling, and nothing at all past it.
     */
    static void blocks(List<Player> viewers, List<Location> places, List<BlockData> nowData,
                       List<BlockData> beforeData) {
        int played = 0;
        for (int index = 0; index < places.size() && played < MAX_BLOCK_SOUNDS; index++) {
            BlockData now = nowData.get(index);
            BlockData before = beforeData.get(index);
            boolean broken = now == null || now.getMaterial() == Material.AIR;
            // A break is heard and seen as whatever was there, not as what is
            // there now — which is air, and has neither a sound nor a colour.
            BlockData voice = broken ? before : now;
            if (voice == null || voice.getMaterial() == Material.AIR) continue;
            played++;
            Location at = places.get(index).clone().add(0.5, 0.5, 0.5);
            for (Player viewer : viewers) {
                if (notHere(viewer)) continue;
                viewer.playSound(at, broken
                                ? voice.getSoundGroup().getBreakSound()
                                : voice.getSoundGroup().getPlaceSound(),
                        SoundCategory.BLOCKS, 0.8f, 1f);
                if (broken) {
                    viewer.spawnParticle(Particle.BLOCK, at, 12, 0.25, 0.25, 0.25, 0.0, voice);
                }
            }
        }
    }

    /** Something appeared: an arrow leaving a bow, a pearl leaving a hand. */
    static void appeared(List<Player> viewers, String entityType, Location at) {
        Sound sound = soundOf(entityType);
        if (sound == null) return;
        for (Player viewer : viewers) {
            if (notHere(viewer)) continue;
            viewer.playSound(at, sound, SoundCategory.PLAYERS, 1f, 1f);
        }
    }

    /** Something landed or went out. */
    static void gone(List<Player> viewers, String entityType, Location at) {
        if (!"ARROW".equals(entityType) && !"SPECTRAL_ARROW".equals(entityType)) return;
        for (Player viewer : viewers) {
            if (notHere(viewer)) continue;
            viewer.playSound(at, Sound.ENTITY_ARROW_HIT, SoundCategory.PLAYERS, 0.6f, 1f);
        }
    }

    /**
     * What a thing sounds like when it is thrown, or {@code null} for the ones
     * that arrive in silence.
     *
     * <p>A crystal is placed without a sound; what anybody remembers about one
     * is the blast, and that has its own mark.
     */
    private static Sound soundOf(String entityType) {
        return switch (entityType) {
            case "ARROW", "SPECTRAL_ARROW" -> Sound.ENTITY_ARROW_SHOOT;
            case "ENDER_PEARL" -> Sound.ENTITY_ENDER_PEARL_THROW;
            case "SPLASH_POTION", "LINGERING_POTION", "POTION" -> Sound.ENTITY_SPLASH_POTION_THROW;
            case "SNOWBALL", "EGG" -> Sound.ENTITY_SNOWBALL_THROW;
            case "TRIDENT" -> Sound.ITEM_TRIDENT_THROW;
            case "FIREBALL", "SMALL_FIREBALL" -> Sound.ENTITY_BLAZE_SHOOT;
            case "TNT", "PRIMED_TNT" -> Sound.ENTITY_TNT_PRIMED;
            case "FIREWORK_ROCKET", "FIREWORK" -> Sound.ENTITY_FIREWORK_ROCKET_LAUNCH;
            default -> null;
        };
    }

    /** The blast itself. */
    static void explosion(List<Player> viewers, Location at, float power) {
        for (Player viewer : viewers) {
            if (notHere(viewer)) continue;
            viewer.spawnParticle(power >= 2f
                    ? Particle.EXPLOSION_EMITTER : Particle.EXPLOSION, at, 1);
            viewer.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS,
                    Math.min(1f, Math.max(0.4f, power / 4f)), 1f);
        }
    }

    private static boolean notHere(Player viewer) {
        return viewer == null || !viewer.isOnline();
    }
}
