package net.exylia.lib.util.sequence;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An effect that may or may not play.
 *
 * <pre>{@code
 * EffectEntry crit = EffectEntry.of(List.of(
 *                 "[SOUND] ENTITY_PLAYER_ATTACK_CRIT;1;1.4",
 *                 "[PARTICLE] CRIT;count:20"))
 *         .name("Critical hit")
 *         .chance(25.0)
 *         .condition("%player_level% >= 10")
 *         .nearby(12.0)
 *         .build();
 * }</pre>
 *
 * <h2>What it is, and what it is not</h2>
 * A {@link Sequence} says <em>what</em> happens. This says <em>whether</em> it
 * happens, <em>to whom</em>, and <em>when</em>. Nothing else: the payload is
 * sequence lines, so every effect the sequence syntax can express — particles,
 * sounds, potions, fireworks, titles, action bars, chat, lightning, explosions,
 * block breaks, commands, shapes, pauses — is expressible here without this
 * class knowing what any of them are.
 *
 * <h2>Why it is ten fields and not forty</h2>
 * ExyliaCommons' {@code EffectEntry} carried a field for every property of every
 * effect type it supported: eight types, forty fields, and a {@code switch} that
 * had to grow for a ninth. Its own javadoc said it mirrored {@code RewardEntry}.
 * Splitting it in two — the payload is a sequence, the gating is these fields —
 * leaves a value that never grows again, and a payload that already does more
 * than the forty fields did.
 *
 * <h2>Who sees it</h2>
 * {@link #radius()} says, and it says everything the old {@code EffectScope}
 * enum did:
 *
 * <table border="1">
 *   <caption>What a radius means</caption>
 *   <tr><th>Value</th><th>Audience</th></tr>
 *   <tr><td>{@code 0} or less</td><td>only the player it is about</td></tr>
 *   <tr><td>a finite number</td><td>everyone within that many blocks</td></tr>
 *   <tr><td>{@link #WHOLE_WORLD}</td><td>everyone in the world it happens in</td></tr>
 * </table>
 *
 * <p>One number rather than an enum and a number, because an enum whose meaning
 * is "read the other field" is two ways to say one thing and a way to say a
 * contradiction.
 *
 * @since 1.57.0
 */
public final class EffectEntry {

    /** What {@link #chance()} means when the effect always plays. */
    public static final double ALWAYS = 100.0;

    /** The radius that means everybody in the world the effect happens in. */
    public static final double WHOLE_WORLD = Double.POSITIVE_INFINITY;

    /** What an effect with no radius of its own reaches: the ecosystem default. */
    public static final double DEFAULT_RADIUS = SequenceTarget.DEFAULT_RADIUS;

    private final String id;
    private final String name;
    private final String icon;
    private final List<String> lines;
    private final double chance;
    private final String condition;
    private final String permission;
    private final int priority;
    private final long delayTicks;
    private final double radius;

    private EffectEntry(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.icon = builder.icon;
        this.lines = builder.lines;
        this.chance = builder.chance;
        this.condition = builder.condition;
        this.permission = builder.permission;
        this.priority = builder.priority;
        this.delayTicks = builder.delayTicks;
        this.radius = builder.radius;
    }

    // ------------------------------------------------------------- what it is

    /**
     * This effect's identity, stable across edits.
     *
     * @return the id
     */
    public @NotNull String id() {
        return id;
    }

    /** What an admin calls it, in Exylia text notation, or {@code null}. */
    public @Nullable String name() {
        return name;
    }

    /** The material or head an editor draws it as, or {@code null} to derive one. */
    public @Nullable String icon() {
        return icon;
    }

    /**
     * What happens, in the sequence notation.
     *
     * @return the lines, never {@code null}
     * @see Sequences
     */
    public @NotNull List<String> lines() {
        return lines;
    }

    /** Whether this effect would actually do anything. */
    public boolean isPlayable() {
        return !lines.isEmpty();
    }

    // ------------------------------------------------------------- its chances

    /**
     * The percentage chance of playing at all, {@code 0}&ndash;{@code 100}.
     *
     * @return the chance
     */
    public double chance() {
        return chance;
    }

    /** Whether this effect plays every time. */
    public boolean isGuaranteed() {
        return chance >= ALWAYS;
    }

    /** The condition that must hold, in the notation conditions are written in. */
    public @Nullable String condition() {
        return condition;
    }

    /** The permission the player it is about must have, or {@code null}. */
    public @Nullable String permission() {
        return permission;
    }

    /** Higher plays first. Effects of equal priority keep their written order. */
    public int priority() {
        return priority;
    }

    /**
     * How long after the trigger this starts, in ticks.
     *
     * <p>Separate from a {@code [DELAY]} line, and not the same thing: a delay
     * inside the sequence holds up the lines after it, while this holds up the
     * whole effect and lets the ones beside it play on time.
     *
     * @return the delay in ticks; {@code 0} for immediately
     */
    public long delayTicks() {
        return delayTicks;
    }

    /**
     * How far this reaches.
     *
     * @return the radius; see the table on this class
     */
    public double radius() {
        return radius;
    }

    /** Whether only the player it is about sees this. */
    public boolean isPrivate() {
        return radius <= 0.0;
    }

    // ---------------------------------------------------------------- deriving

    /**
     * The name to show, falling back to what it does.
     *
     * <p>Never {@code null}: an effect nobody named still has to read as
     * something in a menu, and one with nothing in it says so.
     *
     * @return something a human reads
     */
    public @NotNull String displayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return lines.isEmpty() ? "(nothing yet)" : lines.get(0);
    }

    // ---------------------------------------------------------------- building

    /**
     * A builder holding this effect's values, including its {@link #id()}.
     *
     * @return a builder
     */
    public @NotNull Builder toBuilder() {
        Builder builder = new Builder(lines);
        builder.id = id;
        builder.name = name;
        builder.icon = icon;
        builder.chance = chance;
        builder.condition = condition;
        builder.permission = permission;
        builder.priority = priority;
        builder.delayTicks = delayTicks;
        builder.radius = radius;
        return builder;
    }

    /**
     * The same effect under a new identity, for duplicating a row.
     *
     * @return the copy
     */
    public @NotNull EffectEntry copy() {
        return toBuilder().id(UUID.randomUUID().toString()).build();
    }

    /**
     * An effect that plays the given lines.
     *
     * @param lines the sequence, in the notation {@link Sequences} compiles
     * @return the builder
     */
    public static @NotNull Builder of(@NotNull List<String> lines) {
        return new Builder(lines);
    }

    /** An effect with nothing in it yet, for an editor's add button. */
    public static @NotNull EffectEntry blank() {
        return of(List.of()).build();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EffectEntry entry && id.equals(entry.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "EffectEntry[" + displayName() + ", chance=" + chance + ']';
    }

    /** Builds an {@link EffectEntry}. */
    public static final class Builder {

        private String id = UUID.randomUUID().toString();
        private String name;
        private String icon;
        private List<String> lines;
        private double chance = ALWAYS;
        private String condition;
        private String permission;
        private int priority;
        private long delayTicks;
        private double radius = DEFAULT_RADIUS;

        private Builder(List<String> lines) {
            this.lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        }

        /**
         * Overrides the generated identity, for reading a stored row.
         *
         * @param id the id
         * @return this builder
         */
        public @NotNull Builder id(@NotNull String id) {
            this.id = Objects.requireNonNull(id, "id");
            return this;
        }

        /**
         * What an admin calls it.
         *
         * @param name the name, or {@code null}
         * @return this builder
         */
        public @NotNull Builder name(@Nullable String name) {
            this.name = name;
            return this;
        }

        /**
         * What an editor draws it as.
         *
         * @param icon a material or head string, or {@code null}
         * @return this builder
         */
        public @NotNull Builder icon(@Nullable String icon) {
            this.icon = icon;
            return this;
        }

        /**
         * What happens.
         *
         * @param lines the sequence lines
         * @return this builder
         */
        public @NotNull Builder lines(@NotNull List<String> lines) {
            this.lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
            return this;
        }

        /**
         * The percentage chance of playing.
         *
         * @param chance {@code 0}&ndash;{@code 100}
         * @return this builder
         */
        public @NotNull Builder chance(double chance) {
            this.chance = chance;
            return this;
        }

        /**
         * What must hold before it plays.
         *
         * @param condition the condition, or {@code null}
         * @return this builder
         */
        public @NotNull Builder condition(@Nullable String condition) {
            this.condition = condition;
            return this;
        }

        /**
         * What the player must have.
         *
         * @param permission the node, or {@code null}
         * @return this builder
         */
        public @NotNull Builder permission(@Nullable String permission) {
            this.permission = permission;
            return this;
        }

        /**
         * Higher plays first.
         *
         * @param priority the priority
         * @return this builder
         */
        public @NotNull Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        /**
         * How long after the trigger it starts.
         *
         * @param delayTicks ticks; negative is read as none
         * @return this builder
         */
        public @NotNull Builder delayTicks(long delayTicks) {
            this.delayTicks = Math.max(0L, delayTicks);
            return this;
        }

        /**
         * Only the player it is about sees it.
         *
         * @return this builder
         */
        public @NotNull Builder onlyTheirs() {
            this.radius = 0.0;
            return this;
        }

        /**
         * Everyone within this many blocks sees it.
         *
         * @param blocks the radius
         * @return this builder
         */
        public @NotNull Builder nearby(double blocks) {
            this.radius = Math.max(0.0, blocks);
            return this;
        }

        /**
         * Everyone in the world it happens in sees it.
         *
         * @return this builder
         */
        public @NotNull Builder wholeWorld() {
            this.radius = WHOLE_WORLD;
            return this;
        }

        /**
         * The radius as a number, for reading a stored row field by field.
         *
         * @param radius the radius
         * @return this builder
         */
        public @NotNull Builder radius(double radius) {
            this.radius = Double.isNaN(radius) ? DEFAULT_RADIUS : Math.max(0.0, radius);
            return this;
        }

        /**
         * The finished effect.
         *
         * @return the effect
         */
        public @NotNull EffectEntry build() {
            return new EffectEntry(this);
        }
    }
}
