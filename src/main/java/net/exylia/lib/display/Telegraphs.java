package net.exylia.lib.display;

import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

/**
 * Warnings drawn on the ground: where something is about to land.
 *
 * <pre>{@code
 * Vfx slam = Vfx.at(mob.getLocation()).nearby(48);
 * Telegraphs.circle(slam, 0, mob.getLocation(), 5, 900, 0xFF9500);
 * }</pre>
 *
 * <p>Every shape is an outline plus a fill. The outline appears at once and
 * stays until the blow lands; the fill moves over the wind-up, so a player can
 * read not only where but <em>when</em>: an attack lands the moment the fill
 * reaches the edge. That is the whole point of a telegraph, and the reason it
 * is a timer rather than a mark.
 *
 * <p>The pieces are thin plates of stained glass laid {@value #LIFT} above the
 * ground, lit at 15 so they read at night and in caves, and outlined in the
 * tint so the colour holds against any floor. The glass is the dye colour
 * closest to the tint. Everything is gone when the wind-up ends.
 *
 * <p>With {@link Vfx#lod()} on, rings and arcs use half the plates.
 *
 * @since 1.197.0
 */
public final class Telegraphs {

    /** How far above the ground the plates are laid, clear of z-fighting. */
    static final double LIFT = 0.03;

    /** How thick a plate is. */
    static final double THICK = 0.02;

    /** How wide an outline is. */
    static final double EDGE = 0.12;

    /** How long an outline takes to appear. */
    static final long POP_MS = 120L;

    /** Roughly how long one plate of a ring is, in blocks. */
    private static final double PLATE = 0.45;

    /** Plates overlap a little, so a curve has no gaps between them. */
    private static final double OVERLAP = 1.08;

    private Telegraphs() {
    }

    /**
     * A disc: an outline at {@code radius} and a fill growing out to meet it.
     *
     * @param vfx        the effect to add to
     * @param atMillis   when it appears, from the start of the effect
     * @param centre     the middle, at ground level
     * @param radius     how far it reaches
     * @param fillMillis how long until it lands
     * @param argb       the tint, as {@code 0xRRGGBB}; alpha is ignored
     * @return the effect
     */
    public static @NotNull Vfx circle(@NotNull Vfx vfx, long atMillis, @NotNull Location centre,
                                      double radius, long fillMillis, int argb) {
        return round(vfx, atMillis, centre, radius, fillMillis, argb, true);
    }

    /**
     * A ring closing in: the outline at {@code radius} and a fill shrinking
     * from it to the middle.
     *
     * <p>For something drawn inwards, like a nova gathering before it breaks.
     *
     * @param vfx        the effect to add to
     * @param atMillis   when it appears, from the start of the effect
     * @param centre     the middle, at ground level
     * @param radius     how far it reaches
     * @param fillMillis how long until it lands
     * @param argb       the tint, as {@code 0xRRGGBB}; alpha is ignored
     * @return the effect
     */
    public static @NotNull Vfx ring(@NotNull Vfx vfx, long atMillis, @NotNull Location centre,
                                    double radius, long fillMillis, int argb) {
        return round(vfx, atMillis, centre, radius, fillMillis, argb, false);
    }

