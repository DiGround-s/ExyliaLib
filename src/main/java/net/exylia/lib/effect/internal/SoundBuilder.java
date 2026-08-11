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

        if (Packets.available()) {
            return PacketSender.sound(viewer, name, category,
                    where.getX(), where.getY(), where.getZ(), volume, pitch);
        }
        return fallback(viewer, where);
    }

    /**
     * Plays through the Bukkit API when packets are unavailable.
     *
     * <p>The string form is used rather than the enum: Bukkit accepts a
     * namespaced key directly, which avoids depending on an enum whose constants
     * move between versions.
     */
    private boolean fallback(Player viewer, Location where) {
        try {
            viewer.playSound(where, bukkitName(), volume, pitch);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Bukkit's string form is the lower-case dotted key. */
    private String bukkitName() {
        String trimmed = name.trim().toLowerCase(Locale.ROOT);
        if (trimmed.indexOf(':') >= 0) {
            trimmed = trimmed.substring(trimmed.indexOf(':') + 1);
        }
        return trimmed.indexOf('.') >= 0 ? trimmed : trimmed.replace('_', '.');
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
