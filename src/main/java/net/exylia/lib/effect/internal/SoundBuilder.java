package net.exylia.lib.effect.internal;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Builds a sound.
 *
 * <pre>{@code
 * Effects.sound("ENTITY_PLAYER_LEVELUP").volume(0.6).pitch(1.4).show(player);
 * }</pre>
 *
 * <p>Played per player, so a confirmation only the acting player should hear
 * does not carry to everyone nearby.
 *
 * @since 1.4.0
 */
public final class SoundBuilder {

    private final String name;
    private Location location;
    private String category = "MASTER";
    private float volume = 1f;
    private float pitch = 1f;

    public SoundBuilder(String name) {
        this.name = name;
    }

    /**
     * Plays the sound at a position rather than at the listener.
     *
     * @param location where it comes from
     * @return this builder
     */
    public @NotNull SoundBuilder at(@NotNull Location location) {
        this.location = location;
        return this;
    }

    /**
     * Sets how loud the sound is.
     *
     * <p>Above 1 does not make it louder; it makes it audible from further
     * away.
     *
     * @param volume the volume
     * @return this builder
     */
    public @NotNull SoundBuilder volume(double volume) {
        this.volume = (float) volume;
        return this;
    }

    /**
     * Sets the pitch, from 0.5 to 2.
     *
     * @param pitch the pitch
     * @return this builder
     */
    public @NotNull SoundBuilder pitch(double pitch) {
        this.pitch = (float) Math.clamp(pitch, 0.5, 2.0);
        return this;
    }

    /**
     * Sets which volume slider controls this sound.
     *
     * @param category one of {@code MASTER}, {@code MUSIC}, {@code RECORDS},
     *                 {@code WEATHER}, {@code BLOCKS}, {@code HOSTILE},
     *                 {@code NEUTRAL}, {@code PLAYERS}, {@code AMBIENT} or
     *                 {@code VOICE}
     * @return this builder
     */
    public @NotNull SoundBuilder category(@NotNull String category) {
        this.category = category;
        return this;
    }

    /**
     * Plays the sound for one player.
     *
     * @param viewer who hears it
     * @return whether the sound name was recognised
     */
    public boolean show(@NotNull Player viewer) {
        Location where = location != null ? location : viewer.getLocation();

        // A false from the packet path means the registry did not know the
        // name, not that the sound played; an exception means the classloader
        // cannot see PacketEvents, which a PlugMan-style load produces. Either
        // way Bukkit is the difference between silence and the sound the
        // config asked for.
        String key = resolvedKey();
        try {
            if (Packets.available() && PacketSender.sound(viewer, key, category,
                    where.getX(), where.getY(), where.getZ(), volume, pitch)) {
                return true;
            }
        } catch (Throwable ignored) {
            // Fall through to the Bukkit API.
        }
        return fallback(viewer, where, key);
    }

    /**
     * Plays through the Bukkit API when packets are unavailable.
     *
     * <p>The string form is used rather than the enum: Bukkit accepts a
     * namespaced key directly, which avoids depending on an enum whose constants
     * move between versions.
     */
    private boolean fallback(Player viewer, Location where, String key) {
        try {
            viewer.playSound(where, key, volume, pitch);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Resolves the written name to the key the client knows.
     *
     * <p>The mapping is not mechanical: {@code ENTITY_PLAYER_LEVELUP} is
     * {@code entity.player.levelup} but {@code BLOCK_NOTE_BLOCK_PLING} is
     * {@code block.note_block.pling}, with an underscore inside the key. Turning
     * every underscore into a dot — the obvious rule — invents a sound that does
     * not exist, and the client answers with silence. That is exactly the bug a
     * live server heard. So a Bukkit enum name is resolved through the enum,
     * which knows its own key, and anything else is treated as a key already,
     * namespace intact.
     */
    /**
     * Test seam: the enum-backed resolution needs a live server registry, so
     * tests inject the answer the enum would give.
     */
    static volatile java.util.function.UnaryOperator<String> keyResolver;

    /**
     * The key this sound resolves to, without playing it.
     *
     * <p>For callers that need the key rather than the sound: an item that eats
     * with a configured sound stores the key in a data component, and resolving
     * it a second way would reintroduce the bug this method exists to avoid.
     *
     * @return the namespaced key
     */
    public @NotNull String key() {
        return resolvedKey();
    }

    /**
     * Resolved keys by written name.
     *
     * <p>{@code Sound.valueOf} on Paper goes through a legacy field-rename
     * shim that searches the enum's fields by reflection, and a sound plays
     * on every hit and every click. The registry does not change while the
     * server runs, and the names come from configs, so this stays small.
     */
    private static final java.util.Map<String, String> RESOLVED = new java.util.concurrent.ConcurrentHashMap<>();

    private String resolvedKey() {
        java.util.function.UnaryOperator<String> injected = keyResolver;
        if (injected != null) {
            return injected.apply(name.trim());
        }
        String trimmed = name.trim();
        String known = RESOLVED.get(trimmed);
        if (known != null) {
            return known;
        }
        String resolved = resolveKey(trimmed);
        RESOLVED.put(trimmed, resolved);
        return resolved;
    }

    private static String resolveKey(String trimmed) {
        try {
            org.bukkit.Sound sound = org.bukkit.Sound.valueOf(trimmed.toUpperCase(Locale.ROOT));
            org.bukkit.NamespacedKey key = org.bukkit.Registry.SOUNDS.getKey(sound);
            if (key != null) {
                return key.toString();
            }
            return trimmed.toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            // Not an enum name — already a key — or no registry behind the
            // enum at all. Either way the written text is the best key there
            // is, and a namespace in it is kept on purpose: stripping one
            // sends a resource pack's sound to minecraft:, where it is not.
            return trimmed.toLowerCase(Locale.ROOT);
        }
    }

    /** Plays the sound for everybody online. */
    public void showAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            show(player);
        }
    }

    /**
     * Plays the sound for everybody within range of its position.
     *
     * @param radius how far away a player can be and still hear it, in blocks
     */
    public void showNearby(double radius) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        double squared = radius * radius;
        for (Player player : location.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(location) <= squared) {
                show(player);
            }
        }
    }
}
