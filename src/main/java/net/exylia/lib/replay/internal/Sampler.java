package net.exylia.lib.replay.internal;

import net.exylia.lib.model.internal.ModelRuntime;
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

    /** Where a slot is in {@link #SLOTS}, or -1 for one the client does not draw. */
    static int slotIndex(EquipmentSlot slot) {
        for (int index = 0; index < SLOTS.length; index++) {
            if (SLOTS[index] == slot) return index;
        }
        return -1;
    }

    /** Two samples this close together belong to the same server tick. */
    static final long SAME_TICK_NANOS = 30_000_000L;

    /** {@link MotionTrack#poseIndex} by pose, worked out once rather than by name every tick. */
    private static final int[] POSES = java.util.Arrays.stream(org.bukkit.entity.Pose.values())
            .mapToInt(pose -> MotionTrack.poseIndex(pose.name())).toArray();

    private Sampler() {
    }

    /** What a body that is not alive always stands in: only living things change pose. */
    private static final int STANDING = POSES[org.bukkit.entity.Pose.STANDING.ordinal()];

    /** The flag word for this tick; see {@link MotionTrack}. */
    static int flagsOf(Entity entity) {
        // Asking for the pose copies the server's whole list of poses each
        // time, and an arrow, an item or a block of TNT never has another one.
        int pose = entity instanceof LivingEntity ? POSES[entity.getPose().ordinal()] : STANDING;
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
        // A model's carrier is recorded as absent. Its model is drawn with
        // packets the recording does not keep, so all a replay could show is
        // the carrier, and a camera view draws an invisible body see-through:
        // a ghost pig where the golem stood.
        return MotionTrack.flagsOf(pose, sprinting, onGround, main, off, fire,
                living.isInvisible(), glowing, ModelRuntime.of(living) == null);
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
     * point a hit. The material is checked every tick; everything else,
     * enchantments and trims included, once a second. An item that is equal
     * is also alike, so the second comparison alone answers both; asking for
     * equality first only cost a whole extra comparison on every hit taken.
     */
    static boolean sameLook(ItemStack now, ItemStack recorded, int tick) {
        return sameLook(now, recorded, tick % FULL_LOOK_EVERY == 0);
    }

    /** Same, comparing past the material only when {@code full}. */
    static boolean sameLook(ItemStack now, ItemStack recorded, boolean full) {
        if (now == recorded) return true;
        if (now == null || recorded == null) return false;
        if (now.getType() != recorded.getType()) return false;
        return !full || looksAlike(now, recorded);
    }

    /** Whether data components can be compared one by one; false on a server older than them. */
    private static volatile boolean components = net.exylia.lib.platform.Features.dataComponents();

    /**
     * Whether two items of the same material look the same in somebody's hand.
     *
     * <p>Durability and count never show on a body, so a change to either is
     * not a change of look: otherwise every hit taken, every block placed and
     * every pearl thrown was a new item copied, and in a recording written out
     * in Paper's compressed byte form on the main thread.
     */
    static boolean looksAlike(ItemStack a, ItemStack b) {
        if (components) {
            try {
                return UnseenData.alike(a, b);
            } catch (LinkageError older) {
                components = false;
            }
        }
        if (a.isSimilar(b)) return true;
        // No data component API (1.21.1): the same question through the meta,
        // asked only when the plain comparison said no, so a still item costs
        // nothing more than it did.
        if (a.getType() != b.getType()) return false;
        ItemStack left = a.clone();
        ItemStack right = b.clone();
        if (!(left.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable l)
                || !(right.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable r)) {
            return false;
        }
        l.setDamage(0);
        r.setDamage(0);
        left.setItemMeta(l);
        right.setItemMeta(r);
        return left.isSimilar(right);
    }

    static ItemStack worn(LivingEntity living, EquipmentSlot slot) {
        org.bukkit.inventory.EntityEquipment equipment = living.getEquipment();
        if (equipment == null) return null;
        ItemStack item = equipment.getItem(slot);
        return item == null || item.getType().isAir() ? null : item;
    }
}
