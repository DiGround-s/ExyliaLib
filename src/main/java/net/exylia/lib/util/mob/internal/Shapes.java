package net.exylia.lib.util.mob.internal;

import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.display.Vfx;
import net.exylia.lib.text.Colors;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * The pieces every skill style is drawn from: models, colours, sounds and the
 * few shapes more than one style needs.
 *
 * <p>Models are made once per material and kept; the set of materials is the
 * styles' own, so the maps stay a few dozen entries. Sounds are named by their
 * Bukkit constant and resolved once, through a seam, because naming a
 * {@link Sound} constant needs a live server's registry and the styles are
 * tested without one.
 */
final class Shapes {

    /** How an item model is made; a test swaps it, since an {@link ItemStack} needs a server. */
    static volatile Function<Material, ItemStack> items = ItemStack::new;

    /** The real lookup: the constant by its field name. */
    static final Function<String, @Nullable Sound> CONSTANTS = Shapes::constant;

    /** How a sound's constant is found by name; a test swaps it for one that records the names. */
    static volatile Function<String, @Nullable Sound> sounds = CONSTANTS;

    private static final Map<Material, DisplayModel> ITEMS = new ConcurrentHashMap<>();
    private static final Map<Material, DisplayModel> BLOCKS = new ConcurrentHashMap<>();
    private static final Map<String, Sound> SOUNDS = new ConcurrentHashMap<>();

    private Shapes() {
    }

    // ----------------------------------------------------------------- models

    /** An item model, lit, made once. */
    static DisplayModel item(Material material) {
        return ITEMS.computeIfAbsent(material, key -> DisplayModel.item(items.apply(key)).light(15));
    }

    /** A block model, made once; unlit, so it takes the light of where it is. */
    static DisplayModel block(Material material) {
        return BLOCKS.computeIfAbsent(material, key -> DisplayModel.block(MobBodies.block(key)));
    }

    /** A block model lit at 15 and outlined in a colour: what a spell is made of. */
    static DisplayModel glowing(Material material, int rgb) {
        return DisplayModel.block(MobBodies.block(material)).glow(rgb).light(15);
    }

    /** A block model lit at 15 with no outline: pieces of one shape, where an outline on each would flatten it. */
    static DisplayModel glowless(Material material) {
        return DisplayModel.block(MobBodies.block(material)).light(15);
    }

    /** The stained glass whose dye is nearest a colour. */
    static Material nearestGlass(int rgb) {
        DyeColor best = DyeColor.WHITE;
        long nearest = Long.MAX_VALUE;
        for (DyeColor dye : DyeColor.values()) {
            Color colour = dye.getColor();
            long red = colour.getRed() - ((rgb >> 16) & 0xFF);
            long green = colour.getGreen() - ((rgb >> 8) & 0xFF);
            long blue = colour.getBlue() - (rgb & 0xFF);
            long distance = red * red + green * green + blue * blue;
            if (distance < nearest) {
                nearest = distance;
                best = dye;
            }
        }
        Material glass = Material.matchMaterial(best.name() + "_STAINED_GLASS");
        return glass == null ? Material.WHITE_STAINED_GLASS : glass;
    }

    /** The stained glass whose dye is a material's colour: a mob's afterimage is its own colours, see-through. */
    static Material glass(Material material) {
        String name = material.name();
        for (String dye : DYES) {
            if (name.startsWith(dye + "_")) {
                Material glass = Material.matchMaterial(dye + "_STAINED_GLASS");
                if (glass != null) return glass;
            }
        }
        return switch (material) {
            case SNOW_BLOCK, BONE_BLOCK, QUARTZ_BLOCK, SANDSTONE, IRON_BLOCK -> Material.WHITE_STAINED_GLASS;
            case GOLD_BLOCK, HONEYCOMB_BLOCK -> Material.YELLOW_STAINED_GLASS;
            case MAGMA_BLOCK -> Material.ORANGE_STAINED_GLASS;
            case OBSIDIAN, BLACKSTONE, SOUL_SAND -> Material.BLACK_STAINED_GLASS;
            case PRISMARINE, DARK_PRISMARINE, PRISMARINE_BRICKS -> Material.CYAN_STAINED_GLASS;
            case SLIME_BLOCK, MOSS_BLOCK -> Material.LIME_STAINED_GLASS;
            case PURPUR_BLOCK -> Material.MAGENTA_STAINED_GLASS;
            default -> material.name().endsWith("_STAINED_GLASS") ? material : Material.LIGHT_GRAY_STAINED_GLASS;
        };
    }

