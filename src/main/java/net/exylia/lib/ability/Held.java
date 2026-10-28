package net.exylia.lib.ability;

import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * One ability on one holder: which, at what level, and from where.
 *
 * <p>What a {@link PluginAbilities#holdings holdings} function answers with. Two
 * equal values are the same holding, so a refresh that answers the same list
 * fires no EQUIP or UNEQUIP.
 *
 * @param ability the compiled ability
 * @param level   its level, 1 or more; the {@code level} of every formula
 * @param slot    the slot it came from, or {@code null} when it comes from no item
 * @since 1.385.0
 */
public record Held(@NotNull Ability ability, int level, @Nullable EquipmentSlot slot) {

    public Held {
        Objects.requireNonNull(ability, "ability");
        if (level < 1) {
            throw new IllegalArgumentException("level must be 1 or more, was " + level);
        }
    }
}
