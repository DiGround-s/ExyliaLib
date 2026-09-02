package net.exylia.lib.util.sequence.internal;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * How one particle is drawn, resolved once.
 *
 * <p>Minecraft asks for a different extra argument depending on the particle:
 * dust wants a colour and a size, a block crack wants block data, and most want
 * nothing at all. Working that out per particle per player per frame is the
 * kind of cost that only shows up with forty players in an arena, so it is
 * decided here, at compile time, and reused.
 *
 * <h2>Colour</h2>
 * ExyliaLib's own {@code ParticleBuilder} has no colour support, which is why
 * this exists rather than delegating: every {@code DUST} line in the ecosystem
 * carries one, and without it the most common coloured effect cannot be drawn
 * at all.
 */
final class ParticlePaint implements Paint {

    /** Names change between versions; the first one that resolves wins. */
    static final Particle EXPLOSION = firstOf("EXPLOSION", "EXPLOSION_LARGE", "EXPLOSION_EMITTER");
    static final Particle BLOCK = firstOf("BLOCK", "BLOCK_CRACK");
    static final Particle FLASH = firstOf("FLASH");
    static final Particle SPARK = firstOf("ELECTRIC_SPARK", "CRIT");

    private final Particle particle;
    private final Object data;
    private final int count;
    private final double spreadX;
    private final double spreadY;
    private final double spreadZ;
    private final double speed;

    ParticlePaint(Particle particle, @Nullable Object data, int count,
                  double spreadX, double spreadY, double spreadZ, double speed) {
        this.particle = particle;
        this.data = data;
        this.count = count;
        this.spreadX = spreadX;
        this.spreadY = spreadY;
        this.spreadZ = spreadZ;
        this.speed = speed;
    }

    /**
     * Resolves a particle by name.
     *
     * @return the particle, or {@code null} when this server has no such one
     */
    static @Nullable Particle particle(@NotNull String name) {
        try {
            return Particle.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static @Nullable Particle firstOf(String... candidates) {
        for (String name : candidates) {
            Particle found = particle(name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * The extra argument this particle needs, or {@code null} when it needs none.
     *
     * <p>A dust particle without a colour is drawn white rather than skipped.
     * ExyliaCommons returned null and then refused to draw the line at all, so
     * a forgotten {@code color:} silently deleted the effect; white is visible,
     * obviously wrong, and leads whoever wrote the file straight to it.
     */
    static @Nullable Object dataFor(@NotNull Particle particle, @Nullable Color colour, float size,
                                    @Nullable Material material) {
        Class<?> type = particle.getDataType();
        Color used = colour != null ? colour : Color.WHITE;
        if (type == Particle.DustOptions.class) {
            return new Particle.DustOptions(used, size);
        }
        if (type == Particle.DustTransition.class) {
            return new Particle.DustTransition(used, Color.WHITE, size);
        }
        if (type == Color.class) {
            return used;
        }
        if (type == Float.class) {
            return size;
        }
        if (type == Integer.class) {
            return 0;
        }
        if (type == org.bukkit.block.data.BlockData.class) {
            return (material != null ? material : Material.STONE).createBlockData();
        }
        if (type == org.bukkit.inventory.ItemStack.class) {
            return new org.bukkit.inventory.ItemStack(material != null ? material : Material.SNOWBALL);
        }
        return null;
    }

    /** Draws this at one place, for the players given. */
    void draw(@NotNull List<Player> observers, @NotNull Location where) {
        for (Player observer : observers) {
            observer.spawnParticle(particle, where, count, spreadX, spreadY, spreadZ, speed, data);
        }
    }

    /** Draws this at a point offset from an anchor, for the players given. */
    @Override
    public void drawAt(@NotNull List<Player> observers, @NotNull Location anchor,
                       double x, double y, double z) {
        // One Location reused across observers: spawnParticle reads it and does
        // not keep it, and a shape of 600 points would otherwise allocate 600
        // Locations per observer per frame.
        Location where = anchor.clone().add(x, y, z);
        for (Player observer : observers) {
            observer.spawnParticle(particle, where, count, spreadX, spreadY, spreadZ, speed, data);
        }
    }

    /** A single unspread particle, for shapes, where the point is the position. */
    static ParticlePaint point(Particle particle, @Nullable Object data, int count) {
        return new ParticlePaint(particle, data, count, 0, 0, 0, 0);
    }

    /** Whether the server knows this particle at all. */
    static boolean known(@Nullable Particle particle) {
        return particle != null;
    }

    static @NotNull Object blockData(@NotNull Material material) {
        return Bukkit.createBlockData(material);
    }
}
