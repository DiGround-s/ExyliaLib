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

    private Sampler() {
    }

    /** The flag word for this tick; see {@link MotionTrack}. */
    static int flagsOf(Entity entity) {
        int pose = MotionTrack.poseIndex(entity.getPose().name());
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
    static ItemStack worn(LivingEntity living, EquipmentSlot slot) {
        if (living.getEquipment() == null) return null;
        ItemStack item = living.getEquipment().getItem(slot);
        return item == null || item.getType().isAir() ? null : item;
    }
}
