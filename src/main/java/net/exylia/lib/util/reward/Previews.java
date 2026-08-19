package net.exylia.lib.util.reward;

import net.exylia.lib.item.Source;

import java.util.Locale;

/**
 * What a reward looks like before anybody receives it.
 *
 * <p>Package-private on purpose: {@link RewardEntry#preview()} and
 * {@link RewardEntry#resolvedIcon()} are the public way in. This exists as its
 * own class only so the entry stays a value and does not grow string handling.
 *
 * <p>Deriving the material from a serialised item deliberately does <em>not</em>
 * deserialise it. A menu drawing forty rewards would pay forty NBT reads for a
 * label, and the item module has to deserialise it again anyway when the row is
 * actually drawn.
 */
final class Previews {

    private Previews() {
        throw new AssertionError("No instances.");
    }

    /** What {@link RewardEntry#preview()} returns. */
    static String of(RewardEntry entry) {
        return switch (entry.type()) {
            case COMMAND -> orMissing(entry.command());
            case MESSAGE -> orMissing(entry.message());
            case ITEM -> item(entry);
            case ECONOMY -> amounts(entry) + (entry.currency() != null
                    ? " " + entry.currency()
                    : " coins");
            case EXPERIENCE -> amounts(entry) + " XP";
            case POTION -> orMissing(entry.value());
        };
    }

    /** What {@link RewardEntry#resolvedIcon()} returns. */
    static String icon(RewardEntry entry) {
        if (entry.icon() != null && !entry.icon().isBlank()) {
            return material(entry.icon());
        }
        if (entry.type() == RewardType.ITEM && entry.itemSnapshot() != null) {
            return material(entry.itemSnapshot());
        }
        return entry.type().defaultIcon();
    }

    private static String item(RewardEntry entry) {
        String snapshot = entry.itemSnapshot();
        if (snapshot == null) {
            return "(no item)";
        }
        String prefix = amountPrefix(entry);
        return prefix + readable(snapshot);
    }

    /**
     * The material a snapshot names, without decoding it.
     *
     * <p>A head is returned whole because that is what the item module is given
     * to draw; a serialised item cannot be named without decoding, so it draws
     * as a chest.
     */
    private static String material(String snapshot) {
        Source source = Source.of(snapshot);
        return switch (source) {
            case Source.OfMaterial material -> material.raw().toUpperCase(Locale.ROOT);
            case Source.OfHead head -> head.raw();
            case Source.OfHeadTemplate template -> template.raw();
            case Source.OfSnapshot ignored -> "CHEST";
        };
    }

    /** The same, as something a human reads in a tooltip. */
    private static String readable(String snapshot) {
        Source source = Source.of(snapshot);
        return switch (source) {
            case Source.OfMaterial material ->
                    material.raw().toLowerCase(Locale.ROOT).replace('_', ' ');
            case Source.OfHead ignored -> "custom skull";
            case Source.OfHeadTemplate ignored -> "custom skull";
            case Source.OfSnapshot ignored -> "item";
        };
    }

    private static String amountPrefix(RewardEntry entry) {
        if (entry.isRanged()) {
            return entry.minAmount() + "-" + entry.maxAmount() + "x ";
        }
        return entry.itemAmount() > 1 ? entry.itemAmount() + "x " : "";
    }

    private static String amounts(RewardEntry entry) {
        if (entry.isRanged()) {
            return entry.minAmount() + "-" + entry.maxAmount();
        }
        return entry.value() != null ? entry.value() : "(not set)";
    }

    private static String orMissing(String value) {
        return value != null && !value.isBlank() ? value : "(not set)";
    }
}
