package net.exylia.lib.util.mob.internal;

import net.exylia.lib.util.mob.MobBody;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The vanilla mob hidden under a model with a {@link MobBody}, and how it is
 * sized and weighted.
 *
 * <h2>Which mob</h2>
 * One whose navigation moves the way the kind does, and whose own behaviour
 * lives in goals (which the engine removes) rather than in its tick or a brain
 * (which nothing can switch off):
 * <ul>
 *   <li>walkers: a wandering trader, an iron golem or a pig, whichever is
 *       shaped most like the model, tall and thin to wide and low, so the box
 *       the server scales to the model covers as much of it as one box can;</li>
 *   <li>climbers: a spider, the only mob whose navigation climbs walls;</li>
 *   <li>flyers and hoverers: a bee, which flies by navigation and holds still
 *       in the air when it stops;</li>
 *   <li>swimmers: a dolphin, which swims by navigation and needs air the
 *       engine keeps topped up.</li>
 * </ul>
 * None of them is a monster but the spider, so they are not removed on a
 * peaceful server; a climber is.
 *
 * <p>Pure: no server is asked, so every choice can be asserted.
 */
final class Carriers {

    /** A vanilla mob and its box at scale 1, in blocks. */
    record Carrier(@NotNull EntityType type, double width, double height) {

        /**
         * The scale that fits its box inside a model of this size: as big as it
         * can be without sticking out of the model anywhere, so nothing outside
         * the model is ever hit.
         */
        double fit(double modelWidth, double modelHeight) {
            double scale = Math.min(modelHeight / height, modelWidth / width);
            return Double.isFinite(scale) && scale > 0 ? Math.clamp(scale, MIN_SCALE, MAX_SCALE) : 1;
        }
    }

    /** The scale attribute's range. */
    static final double MIN_SCALE = 0.0625;
    static final double MAX_SCALE = 16;

    private static final List<Carrier> WALKERS = List.of(
            new Carrier(EntityType.WANDERING_TRADER, 0.6, 1.95),
            new Carrier(EntityType.IRON_GOLEM, 1.4, 2.7),
            new Carrier(EntityType.PIG, 0.9, 0.9));
    private static final Carrier CLIMBER = new Carrier(EntityType.SPIDER, 1.4, 0.9);
    private static final Carrier FLYER = new Carrier(EntityType.BEE, 0.7, 0.6);
    private static final Carrier SWIMMER = new Carrier(EntityType.DOLPHIN, 0.9, 0.6);

    /**
     * Blocks a second per point of the movement speed attribute, as a mob
     * following a path at speed 1 covers ground: a calibration knob, measured
     * against vanilla walkers.
     */
    static final double SPEED_PER_POINT = 10;

    private Carriers() {
    }

    /**
     * The mob that carries a model of this size moving this way.
     *
     * @param width  the model's width as drawn, blocks
     * @param height the model's height as drawn, blocks
     */
    static @NotNull Carrier of(@NotNull MobBody.Kind kind, double width, double height) {
        return switch (kind) {
            case CLIMBER -> CLIMBER;
            case FLYER, HOVER -> FLYER;
            case SWIMMER -> SWIMMER;
            default -> nearestShape(width, height);
        };
    }

    /** The walker whose height over width is closest to the model's, on a log scale. */
    private static Carrier nearestShape(double width, double height) {
        double aspect = Math.log(Math.max(0.05, height) / Math.max(0.05, width));
        Carrier best = WALKERS.getFirst();
        double bestGap = Double.MAX_VALUE;
        for (Carrier carrier : WALKERS) {
            double gap = Math.abs(aspect - Math.log(carrier.height() / carrier.width()));
            if (gap < bestGap) {
                bestGap = gap;
                best = carrier;
            }
        }
        return best;
    }

    /**
     * The attribute base values a body moves with, by registry key: how fast,
     * how high it steps, how hard it is to push, how far it falls unhurt.
     *
     * @param height the model's height as drawn, blocks
     */
    static @NotNull Map<String, Double> physics(@NotNull MobBody body, double height) {
        Map<String, Double> values = new LinkedHashMap<>();
        double speed = body.pace() / SPEED_PER_POINT;
        MobBody.Kind kind = body.kind();
        values.put("movement_speed", speed);
        if (kind.flies()) values.put("flying_speed", speed);
        double tall = Math.max(0.3, height);
        values.put("step_height", kind == MobBody.Kind.HEAVY ? Math.clamp(tall * 0.4, 1.0, 2.0)
                : Math.clamp(tall * 0.3, 0.6, 1.25));
        values.put("knockback_resistance", switch (kind) {
            case STATIC -> 1.0;
            case HEAVY -> 0.85;
            default -> Math.clamp((tall - 1.5) * 0.15, 0, 0.6);
        });
        values.put("safe_fall_distance", kind == MobBody.Kind.HEAVY ? 8.0 : 3 + tall);
        values.put("follow_range", 32.0);
        if (kind == MobBody.Kind.SWIMMER) values.put("water_movement_efficiency", 1.0);
        return values;
    }
}