    /**
     * A wedge in front of something: two edges, an arc, and a fill sweeping
     * out from the apex.
     *
     * @param vfx        the effect to add to
     * @param atMillis   when it appears, from the start of the effect
     * @param apex       where the wedge starts, at ground level
     * @param yaw        which way it points, as a Minecraft yaw: 0 south, 90 west
     * @param radius     how far it reaches
     * @param degrees    how wide it opens
     * @param fillMillis how long until it lands
     * @param argb       the tint, as {@code 0xRRGGBB}; alpha is ignored
     * @return the effect
     */
    public static @NotNull Vfx cone(@NotNull Vfx vfx, long atMillis, @NotNull Location apex,
                                    float yaw, double radius, double degrees, long fillMillis,
                                    int argb) {
        DisplayModel plate = plate(argb);
        double facing = facing(yaw);
        double half = Math.toRadians(Math.clamp(degrees, 1.0, 360.0)) / 2;
        int plates = plates(radius * half * 2, 4, 32, vfx.lod());
        double length = radius * half * 2 / plates * OVERLAP;
        long life = Math.max(POP_MS, fillMillis);
        for (int index = 0; index < plates; index++) {
            double angle = facing - half + half * 2 * (index + 0.5) / plates;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            vfx.display(atMillis, plate, outline(x, z, tangent(angle), length, life), apex);
            vfx.display(atMillis, plate, sweep(0, 0, x, z, tangent(angle), 0.02, length,
                    life), apex);
        }
        if (half * 2 < Math.PI * 2 - 1.0E-6) {
            for (double side : new double[]{facing - half, facing + half}) {
                vfx.display(atMillis, plate, outline(Math.cos(side) * radius / 2,
                        Math.sin(side) * radius / 2, radial(side), radius, life), apex);
            }
        }
        return vfx;
    }

