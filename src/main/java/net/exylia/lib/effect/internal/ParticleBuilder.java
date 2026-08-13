package net.exylia.lib.effect.internal;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Builds a particle effect.
 *
 * <pre>{@code
 * Effects.particle("FLAME").count(20).spread(0.3).at(location).showAll();
 * }</pre>
 *
 * <p>Particles are the clearest case for packets: shown this way they are drawn
 * by the client and the server never spawns anything. That also makes them
 * per-player, so a selection outline or a preview can be shown to one person
 * without anyone else seeing it.
 *
 * @since 1.4.0
 */
public final class ParticleBuilder {

    private final String name;
    private Location location;
    private int count = 1;
    private float offsetX;
    private float offsetY;
    private float offsetZ;
    private float speed;
    private boolean longDistance;

    public ParticleBuilder(String name) {
        this.name = name;
    }

    /**
     * Sets where the particles appear.
     *
     * @param location the position
     * @return this builder
     */
    public @NotNull ParticleBuilder at(@NotNull Location location) {
        this.location = location;
        return this;
    }

    /**
     * Sets how many particles to draw.
     *
     * @param count the number
     * @return this builder
     */
    public @NotNull ParticleBuilder count(int count) {
        this.count = Math.max(0, count);
        return this;
    }

    /**
     * Spreads the particles evenly around the position.
     *
     * @param radius how far they scatter, in blocks
     * @return this builder
     */
    public @NotNull ParticleBuilder spread(double radius) {
        float value = (float) radius;
        this.offsetX = value;
        this.offsetY = value;
        this.offsetZ = value;
        return this;
    }

    /**
     * Spreads the particles by a different amount on each axis.
     *
     * @param x sideways spread
     * @param y vertical spread
     * @param z forward spread
     * @return this builder
     */
    public @NotNull ParticleBuilder spread(double x, double y, double z) {
        this.offsetX = (float) x;
        this.offsetY = (float) y;
        this.offsetZ = (float) z;
        return this;
    }

    /**
     * Sets how fast the particles move.
     *
     * @param speed the speed, meaning depends on the particle
     * @return this builder
     */
    public @NotNull ParticleBuilder speed(double speed) {
        this.speed = (float) speed;
        return this;
    }

    /**
     * Makes the particles visible from further than the usual limit.
     *
     * <p>The client normally hides particles past about 32 blocks; this raises
     * it to around 512, for something meant to be seen across a map.
     *
     * @return this builder
     */
    public @NotNull ParticleBuilder farAway() {
        this.longDistance = true;
        return this;
    }

    /**
     * Shows the particles to one player.
     *
     * @param viewer who sees them
     * @return whether the particle name was recognised
     */
    public boolean show(@NotNull Player viewer) {
        Location where = location != null ? location : viewer.getLocation();

        try {
            if (Packets.available() && PacketSender.particle(viewer, name,
                    where.getX(), where.getY(), where.getZ(),
                    offsetX, offsetY, offsetZ, speed, count, longDistance)) {
                return true;
            }
        } catch (Throwable ignored) {
            // Fall through to the Bukkit API.
        }
        return fallback(viewer, where);
    }

    /**
     * Draws through the Bukkit API when packets are unavailable.
     *
     * <p>Bukkit's enum is looked up by name because a config holds a string, and
     * an unknown name has to be reported rather than throwing.
     */
    private boolean fallback(Player viewer, Location where) {
        try {
            org.bukkit.Particle particle = org.bukkit.Particle.valueOf(bukkitName());
            viewer.spawnParticle(particle, where, count, offsetX, offsetY, offsetZ, speed);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** Turns a key such as {@code minecraft:flame} back into {@code FLAME}. */
    private String bukkitName() {
        String trimmed = name.trim();
        int colon = trimmed.indexOf(':');
        if (colon >= 0) {
            trimmed = trimmed.substring(colon + 1);
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    /**
     * Shows the particles to everybody online.
     *
     * <p>Sent per player rather than as one world-wide effect, which is what
     * keeps the server from spawning anything.
     */
    public void showAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            show(player);
        }
    }

    /**
     * Shows the particles to everybody within range of the position.
     *
     * @param radius how far away a player can be and still see them, in blocks
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
