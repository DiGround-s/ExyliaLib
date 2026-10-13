package net.exylia.lib.npc;

import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a placed NPC looks like and how it behaves.
 *
 * <pre>{@code
 * NpcSpec guide = NpcSpec.builder()
 *         .skin(NpcSkin.player("Notch"))
 *         .pose(NpcPose.STANDING)
 *         .equip(EquipmentSlot.HAND, new ItemStack(Material.COMPASS))
 *         .look(NpcLook.EACH_VIEWER)
 *         .build();
 * }</pre>
 *
 * <p>Immutable. {@link #toBuilder()} starts a changed copy, which is what
 * {@link PlacedNpc#update(NpcSpec)} takes.
 *
 * <p>There is no name: a placed NPC never shows one. Its nametag is always hidden and it
 * never appears in the tab list; a name above it is a hologram's job.
 *
 * @since 1.291.0
 */
public final class NpcSpec {

    private final NpcSkin skin;
    private final NpcPose pose;
    private final double scale;
    private final int glow;
    private final Map<EquipmentSlot, ItemStack> equipment;
    private final NpcLook look;
    private final double lookRange;
    private final double viewDistance;

    private NpcSpec(Builder builder) {
        this.skin = builder.skin;
        this.pose = builder.pose;
        this.scale = builder.scale;
        this.glow = builder.glow;
        this.equipment = Collections.unmodifiableMap(new EnumMap<>(builder.equipment));
        this.look = builder.look;
        this.lookRange = builder.lookRange;
        this.viewDistance = builder.viewDistance;
    }

    /** A builder with the defaults: no skin, standing, player-sized, no glow, fixed look. */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /** A builder holding this spec's values. */
    public @NotNull Builder toBuilder() {
        Builder builder = new Builder();
        builder.skin = skin;
        builder.pose = pose;
        builder.scale = scale;
        builder.glow = glow;
        builder.equipment.putAll(equipment);
        builder.look = look;
        builder.lookRange = lookRange;
        builder.viewDistance = viewDistance;
        return builder;
    }

    /** What it wears. */
    public @NotNull NpcSkin skin() {
        return skin;
    }

    /** How it holds itself. */
    public @NotNull NpcPose pose() {
        return pose;
    }

    /** How big it is drawn, one being a player's own size. */
    public double scale() {
        return scale;
    }

    /** The outline colour as {@code 0xRRGGBB}, or a negative number for none. */
    public int glow() {
        return glow;
    }

    /** What it wears and holds, by slot. Read-only; do not change the items. */
    public @NotNull Map<EquipmentSlot, ItemStack> equipment() {
        return equipment;
    }

    /** Where it turns its head. */
    public @NotNull NpcLook look() {
        return look;
    }

    /** How close a viewer has to be for {@link NpcLook#NEAREST} or {@link NpcLook#EACH_VIEWER} to turn to them, in blocks. */
    public double lookRange() {
        return lookRange;
    }

    /** How close a player has to be to see it, in blocks. */
    public double viewDistance() {
        return viewDistance;
    }

    /** Builds a {@link NpcSpec}. Not thread-safe; build on one thread. */
    public static final class Builder {

        private NpcSkin skin = NpcSkin.none();
        private NpcPose pose = NpcPose.STANDING;
        private double scale = 1.0;
        private int glow = -1;
        private final Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
        private NpcLook look = NpcLook.FIXED;
        private double lookRange = 8.0;
        private double viewDistance = 48.0;

        private Builder() {
        }

        /** What it wears. */
        public @NotNull Builder skin(@NotNull NpcSkin skin) {
            this.skin = Objects.requireNonNull(skin, "skin");
            return this;
        }

        /** How it holds itself. */
        public @NotNull Builder pose(@NotNull NpcPose pose) {
            this.pose = Objects.requireNonNull(pose, "pose");
            return this;
        }

        /**
         * How big it is drawn.
         *
         * @param scale the multiplier, clamped to {@code 0.0625}..{@code 16}
         */
        public @NotNull Builder scale(double scale) {
            this.scale = Math.clamp(scale, 0.0625, 16.0);
            return this;
        }

        /**
         * An outline, in {@code 0xRRGGBB}.
         *
         * <p>Drawn in the nearest of the sixteen team colours, which is all a client
         * outlines a player in.
         *
         * @param rgb the colour, or a negative number for none
         */
        public @NotNull Builder glow(int rgb) {
            this.glow = rgb < 0 ? -1 : rgb & 0xFFFFFF;
            return this;
        }

        /**
         * Puts an item in one slot.
         *
         * @param slot the slot; {@code BODY} and {@code SADDLE} mean nothing on a player
         * @param item the item, or {@code null} or air to empty it
         */
        public @NotNull Builder equip(@NotNull EquipmentSlot slot, @Nullable ItemStack item) {
            if (item == null || item.getType().isAir()) {
                equipment.remove(slot);
            } else {
                equipment.put(slot, item.clone());
            }
            return this;
        }

        /** Where it turns its head. */
        public @NotNull Builder look(@NotNull NpcLook look) {
            this.look = Objects.requireNonNull(look, "look");
            return this;
        }

        /** How close a viewer has to be for it to turn to them, in blocks. */
        public @NotNull Builder lookRange(double blocks) {
            this.lookRange = Math.max(0.0, blocks);
            return this;
        }

        /** How close a player has to be to see it, in blocks. */
        public @NotNull Builder viewDistance(double blocks) {
            this.viewDistance = Math.max(1.0, blocks);
            return this;
        }

        /** The spec. */
        public @NotNull NpcSpec build() {
            return new NpcSpec(this);
        }
    }
}