    /**
     * A strip from one point to another: two sides, two ends, and a fill
     * running from {@code from} to {@code to}.
     *
     * <p>Flat on the ground at {@code from}'s height, whatever the height of
     * {@code to}: a charge or a beam is dodged by stepping sideways.
     *
     * @param vfx        the effect to add to
     * @param atMillis   when it appears, from the start of the effect
     * @param from       where it starts, at ground level
     * @param to         where it ends
     * @param width      how wide, in blocks
     * @param fillMillis how long until it lands
     * @param argb       the tint, as {@code 0xRRGGBB}; alpha is ignored
     * @return the effect
     */
    public static @NotNull Vfx line(@NotNull Vfx vfx, long atMillis, @NotNull Location from,
                                    @NotNull Location to, double width, long fillMillis, int argb) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-3) {
            return vfx;
        }
        DisplayModel plate = plate(argb);
        double angle = Math.atan2(dz, dx);
        double ux = dx / length;
        double uz = dz / length;
        double side = Math.max(EDGE, width) / 2;
        long life = Math.max(POP_MS, fillMillis);
        for (int sign = -1; sign <= 1; sign += 2) {
            vfx.display(atMillis, plate, outline(dx / 2 - uz * side * sign, dz / 2 + ux * side * sign,
                    radial(angle), length, life), from);
        }
        vfx.display(atMillis, plate, outline(0, 0, tangent(angle), side * 2, life), from);
        vfx.display(atMillis, plate, outline(dx, dz, tangent(angle), side * 2, life), from);
        // The fill's middle travels half as far as its front: it grows from
        // nothing at the start, so its front is at the end when it is whole.
        return vfx.display(atMillis, plate, DisplayMotion.builder().life(life)
                .from(0, LIFT, 0).to(dx / 2, LIFT, dz / 2)
                .rotation(radial(angle))
                .scale(new double[]{0.02, THICK, side * 1.6},
                        new double[]{length, THICK, side * 1.6})
                .build(), from);
    }

    /**
     * An X on the ground, growing to full size over the wind-up.
     *
     * <p>The mark on the exact spot: what a meteor adds to its circle, so the
     * middle of the blast is readable from above.
     *
     * @param vfx        the effect to add to
     * @param atMillis   when it appears, from the start of the effect
     * @param centre     the middle, at ground level
     * @param length     how long each arm is, end to end
     * @param fillMillis how long until it lands
     * @param argb       the tint, as {@code 0xRRGGBB}; alpha is ignored
     * @return the effect
     */
    public static @NotNull Vfx cross(@NotNull Vfx vfx, long atMillis, @NotNull Location centre,
                                     double length, long fillMillis, int argb) {
        DisplayModel plate = plate(argb);
        double width = Math.max(EDGE, length * 0.12);
        long life = Math.max(POP_MS, fillMillis);
        for (double angle : new double[]{Math.PI / 4, -Math.PI / 4}) {
            vfx.display(atMillis, plate, DisplayMotion.builder().life(life)
                    .from(0, LIFT, 0).to(0, LIFT, 0)
                    .rotation(radial(angle))
                    .scale(new double[]{0.02, THICK, width}, new double[]{length, THICK, width})
                    .ease(DisplayMotion.Easing.OUT)
                    .build(), centre);
        }
        return vfx;
    }

    // ------------------------------------------------------------------ inside

    private static Vfx round(Vfx vfx, long atMillis, Location centre, double radius,
                             long fillMillis, int argb, boolean grow) {
        DisplayModel plate = plate(argb);
        int plates = plates(Math.PI * 2 * radius, 12, 40, vfx.lod());
        double length = Math.PI * 2 * radius / plates * OVERLAP;
        long life = Math.max(POP_MS, fillMillis);
        for (int index = 0; index < plates; index++) {
            double angle = Math.PI * 2 * index / plates;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            vfx.display(atMillis, plate, outline(x, z, tangent(angle), length, life), centre);
            // The fill moves as a ring of the same plates, each as long as its
            // share of the circle it is on at that moment.
            vfx.display(atMillis, plate, grow
                    ? sweep(0, 0, x, z, tangent(angle), 0.02, length, life)
                    : sweep(x, z, 0, 0, tangent(angle), length, 0.02, life), centre);
        }
        return vfx;
    }

    /** An outline plate: pops in along its own length, then holds. */
    static DisplayMotion outline(double x, double z, Rotation turn, double length, long life) {
        return DisplayMotion.chain(
                DisplayMotion.builder().life(POP_MS)
                        .from(x, LIFT, z).to(x, LIFT, z).rotation(turn)
                        .scale(new double[]{length * 0.3, THICK, EDGE},
                                new double[]{length, THICK, EDGE})
                        .ease(DisplayMotion.Easing.OUT).build(),
                DisplayMotion.still(Math.max(0L, life - POP_MS)));
    }

    /** A fill plate: travels at a steady rate, so its progress is the timer. */
    static DisplayMotion sweep(double fromX, double fromZ, double toX, double toZ, Rotation turn,
                               double fromLength, double toLength, long life) {
        return DisplayMotion.builder().life(life)
                .from(fromX, LIFT, fromZ).to(toX, LIFT, toZ).rotation(turn)
                .scale(new double[]{fromLength, THICK, EDGE * 0.75},
                        new double[]{toLength, THICK, EDGE * 0.75})
                .build();
    }

    /** How many plates a length of curve gets. */
    static int plates(double length, int least, int most, boolean lod) {
        int plates = (int) Math.clamp(Math.round(length / PLATE), least, most);
        return lod ? Math.max(least / 2 + 1, plates / 2) : plates;
    }

    /**
     * Turns a plate so its long side runs along the circle at {@code angle},
     * where the angle is measured from east towards south.
     */
    static Rotation tangent(double angle) {
        return Rotation.around(Rotation.Axis.Y, -angle - Math.PI / 2);
    }

    /** Turns a plate so its long side points out from the middle at {@code angle}. */
    static Rotation radial(double angle) {
        return Rotation.around(Rotation.Axis.Y, -angle);
    }

    /** A Minecraft yaw as an angle from east towards south. */
    static double facing(float yaw) {
        double radians = Math.toRadians(yaw);
        return Math.atan2(Math.cos(radians), -Math.sin(radians));
    }

    /** A lit glass plate in the dye closest to the tint, outlined in the tint. */
    private static DisplayModel plate(int argb) {
        int rgb = argb & 0xFFFFFF;
        return DisplayModel.block(glass(rgb).createBlockData()).glow(rgb).light(15);
    }

    /** The stained glass closest to a colour. */
    static @NotNull Material glass(int rgb) {
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
        return Material.valueOf(best.name() + "_STAINED_GLASS");
    }
}
