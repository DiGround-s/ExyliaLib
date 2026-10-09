package net.exylia.lib.replay.internal;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Reads what one entity looks like on this tick.
 *
 * <p>Shared by the recorder and the black box, so a recording made either way
 * holds the same thing. Only ever called on the thread that owns the entity.
 */
@ApiStatus.Internal
final class Sampler {

    /** The six slots the client draws, in a fixed order. */
    static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** Two samples this close together belong to the same server tick. */
    static final long SAME_TICK_NANOS = 30_000_000L;

    /** {@link MotionTrack#poseIndex} by pose, worked out once rather than by name every tick. */
    private static final int[] POSES = java.util.Arrays.stream(org.bukkit.entity.Pose.values())
            .mapToInt(pose -> MotionTrack.poseIndex(pose.name())).toArray();

    private Sampler() {
    }

    /** The flag word for this tick; see {@link MotionTrack}. */
    static int flagsOf(Entity entity) {
        int pose = POSES[entity.getPose().ordinal()];
        boolean onGround = entity.isOnGround();
        boolean fire = entity.getFireTicks() > 0 || entity.isVisualFire();
        boolean glowing = entity.isGlowing();
        if (!(entity instanceof LivingEntity living)) {
            return MotionTrack.flagsOf(pose, false, onGround, false, false, fire, false, glowing, true);
        }
        boolean main = false;
        boolean off = false;
        try {
            if (living.isHandRaised()) {
                if (living.getHandRaised() == EquipmentSlot.OFF_HAND) off = true;
                else main = true;
            }
        } catch (NoSuchMethodError older) {
            // An API without it: the arm simply does not rise.
        }
        boolean sprinting = entity instanceof Player player && player.isSprinting();
        return MotionTrack.flagsOf(pose, sprinting, onGround, main, off, fire,
                living.isInvisible(), glowing, true);
    }

    /** Health, or zero for anything that has none. */
    static double healthOf(Entity entity) {
        return entity instanceof LivingEntity living ? living.getHealth() : 0.0;
    }

    /** What is in one slot now, or {@code null} for nothing. */
    /** Ticks between full looks at an item, past its material and count. */
    static final int FULL_LOOK_EVERY = 20;

    /** Ticks between reads of the armour slots. */
    static final int ARMOUR_EVERY = 5;

    /** Ticks between reads of the hands. */
    static final int HANDS_EVERY = 2;

    /** Whether a slot is read on this tick: armour changes rarely, hands often. */
    static boolean due(int slot, int tick) {
        return tick % (slot < 2 ? HANDS_EVERY : ARMOUR_EVERY) == 0;
    }

    /**
     * Whether an item still looks like the one recorded.
     *
     * <p>A full comparison every tick was the most expensive thing the
     * recorder did, and almost all it ever found was durability going down a
     * point a hit. Material and count are checked every tick; everything
     * else, enchantments and trims included, once a second.
     */
    static boolean sameLook(ItemStack now, ItemStack recorded, int tick) {
        if (now == recorded) return true;
        if (now == null || recorded == null) return false;
        if (now.getType() != recorded.getType() || now.getAmount() != recorded.getAmount()) return false;
        return tick % FULL_LOOK_EVERY != 0 || now.equals(recorded);
    }

    static ItemStack worn(LivingEntity living, EquipmentSlot slot) {
        org.bukkit.inventory.EntityEquipment equipment = living.getEquipment();
        if (equipment == null) return null;
        ItemStack item = equipment.getItem(slot);
        return item == null || item.getType().isAir() ? null : item;
    }
}
