package net.exylia.lib.util.mob;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * How a model mob moves and thinks: what it is, rather than which vanilla mob
 * it hides.
 *
 * <pre>{@code
 * MobModel golem = MobModel.of("stone_golem")
 *         .withBody(MobBody.of(MobBody.Kind.HEAVY).withSpeed(2.2));
 * MobModel wisp = MobModel.of("wisp")
 *         .withBody(MobBody.of(MobBody.Kind.HOVER).withKeepDistance(6).withHover(1.8));
 * }</pre>
 *
 * <p>With any kind but {@link Kind#VANILLA} the engine picks the mob under the
 * model itself and empties it: its vanilla AI goes (no zombie hunting
 * villagers, no creeper fuse, no skeleton bow), its box is scaled to the
 * model's size so paths, walls, arrows and explosions meet the model, and it
 * moves with the kind's physics. What it does instead is the engine's own:
 * it roams, hunts players only (with {@link MobFlag#AGGRESSIVE}; otherwise only
 * whoever hurts it, and never with {@link MobFlag#PASSIVE}), keeps its
 * distance, flees, goes home past its roam, and strikes with the model's own
 * attacks. The template's {@code type} is not used.
 *
 * <p>The vanilla AI is replaced through Paper's mob goals; on Spigot the body is
 * still scaled and given its physics, but keeps its vanilla AI (reported once).
 *
 * @param kind         how it moves
 * @param speed        blocks a second; {@code 0} is the kind's own
 * @param keepDistance blocks it keeps from its target, as an archer or a caster
 *                     does; {@code 0} closes in to strike
 * @param fleeBelow    the share of its health below which it runs from its
 *                     target, {@code 0-1}; {@code 0} never
 * @param hover        blocks above the ground a {@link Kind#HOVER} floats at, or a
 *                     {@link Kind#FLYER} rests at; {@code 0} is the kind's own
 * @since 1.326.0
 */
public record MobBody(@NotNull Kind kind, double speed, double keepDistance, double fleeBelow, double hover) {

    /** How a body moves. */
    public enum Kind {
        /** The vanilla mob's own body, AI and box, as before 1.326.0. */
        VANILLA(0, 0),
        /** Walks: people, beasts. */
        WALKER(3.0, 0),
        /** Walks slowly, steps up whole blocks, shrugs off knockback and falls: golems, giants. */
        HEAVY(2.0, 0),
        /** Walks, and climbs straight up walls: spiders, crawlers. */
        CLIMBER(3.4, 0),
        /** Flies anywhere, at its target's height when hunting. */
        FLYER(4.0, 2.5),
        /** Floats a little above the ground and drifts: wisps, spirits. */
        HOVER(2.5, 1.5),
        /** Swims; stranded on land it dries out. */
        SWIMMER(4.0, 0),
        /** Never moves: turrets, totems, plants. It still turns and strikes. */
        STATIC(0, 0);

        private final double speed;
        private final double hover;

        Kind(double speed, double hover) {
            this.speed = speed;
            this.hover = hover;
        }

        /** Its own speed, blocks a second. */
        public double speed() {
            return speed;
        }

        /** Its own height over the ground, blocks. */
        public double hover() {
            return hover;
        }

        /** Whether it moves through the air rather than over the ground. */
        public boolean flies() {
            return this == FLYER || this == HOVER;
        }

        /** The kind by name, in any case; {@code null} for none. */
        public static Kind of(String name) {
            if (name == null) return null;
            try {
                return valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
    }

    /** The vanilla body. */
    public static final MobBody VANILLA = new MobBody(Kind.VANILLA, 0, 0, 0, 0);

    public MobBody {
        kind = kind == null ? Kind.VANILLA : kind;
        speed = Double.isFinite(speed) ? Math.clamp(speed, 0, 40) : 0;
        keepDistance = Double.isFinite(keepDistance) ? Math.clamp(keepDistance, 0, 32) : 0;
        fleeBelow = Double.isFinite(fleeBelow) ? Math.clamp(fleeBelow, 0, 1) : 0;
        hover = Double.isFinite(hover) ? Math.clamp(hover, 0, 16) : 0;
    }

    /** A body of a kind with every setting its own. */
    public static @NotNull MobBody of(@NotNull Kind kind) {
        return new MobBody(kind, 0, 0, 0, 0);
    }

    /** Whether the engine runs it, rather than the vanilla mob it hides. */
    public boolean custom() {
        return kind != Kind.VANILLA;
    }

    /** Blocks a second it moves at: its own, else its kind's. */
    public double pace() {
        return kind == Kind.STATIC ? 0 : speed > 0 ? speed : kind.speed();
    }

    /** Blocks over the ground it floats at: its own, else its kind's. */
    public double height() {
        return hover > 0 ? hover : kind.hover();
    }

    public @NotNull MobBody withKind(@NotNull Kind kind) {
        return new MobBody(kind, speed, keepDistance, fleeBelow, hover);
    }

    public @NotNull MobBody withSpeed(double speed) {
        return new MobBody(kind, speed, keepDistance, fleeBelow, hover);
    }

    public @NotNull MobBody withKeepDistance(double keepDistance) {
        return new MobBody(kind, speed, keepDistance, fleeBelow, hover);
    }

    public @NotNull MobBody withFleeBelow(double fleeBelow) {
        return new MobBody(kind, speed, keepDistance, fleeBelow, hover);
    }

    public @NotNull MobBody withHover(double hover) {
        return new MobBody(kind, speed, keepDistance, fleeBelow, hover);
    }
}