    /** Longest first, so LIGHT_BLUE is not read as BLUE. */
    private static final List<String> DYES = List.of("LIGHT_BLUE", "LIGHT_GRAY", "MAGENTA", "ORANGE", "YELLOW",
            "PURPLE", "BROWN", "GREEN", "BLACK", "WHITE", "LIME", "PINK", "GRAY", "CYAN", "BLUE", "RED");

    // ---------------------------------------------------------------- colour

    /**
     * A colour as a theme or a tint writes it: a palette token ({@code {info}}
     * or {@code info}), {@code #rrggbb} or {@code <#rrggbb>}.
     *
     * @return the colour as {@code 0xRRGGBB}, or {@code -1} when it is none of those
     */
    static int colour(@Nullable String written) {
        if (written == null) return -1;
        String value = written.trim();
        if (value.startsWith("<") && value.endsWith(">")) value = value.substring(1, value.length() - 1);
        if (value.startsWith("{") && value.endsWith("}")) value = value.substring(1, value.length() - 1);
        if (value.isEmpty()) return -1;
        if (value.startsWith("#")) {
            TextColor hex = TextColor.fromHexString(value.toLowerCase(Locale.ROOT));
            return hex == null ? -1 : hex.value();
        }
        TextColor token = Colors.get(value);
        return token == null ? -1 : token.value();
    }

    /** Dust in a colour. */
    static Particle.DustOptions dust(int rgb, float size) {
        return new Particle.DustOptions(Color.fromRGB(rgb & 0xFFFFFF), size);
    }

