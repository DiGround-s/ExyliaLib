package net.exylia.lib.api.armortrims;

import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

/**
 * The four armor slots a trim can be worn in.
 *
 * <p>Separate from Bukkit's {@link EquipmentSlot}, which also names the two
 * hands and the body: a trim is only ever worn on armor, and a method taking
 * this cannot be handed a slot it has no answer for.
 *
 * @since 1.0.0
 */
public enum ArmorPiece {

    HELMET(EquipmentSlot.HEAD),
    CHESTPLATE(EquipmentSlot.CHEST),
    LEGGINGS(EquipmentSlot.LEGS),
    BOOTS(EquipmentSlot.FEET);

    private final EquipmentSlot slot;

    ArmorPiece(EquipmentSlot slot) {
        this.slot = slot;
    }

    /**
     * The Bukkit slot this piece is worn in.
     *
     * <p>Here so a caller reading a player's inventory does not have to keep a
     * switch of its own in step with this enum.
     *
     * @return the equipment slot
     */
    @NotNull
    public EquipmentSlot slot() {
        return slot;
    }
}
