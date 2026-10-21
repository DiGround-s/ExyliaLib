package net.exylia.lib.model.internal;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sounds and particles named in text: a mob's own voice ({@code sound|volume|pitch})
 * and the sound and particle keys of an animation.
 *
 * <p>An animation's events are also plain words ({@code release}, {@code impact}),
 * so a name is only played when the server knows it as a sound or a particle:
 * an unknown key sent to a client is a warning in its log, not a sound.
 */
@ApiStatus.Internal
public final class Voices {

    /** A name that is neither, remembered as such. */
    private static final Object NOTHING = new Object();
    private static final Map<String, Object> KNOWN = new ConcurrentHashMap<>();

    private Voices() {
    }

    /**
     * Plays a {@code sound|volume|pitch} line at a place, heard as a hostile
     * mob is. Blank plays nothing; a name the server does not know is sent as
     * written, which is how a resource pack's own sound would be played.
     */
    public static void play(@Nullable String spec, @Nullable Location at) {
        if (spec == null || spec.isBlank() || at == null) {
            return;
        }
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        String[] parts = spec.split("\\|");
        String name = parts[0].trim();
        float volume = parts.length > 1 ? number(parts[1], 1f) : 1f;
        float pitch = parts.length > 2 ? number(parts[2], 1f) : 1f;
        Object known = resolve(name);
        if (known instanceof Sound sound) {
            world.playSound(at, sound, SoundCategory.HOSTILE, volume, pitch);
        } else {
            world.playSound(at, name.toLowerCase(Locale.ROOT), SoundCategory.HOSTILE, volume, pitch);
        }
    }

    /**
     * Plays an animation's event where it happens, when it names a sound or a
     * particle the server knows.
     *
     * @return whether it was one
     */
    public static boolean event(String name, Location at) {
        Object known = resolve(name);
        World world = at.getWorld();
        if (world == null) {
            return false;
        }
        if (known instanceof Sound sound) {
            world.playSound(at, sound, SoundCategory.HOSTILE, 1f, 1f);
            return true;
        }
        if (known instanceof Particle particle) {
            world.spawnParticle(particle, at, 6, 0.15, 0.15, 0.15, 0.02);
            return true;
        }
        return false;
    }

    /** A Bukkit sound name, a sound key, or a particle that needs no data; {@code NOTHING} for anything else. */
    static Object resolve(String name) {
        String trimmed = name.trim();
        return KNOWN.computeIfAbsent(trimmed, Voices::lookUp);
    }

    private static Object lookUp(String name) {
        if (name.isEmpty()) {
            return NOTHING;
        }
        try {
            Sound constant = (Sound) Sound.class.getField(name.toUpperCase(Locale.ROOT)).get(null);
            if (constant != null) {
                return constant;
            }
        } catch (ReflectiveOperationException | ClassCastException | LinkageError notAConstant) {
            // A key, or nothing.
        }
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
        if (key == null) {
            return NOTHING;
        }
        try {
            Sound sound = Registry.SOUNDS.get(key);
            if (sound != null) {
                return sound;
            }
            Particle particle = Registry.PARTICLE_TYPE.get(key);
            if (particle != null && particle.getDataType() == Void.class) {
                return particle;
            }
        } catch (RuntimeException | LinkageError noRegistry) {
            // A server without these registries plays nothing.
        }
        return NOTHING;
    }

    private static float number(String text, float fallback) {
        try {
            float value = Float.parseFloat(text.trim());
            return Float.isFinite(value) ? value : fallback;
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }
}
