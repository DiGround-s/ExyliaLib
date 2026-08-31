package net.exylia.lib.util.loot;

import net.exylia.lib.item.Source;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * One line of a loot table, exactly as it is stored.
 *
 * <p>A value, not a live thing: it holds what somebody configured and nothing
 * about the chest, the spawner or the block it is about to come out of. Rolling
 * it is {@link Loot}'s job, and the same entry is rolled for every player.
 *
 * <pre>{@code
 * LootEntry bread = LootEntry.item("BREAD")
 *         .amountBetween(1, 3)
 *         .weight(40.0)
 *         .tier("COMMON")
 *         .build();
 * }</pre>
 *
 * <h2>Immutable, unlike the one it replaces</h2>
 * ExyliaCommons' {@code LootEntry} was a mutable Lombok bean shared between the
 * table that loaded it, the service rolling it and the editor menu writing to
 * it. Here an edit produces a new entry through {@link #toBuilder()}, so an
 * admin saving a table cannot change an entry out from under a chest that is
 * being filled, and the same list can be read from several threads without a
 * lock.
 *
 * <h2>The stored form is not ours to choose</h2>
 * Rows written by ExyliaCommons already exist in production databases — every
 * loot chest template, item spawner and event loot table in the ecosystem. The
 * field names {@link LootCodec} reads and writes are that bean's fields, and
 * they are fixed. See that class for the format; this one only says what the
 * fields mean.
 *
 * <h2>Weight means two different things, and both are commons'</h2>
 * {@link Loot#roll} treats {@link #weight()} as a percentage: each entry is
 * rolled on its own and any number of them can come up. {@link Loot#pick}
 * treats it as a share of the total: exactly one comes up. The same stored
 * number, read two ways, because that is what the tables out there mean by it —
 * a chest rolls every line, a survival-games refill picks one.
 *
 * @since 1.56.0
 */
public final class LootEntry {

    /** The weight an entry has when nobody chose one, as commons defaulted it. */
    public static final double DEFAULT_WEIGHT = 50.0;

    private final String id;
    private final LootType type;
    private final String itemSnapshot;
    private final int minAmount;
    private final int maxAmount;
    private final String command;
    private final double weight;
    private final String tier;

    private LootEntry(Builder builder) {
        this.id = builder.id;
        this.type = builder.type;
        this.itemSnapshot = builder.itemSnapshot;
        this.minAmount = builder.minAmount;
        this.maxAmount = builder.maxAmount;
        this.command = builder.command;
        this.weight = builder.weight;
        this.tier = builder.tier;
    }

    // ------------------------------------------------------------- what it is

    /**
     * This entry's identity, stable across edits.
     *
     * <p>What an editor menu uses to find the row a player clicked. Generated
     * when the entry is first built and carried by {@link #toBuilder()}; only
     * {@link #copy()} makes a new one.
     *
     * @return the id
     */
    public @NotNull String id() {
        return id;
    }

    /** What this entry gives. */
    public @NotNull LootType type() {
        return type;
    }

    /** Whether this entry gives an item. */
    public boolean isItem() {
        return type == LootType.ITEM;
    }

    /** Whether this entry runs a command. */
    public boolean isCommand() {
        return type == LootType.COMMAND;
    }

    // ------------------------------------------------------------ its payload

    /**
     * The item, for {@link LootType#ITEM}.
     *
     * <p>Written the way the item module reads one: a material name, a head
     * string, or a {@code bytes:} snapshot.
     *
     * @return the stored item, or {@code null}
     */
    public @Nullable String itemSnapshot() {
        return itemSnapshot;
    }

    /**
     * The command the console runs, for {@link LootType#COMMAND}.
     *
     * <p>Placeholders in it are resolved against whoever it is being run for;
     * the entry itself does not know who that is.
     *
     * @return the command, or {@code null}
     */
    public @Nullable String command() {
        return command;
    }

    // ---------------------------------------------------------------- amounts

    /** The low end of the stack size, inclusive. */
    public int minAmount() {
        return minAmount;
    }

    /** The high end of the stack size, inclusive. */
    public int maxAmount() {
        return maxAmount;
    }

    /** Whether the two ends differ, so the amount is actually rolled. */
    public boolean isRanged() {
        return maxAmount > minAmount;
    }

    // ------------------------------------------------------------- its chances

    /**
     * How likely this entry is, read as a percentage or as a share depending on
     * who is rolling.
     *
     * @return the weight
     * @see Loot#roll
     * @see Loot#pick
     */
    public double weight() {
        return weight;
    }

    /**
     * Which band this entry belongs to, such as {@code COMMON} or {@code RARE},
     * or {@code null}.
     *
     * <p>The library never reads it. It exists because the tables already carry
     * it and the plugins that wrote it group and colour by it.
     *
     * @return the tier, uppercase as it was written
     */
    public @Nullable String tier() {
        return tier;
    }

    // ---------------------------------------------------------------- deriving

    /**
     * A short description of what this entry gives.
     *
     * <p>Never {@code null}: a half-configured entry describes itself as such
     * rather than as nothing, so the menu that has to fix it can show it. The
     * four strings are commons' own, down to the brackets.
     *
     * <p>A {@code bytes:} snapshot is deliberately <em>not</em> decoded for the
     * name — a menu of forty entries would pay forty NBT reads for a label, and
     * the row is already drawn as the item itself. See {@link #resolvedIcon()}.
     *
     * @return something a human reads
     */
    public @NotNull String displayName() {
        if (isCommand()) {
            return command != null && !command.isBlank() ? command : "(no command)";
        }
        return itemSnapshot != null ? readable(itemSnapshot) : "(not set)";
    }

    /**
     * What a menu should draw for this entry.
     *
     * <p>An item entry draws as its own item; anything else draws as its type's
     * {@link LootType#defaultIcon()}. A head string and a {@code bytes:}
     * snapshot are returned whole, because that is what the item module expects
     * to be handed: a stored item draws as the item it is, custom name, model
     * and all, which is the only way an admin can tell two of them apart.
     *
     * @return a material name, a head string or a snapshot
     */
    public @NotNull String resolvedIcon() {
        if (isItem() && itemSnapshot != null) {
            return material(itemSnapshot);
        }
        return type.defaultIcon();
    }

    private static String material(String snapshot) {
        return switch (Source.of(snapshot)) {
            case Source.OfMaterial value -> value.raw().toUpperCase(Locale.ROOT);
            case Source.OfHead head -> head.raw();
            case Source.OfHeadTemplate template -> template.raw();
            // Handed over whole: decoding it is the item module's job, and a
            // row that drew every stored item as a chest was a page of chests.
            case Source.OfSnapshot stored -> stored.raw();
        };
    }

    private static String readable(String snapshot) {
        return switch (Source.of(snapshot)) {
            case Source.OfMaterial value -> value.raw().toUpperCase(Locale.ROOT);
            case Source.OfHead ignored -> "CUSTOM_HEAD";
            case Source.OfHeadTemplate ignored -> "CUSTOM_HEAD";
            case Source.OfSnapshot ignored -> "ITEM";
        };
    }

    // ---------------------------------------------------------------- building

    /**
     * A builder holding this entry's values, including its {@link #id()}.
     *
     * <p>The seam an editor edits through: change one field, build, replace the
     * entry in the list. Keeping the id is what makes that a change to the same
     * entry rather than a different one.
     *
     * @return a builder
     */
    public @NotNull Builder toBuilder() {
        Builder builder = new Builder(type);
        builder.id = id;
        builder.itemSnapshot = itemSnapshot;
        builder.minAmount = minAmount;
        builder.maxAmount = maxAmount;
        builder.command = command;
        builder.weight = weight;
        builder.tier = tier;
        return builder;
    }

    /**
     * The same entry under a new identity.
     *
     * <p>What duplicating a row in an editor means. {@link #toBuilder()} is for
     * editing the entry that already exists.
     *
     * @return the copy
     */
    public @NotNull LootEntry copy() {
        return toBuilder().id(UUID.randomUUID().toString()).build();
    }

    /**
     * A builder for an entry of the given type.
     *
     * @param type what it gives
     * @return the builder
     */
    public static @NotNull Builder of(@NotNull LootType type) {
        return new Builder(Objects.requireNonNull(type, "type"));
    }

    /**
     * An entry that gives an already-stored item.
     *
     * @param itemSnapshot a material name, a head string or a {@code bytes:}
     *                     snapshot
     * @return the builder
     * @see Loot#entryOf(org.bukkit.inventory.ItemStack)
     */
    public static @NotNull Builder item(@NotNull String itemSnapshot) {
        return of(LootType.ITEM).itemSnapshot(itemSnapshot);
    }

    /**
     * An entry that runs a command.
     *
     * @param command the command, without a leading slash
     * @return the builder
     */
    public static @NotNull Builder command(@NotNull String command) {
        return of(LootType.COMMAND).command(command);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LootEntry entry && id.equals(entry.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "LootEntry[" + type + " " + displayName() + ", weight=" + weight + "]";
    }

    /** Builds a {@link LootEntry}. */
    public static final class Builder {

        private String id = UUID.randomUUID().toString();
        private final LootType type;
        private String itemSnapshot;
        private int minAmount = 1;
        private int maxAmount = 1;
        private String command;
        private double weight = DEFAULT_WEIGHT;
        private String tier;

        private Builder(LootType type) {
            this.type = type;
        }

        /**
         * Overrides the generated identity.
         *
         * <p>For reading a stored row, which already has one. An entry built in
         * code does not need to call this.
         *
         * @param id the id
         * @return this builder
         */
        public @NotNull Builder id(@NotNull String id) {
            this.id = Objects.requireNonNull(id, "id");
            return this;
        }

        /**
         * The item, as a material, a head string or a {@code bytes:} snapshot.
         *
         * @param itemSnapshot the stored item
         * @return this builder
         */
        public @NotNull Builder itemSnapshot(@Nullable String itemSnapshot) {
            this.itemSnapshot = itemSnapshot;
            return this;
        }

        /**
         * The command the console runs.
         *
         * @param command the command
         * @return this builder
         */
        public @NotNull Builder command(@Nullable String command) {
            this.command = command;
            return this;
        }

        /**
         * Makes the stack size exactly this.
         *
         * @param amount how many
         * @return this builder
         */
        public @NotNull Builder amount(int amount) {
            this.minAmount = amount;
            this.maxAmount = amount;
            return this;
        }

        /**
         * Makes the stack size random within a range, inclusive at both ends.
         *
         * <p>The bounds are put in order rather than rejected: somebody who
         * typed them the wrong way round meant a range, and dropping the entry
         * teaches them nothing at a time nobody is reading the log.
         *
         * @param min the low end
         * @param max the high end
         * @return this builder
         */
        public @NotNull Builder amountBetween(int min, int max) {
            this.minAmount = Math.min(min, max);
            this.maxAmount = Math.max(min, max);
            return this;
        }

        /**
         * The low end on its own, for reading a stored row field by field.
         *
         * @param minAmount the low end
         * @return this builder
         */
        public @NotNull Builder minAmount(int minAmount) {
            this.minAmount = minAmount;
            return this;
        }

        /**
         * The high end on its own, for reading a stored row field by field.
         *
         * @param maxAmount the high end
         * @return this builder
         */
        public @NotNull Builder maxAmount(int maxAmount) {
            this.maxAmount = maxAmount;
            return this;
        }

        /**
         * How likely this entry is.
         *
         * @param weight a percentage to {@link Loot#roll}, a share to
         *               {@link Loot#pick}
         * @return this builder
         */
        public @NotNull Builder weight(double weight) {
            this.weight = weight;
            return this;
        }

        /**
         * Which band this entry belongs to.
         *
         * @param tier the tier, or {@code null}
         * @return this builder
         */
        public @NotNull Builder tier(@Nullable String tier) {
            this.tier = tier;
            return this;
        }

        /**
         * The finished entry.
         *
         * @return the entry
         */
        public @NotNull LootEntry build() {
            return new LootEntry(this);
        }
    }
}