    /** A colour mixed towards white: a core brighter than its halo. */
    static int lighter(int rgb, double amount) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        red += (int) ((255 - red) * amount);
        green += (int) ((255 - green) * amount);
        blue += (int) ((255 - blue) * amount);
        return (red << 16) | (green << 8) | blue;
    }

    // ----------------------------------------------------------------- sound

    /** A sound at the effect's origin; skipped when the name resolves to nothing. */
    static void sound(Vfx vfx, long atMillis, String name, float volume, float pitch) {
        Sound sound = resolve(name);
        if (sound != null) vfx.sound(atMillis, sound, volume, pitch);
    }

    /** A sound somewhere else. */
    static void sound(Vfx vfx, long atMillis, Location where, String name, float volume, float pitch) {
        Sound sound = resolve(name);
        if (sound != null) vfx.sound(atMillis, where, sound, volume, pitch);
    }

    static @Nullable Sound resolve(String name) {
        Function<String, Sound> resolver = sounds;
        if (resolver != CONSTANTS) return resolver.apply(name);
        return SOUNDS.computeIfAbsent(name, CONSTANTS);
    }

    /** The constant by its field name: the Bukkit name, never a key worked out by string rules. */
    private static @Nullable Sound constant(String name) {
        try {
            return (Sound) Sound.class.getField(name).get(null);
        } catch (ReflectiveOperationException | ClassCastException | LinkageError missing) {
            return null;
        }
    }

    // ---------------------------------------------------------------- shapes

    /**
     * Turns a model so its own +Z points along the ground at {@code angle},
     * measured from east towards south (the angle {@code cos, sin} is drawn at).
     */
    static Rotation facing(double angle) {
        return Rotation.around(Rotation.Axis.Y, Math.atan2(Math.cos(angle), Math.sin(angle)));
    }

    /**
     * Tilts a model's top away from the middle by {@code tilt} radians, for a
     * piece standing on a ring at {@code angle}: a spike leaning out of a nova.
     */
    static Rotation outward(double angle, double tilt) {
        return Rotation.around(Rotation.Axis.X, tilt).then(facing(angle));
    }

    /** A random lean of up to {@code most} radians and any turn: rubble never lands square. */
    static Rotation tilt(ThreadLocalRandom random, double most) {
        return Rotation.around(Rotation.Axis.X, random.nextDouble(-most, most))
                .then(Rotation.around(Rotation.Axis.Z, random.nextDouble(-most, most)))
                .then(Rotation.around(Rotation.Axis.Y, random.nextDouble(Math.PI * 2)));
    }

    /**
     * The rotation that lays a model's own +Z along a direction: pitched about
     * its X, then turned about Y, so it never rolls.
     */
    static Rotation along(double dx, double dy, double dz) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-9) return Rotation.NONE;
        double pitch = -Math.asin(Math.clamp(dy / length, -1.0, 1.0));
        return Rotation.around(Rotation.Axis.X, pitch).then(Rotation.around(Rotation.Axis.Y, Math.atan2(dx, dz)));
    }

    /**
     * Items laid end to end from one point to another, their own up along the
     * way: a chain's links, which {@link Vfx#beam} would lay across it.
     */
    static void links(Vfx vfx, long atMillis, Location from, Location to, DisplayModel model, double spacing,
                      long lifeMillis) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-3) return;
        Rotation turn = Rotation.around(Rotation.Axis.X, Math.PI / 2).then(along(dx, dy, dz));
        int pieces = Math.max(1, (int) Math.round(length / spacing));
        for (int piece = 0; piece < pieces; piece++) {
            double at = (piece + 0.5) / pieces;
            // Links pay out from the mob: the far ones appear last.
            long delay = Math.round(at * 90);
            DisplayMotion motion = DisplayMotion.builder().life(Math.max(50L, lifeMillis - delay))
                    .from(dx * at, dy * at, dz * at).to(dx * at, dy * at, dz * at)
                    .rotation(turn).scale(spacing * 1.15, spacing * 1.15).build();
            vfx.display(atMillis + delay, model, motion, from);
        }
    }

    /**
     * A bolt between two points: {@code segments} straight pieces joined at
     * points pushed off the line, least at the ends.
     */
    static List<Location> jagged(Location from, Location to, int segments, double swing, ThreadLocalRandom random) {
        List<Location> points = new ArrayList<>(segments + 1);
        for (int point = 0; point <= segments; point++) {
            double t = (double) point / segments;
            Location at = from.clone().add(to.clone().subtract(from).toVector().multiply(t));
            if (point > 0 && point < segments) {
                double sway = swing * Math.sin(Math.PI * t);
                at.add(random.nextDouble(-sway, sway), random.nextDouble(-sway, sway) * 0.6,
                        random.nextDouble(-sway, sway));
            }
            points.add(at);
        }
        return points;
    }

    /** Joined beams along a path: a bolt's core, or its halo. */
    static void bolt(Vfx vfx, long atMillis, List<Location> path, DisplayModel model, double thickness,
                     long lifeMillis) {
        for (int segment = 0; segment + 1 < path.size(); segment++) {
            vfx.beam(atMillis, path.get(segment), path.get(segment + 1), model, thickness, lifeMillis);
        }
    }

    /** Particles on a flat circle, each flung outwards when {@code speed} is above zero. */
    static void circle(Vfx vfx, long atMillis, Particle particle, Location centre, double radius, int points,
                       double speed, @Nullable Object data) {
        for (int point = 0; point < points; point++) {
            double angle = Math.PI * 2 * point / points;
            double x = Math.cos(angle);
            double z = Math.sin(angle);
            Location at = centre.clone().add(x * radius, 0, z * radius);
            if (speed > 0 && data == null) {
                // Count 0: the offsets are a direction and the speed its length.
                vfx.particle(atMillis, particle, at, 0, x, 0.05, z, speed, null);
            } else {
                vfx.particle(atMillis, particle, at, 1, 0, 0, 0, 0, data);
            }
        }
    }

    /**
     * A piece flung from a point that lands on the ground, rests and melts:
     * the rubble of every style, the same arc the reactions draw.
     */
    static DisplayMotion thrown(double x, double y, double z, double dx, double dz, double size, long life,
                                ThreadLocalRandom random, long rest) {
        return MobReactions.thrown(x, y, z, dx, dz, size / 2, life, 16, size, tilt(random, 0.8), random, rest);
    }
}
