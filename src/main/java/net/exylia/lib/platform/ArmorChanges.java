package net.exylia.lib.platform;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

/**
 * The slot a {@code PlayerArmorChangeEvent} is about, on every supported server.
 *
 * <p>Paper 1.21.4 gave the event {@code getSlot()}, an {@link EquipmentSlot};
 * 1.21.1 only has {@code getSlotType()}, its own four-value enum, which newer
 * servers deprecate. Calling {@code getSlot()} on 1.21.1 is a
 * {@code NoSuchMethodError}.
 *
 * <pre>{@code
 * @EventHandler
 * public void onArmor(PlayerArmorChangeEvent event) {
 *     EquipmentSlot slot = ArmorChanges.slot(event);
 * }
 * }</pre>
 *
 * @since 1.442.0
 */
public final class ArmorChanges {

    private static final boolean DIRECT = hasDirect();

    private ArmorChanges() {
    }

    /** {@code HEAD}, {@code CHEST}, {@code LEGS} or {@code FEET}. */
    @SuppressWarnings({"deprecation", "removal"})
    public static @NotNull EquipmentSlot slot(@NotNull PlayerArmorChangeEvent event) {
        if (DIRECT) {
            return Direct.slot(event);
        }
        return switch (event.getSlotType()) {
            case HEAD -> EquipmentSlot.HEAD;
            case CHEST -> EquipmentSlot.CHEST;
            case LEGS -> EquipmentSlot.LEGS;
            case FEET -> EquipmentSlot.FEET;
        };
    }

    private static boolean hasDirect() {
        try {
            PlayerArmorChangeEvent.class.getMethod("getSlot");
            return true;
        } catch (NoSuchMethodException | LinkageError older) {
            return false;
        }
    }

    @RequiresServer("1.21.4")
    private static final class Direct {
        static EquipmentSlot slot(PlayerArmorChangeEvent event) {
            return event.getSlot();
        }
    }
}
