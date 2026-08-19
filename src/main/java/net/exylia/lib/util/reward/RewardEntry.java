package net.exylia.lib.util.reward;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * One reward, exactly as it is stored.
 *
 * <p>This is a value, not a live thing: it holds what a server owner configured
 * and nothing about who is receiving it or when. Giving it out is
 * {@link PluginRewards#give}'s job, and the same entry is handed to every player
 * who earns it.
 *
 * <pre>{@code
 * RewardEntry coins = RewardEntry.economy("500")
 *         .name("{primary}&lSTARTER BONUS")
 *         .chance(50.0)
 *         .build();
 * }</pre>
 *
 * <h2>Immutable, unlike the one it replaces</h2>
 * ExyliaCommons' {@code RewardEntry} was a mutable Lombok bean shared between
 * the config that loaded it, the executor that read it and the editor menu that
 * wrote to it. Here an edit produces a new entry through {@link #toBuilder()},
 * so an editor cannot change a reward out from under a delivery already in
 * flight, and the same entry can be read from several threads without a lock.
 *
 * <h2>The stored form is not ours to choose</h2>
 * Rows written by ExyliaCommons already exist in production databases, so the
 * field names {@link RewardCodec} reads and writes are fixed. See that class for
 * the format; this one only describes what the fields mean.
 *
 * @since 1.33.0
 */
public final class RewardEntry {

    /** What {@link #chance()} means when the reward is guaranteed. */
    public static final double ALWAYS = 100.0;

    private final String id;
    private final String name;
    private final RewardType type;

    private final String command;
    private final String itemSnapshot;
    private final String message;
    private final String icon;
    private final String value;
    private final String currency;

    private final int itemAmount;
    private final Integer minAmount;
    private final Integer maxAmount;

    private final double chance;
    private final double weight;

    private final String condition;
    private final String permission;
    private final String deliveryMessage;

    private final int priority;

    private RewardEntry(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.type = builder.type;
        this.command = builder.command;
        this.itemSnapshot = builder.itemSnapshot;
        this.message = builder.message;
        this.icon = builder.icon;
        this.value = builder.value;
        this.currency = builder.currency;
        this.itemAmount = builder.itemAmount;
        this.minAmount = builder.minAmount;
        this.maxAmount = builder.maxAmount;
        this.chance = builder.chance;
        this.weight = builder.weight;
        this.condition = builder.condition;
        this.permission = builder.permission;
        this.deliveryMessage = builder.deliveryMessage;
        this.priority = builder.priority;
    }

    // ------------------------------------------------------------- what it is

    /**
     * This reward's identity, stable across edits.
     *
     * <p>What a delivery result points back at, and what an editor menu uses to
     * find the row a player clicked. Generated when the entry is first built and
     * carried by {@link #toBuilder()}; only {@link #copy()} makes a new one.
     */
    public @NotNull String id() {
        return id;
    }

    /** The name a menu shows, in Exylia text notation, or {@code null}. */
    public @Nullable String name() {
        return name;
    }

    /** What this reward gives. */
    public @NotNull RewardType type() {
        return type;
    }

    // ------------------------------------------------------------ its payload

    /** The command to run, for {@link RewardType#COMMAND}. */
    public @Nullable String command() {
        return command;
    }

    /** The serialised item, for {@link RewardType#ITEM}. */
    public @Nullable String itemSnapshot() {
        return itemSnapshot;
    }

    /** The message to send, for {@link RewardType#MESSAGE}. */
    public @Nullable String message() {
        return message;
    }

    /**
     * The payload of a type ExyliaCommons did not have.
     *
     * <p>An amount for {@link RewardType#ECONOMY} and
     * {@link RewardType#EXPERIENCE}, and a potion string such as
     * {@code SPEED:1:300} for {@link RewardType#POTION}.
     *
     * @since 1.33.0
     */
    public @Nullable String value() {
        return value;
    }

    /**
     * Which currency an {@link RewardType#ECONOMY} reward pays into, or
     * {@code null} for the server's default.
     *
     * @since 1.33.0
     */
    public @Nullable String currency() {
        return currency;
    }

    /** The material or head string a menu draws, or {@code null} to derive one. */
    public @Nullable String icon() {
        return icon;
    }

    // ---------------------------------------------------------------- amounts

    /**
     * The fixed stack size, when this is not a range.
     *
     * <p>Ignored once {@link #minAmount()} and {@link #maxAmount()} are set.
     */
    public int itemAmount() {
        return itemAmount;
    }

    /**
     * The low end of a random amount, or {@code null} for a fixed one.
     *
     * @since 1.33.0
     */
    public @Nullable Integer minAmount() {
        return minAmount;
    }

    /**
     * The high end of a random amount, or {@code null} for a fixed one.
     *
     * @since 1.33.0
     */
    public @Nullable Integer maxAmount() {
        return maxAmount;
    }

    /**
     * Whether this reward gives a random amount rather than a fixed one.
     *
     * @since 1.33.0
     */
    public boolean isRanged() {
        return minAmount != null && maxAmount != null;
    }

    // ------------------------------------------------------------- its chances

    /**
     * The percentage chance of being given at all, {@code 0}&ndash;{@code 100}.
     *
     * <p>A percentage rather than a fraction because that is what the stored
     * rows hold and what a server owner types.
     */
    public double chance() {
        return chance;
    }

    /** Whether this reward is given every time. */
    public boolean isGuaranteed() {
        return chance >= ALWAYS;
    }

    /**
     * How likely this entry is to be picked by {@link PluginRewards#roll},
     * relative to the others in its group.
     *
     * <p>Unrelated to {@link #chance()}: chance asks "does this one happen",
     * weight asks "which of these happens". A weight of {@code 0} means the
     * entry is never picked by a weighted roll, which is why the default is
     * {@code 1}.
     *
     * @since 1.33.0
     */
    public double weight() {
        return weight;
    }

    // ---------------------------------------------------------- who may get it

    /** The condition that must hold, in the notation {@code Conditions} reads. */
    public @Nullable String condition() {
        return condition;
    }

    /** The permission the receiver must have, or {@code null}. */
    public @Nullable String permission() {
        return permission;
    }

    /** What to tell the receiver when this reward lands, or {@code null}. */
    public @Nullable String deliveryMessage() {
        return deliveryMessage;
    }

    /** Higher goes first. Rewards of equal priority keep their written order. */
    public int priority() {
        return priority;
    }

    // ---------------------------------------------------------------- deriving

    /**
     * The name to show, falling back to a description of what it gives.
     *
     * <p>A reward nobody bothered to name still has to read as something in a
     * menu.
     */
    public @NotNull String displayName() {
        return name != null && !name.isBlank() ? name : preview();
    }

    /**
     * A short description of what this reward gives.
     *
     * <p>Never {@code null}: a reward missing its payload describes itself as
     * such rather than as nothing, so a half-configured row is visible in the
     * menu that has to fix it.
     */
    public @NotNull String preview() {
        return Previews.of(this);
    }

    /**
     * The material a menu should draw for this reward.
     *
     * <p>The explicit {@link #icon()} wins; an item reward falls back to the
     * item itself; anything else falls back to a material that reads as its
     * type. A head string is returned whole, because that is what the item
     * module expects to be handed.
     */
    public @NotNull String resolvedIcon() {
        return Previews.icon(this);
    }

    // ---------------------------------------------------------------- building

    /**
     * A builder holding this entry's values, including its {@link #id()}.
     *
     * <p>The seam an editor menu edits through: change one field, build, and
     * replace the entry in the list. Keeping the id is what makes that a change
     * to the same reward rather than a different one.
     */
    public @NotNull Builder toBuilder() {
        Builder builder = new Builder(type);
        builder.id = id;
        builder.name = name;
        builder.command = command;
        builder.itemSnapshot = itemSnapshot;
        builder.message = message;
        builder.icon = icon;
        builder.value = value;
        builder.currency = currency;
        builder.itemAmount = itemAmount;
        builder.minAmount = minAmount;
        builder.maxAmount = maxAmount;
        builder.chance = chance;
        builder.weight = weight;
        builder.condition = condition;
        builder.permission = permission;
        builder.deliveryMessage = deliveryMessage;
        builder.priority = priority;
        return builder;
    }

    /**
     * The same reward under a new identity.
     *
     * <p>What duplicating a row in an editor means. {@link #toBuilder()} is for
     * editing the reward that already exists.
     */
    public @NotNull RewardEntry copy() {
        return toBuilder().id(UUID.randomUUID().toString()).build();
    }

    /**
     * A builder for a reward of the given type.
     *
     * @param type what it gives
     * @return the builder
     */
    public static @NotNull Builder of(@NotNull RewardType type) {
        return new Builder(Objects.requireNonNull(type, "type"));
    }

    /** A reward that runs a command. */
    public static @NotNull Builder command(@NotNull String command) {
        return of(RewardType.COMMAND).command(command);
    }

    /** A reward that sends a message. */
    public static @NotNull Builder message(@NotNull String message) {
        return of(RewardType.MESSAGE).message(message);
    }

    /** A reward that gives an already-serialised item. */
    public static @NotNull Builder item(@NotNull String itemSnapshot) {
        return of(RewardType.ITEM).itemSnapshot(itemSnapshot);
    }

    /**
     * A reward that pays money.
     *
     * @param amount how much, as written, so a decimal is not rounded on its way
     *               through a {@code double}
     * @since 1.33.0
     */
    public static @NotNull Builder economy(@NotNull String amount) {
        return of(RewardType.ECONOMY).value(amount);
    }

    /**
     * A reward that grants experience points.
     *
     * @param points how much experience
     * @since 1.33.0
     */
    public static @NotNull Builder experience(int points) {
        return of(RewardType.EXPERIENCE).value(Integer.toString(points));
    }

    /**
     * A reward that applies a potion effect.
     *
     * @param effect the effect, written as {@link net.exylia.lib.util.Effects}
     *               reads it: {@code SPEED:1:300}
     * @since 1.33.0
     */
    public static @NotNull Builder potion(@NotNull String effect) {
        return of(RewardType.POTION).value(effect);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RewardEntry entry && id.equals(entry.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "RewardEntry[" + type + " " + preview() + ", chance=" + chance + "]";
    }

    /** Builds a {@link RewardEntry}. */
    public static final class Builder {

        private String id = UUID.randomUUID().toString();
        private String name;
        private final RewardType type;
        private String command;
        private String itemSnapshot;
        private String message;
        private String icon;
        private String value;
        private String currency;
        private int itemAmount = 1;
        private Integer minAmount;
        private Integer maxAmount;
        private double chance = ALWAYS;
        private double weight = 1.0;
        private String condition;
        private String permission;
        private String deliveryMessage;
        private int priority;

        private Builder(RewardType type) {
            this.type = type;
        }

        /**
         * Overrides the generated identity.
         *
         * <p>For reading a stored row, which already has one. A reward built in
         * code does not need to call this.
         */
        public @NotNull Builder id(@NotNull String id) {
            this.id = Objects.requireNonNull(id, "id");
            return this;
        }

        /**
         * The name a menu shows, in Exylia text notation.
         *
         * @see RewardEntry#name()
         */
        public @NotNull Builder name(@Nullable String name) {
            this.name = name;
            return this;
        }

        /**
         * The command the console runs.
         *
         * @see RewardEntry#command()
         */
        public @NotNull Builder command(@Nullable String command) {
            this.command = command;
            return this;
        }

        /**
         * The item, as a material, a head string or a {@code bytes:} snapshot.
         *
         * @see RewardEntry#itemSnapshot()
         */
        public @NotNull Builder itemSnapshot(@Nullable String itemSnapshot) {
            this.itemSnapshot = itemSnapshot;
            return this;
        }

        /**
         * The message to send.
         *
         * @see RewardEntry#message()
         */
        public @NotNull Builder message(@Nullable String message) {
            this.message = message;
            return this;
        }

        /**
         * The material a menu draws, overriding what would be derived.
         *
         * @see RewardEntry#icon()
         */
        public @NotNull Builder icon(@Nullable String icon) {
            this.icon = icon;
            return this;
        }

        /**
         * The payload of a type ExyliaCommons did not have.
         *
         * @see RewardEntry#value()
         */
        public @NotNull Builder value(@Nullable String value) {
            this.value = value;
            return this;
        }

        /**
         * Which currency to pay into, or {@code null} for the default.
         *
         * @see RewardEntry#currency()
         */
        public @NotNull Builder currency(@Nullable String currency) {
            this.currency = currency;
            return this;
        }

        /**
         * The fixed stack size.
         *
         * @see RewardEntry#itemAmount()
         */
        public @NotNull Builder itemAmount(int itemAmount) {
            this.itemAmount = itemAmount;
            return this;
        }

        /**
         * Makes the amount exactly this, cancelling any range.
         *
         * <p>What is owed to a player who could not carry all of a rolled
         * reward: the dice already spoke, and rolling again when the rest is
         * finally handed over would change what they earned.
         *
         * @param amount how many
         * @since 1.33.0
         */
        public @NotNull Builder fixedAmount(int amount) {
            this.itemAmount = amount;
            this.minAmount = null;
            this.maxAmount = null;
            return this;
        }

        /**
         * Makes the amount random within a range, inclusive at both ends.
         *
         * <p>The bounds are put in order rather than rejected: a server owner
         * who typed them the wrong way round meant a range, and refusing to give
         * the reward teaches them nothing at a time nobody is reading the log.
         *
         * @param min the low end
         * @param max the high end
         * @since 1.33.0
         */
        public @NotNull Builder amountBetween(int min, int max) {
            this.minAmount = Math.min(min, max);
            this.maxAmount = Math.max(min, max);
            return this;
        }

        /**
         * The percentage chance of happening at all, {@code 0}&ndash;{@code 100}.
         *
         * @see RewardEntry#chance()
         */
        public @NotNull Builder chance(double chance) {
            this.chance = chance;
            return this;
        }

        /**
         * How likely this is to be picked out of a group, against its siblings.
         *
         * @see RewardEntry#weight()
         */
        public @NotNull Builder weight(double weight) {
            this.weight = weight;
            return this;
        }

        /**
         * What must hold before this is given.
         *
         * @see RewardEntry#condition()
         */
        public @NotNull Builder condition(@Nullable String condition) {
            this.condition = condition;
            return this;
        }

        /**
         * What the receiver must have.
         *
         * @see RewardEntry#permission()
         */
        public @NotNull Builder permission(@Nullable String permission) {
            this.permission = permission;
            return this;
        }

        /**
         * What to tell the receiver when this lands.
         *
         * @see RewardEntry#deliveryMessage()
         */
        public @NotNull Builder deliveryMessage(@Nullable String deliveryMessage) {
            this.deliveryMessage = deliveryMessage;
            return this;
        }

        /**
         * Higher goes first.
         *
         * @see RewardEntry#priority()
         */
        public @NotNull Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        /** The finished reward. */
        public @NotNull RewardEntry build() {
            return new RewardEntry(this);
        }
    }
}
