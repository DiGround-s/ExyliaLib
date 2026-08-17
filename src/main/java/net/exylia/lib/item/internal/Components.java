package net.exylia.lib.item.internal;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.FoodProperties;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.exylia.lib.item.Modifier;
import net.kyori.adventure.key.Key;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * The two item parts that only exist as data components.
 *
 * <p>Confined to one class on purpose, exactly as PacketEvents and Apollo are.
 * {@code io.papermc.paper.datacomponent} is Paper's, and the library has to
 * load on Spigot: a class referring to it is never loaded there, so nothing
 * else may refer to it.
 *
 * <p>{@link #available()} is the gate. It is asked once, and everything here is
 * behind it.
 *
 * <p>Written against the API rather than through reflection. ExyliaCommons
 * needed two hundred lines of reflection for the consumable alone because it
 * supported servers older than the components; we compile against paper-api
 * 1.21.4, where they are ordinary method calls.
 */
final class Components {

    /**
     * Whether this server has data components.
     *
     * <p>Resolved once at class load. On Spigot this class is never loaded at
     * all — the caller checks {@link #available()} through
     * {@link ComponentSupport}, which does not name a Paper type.
     */
    private Components() {
    }

    /**
     * Makes an item edible.
     *
     * <p>Two components rather than one: {@code FOOD} decides what eating it
     * gives you and {@code CONSUMABLE} decides how eating it looks and sounds.
     * An item with only the second is held and consumed but feeds nobody, which
     * is what a flask or a scroll wants.
     *
     * @param item     the item
     * @param eating   how long, how filling, and what it sounds like
     * @param sound    the resolved sound key, or {@code null} for the default
     */
    static void consumable(ItemStack item, net.exylia.lib.item.Consumable eating, Key sound) {
        item.setData(DataComponentTypes.FOOD, FoodProperties.food()
                .nutrition(eating.nutrition())
                .saturation(eating.saturation())
                .canAlwaysEat(true));

        Consumable.Builder builder = Consumable.consumable()
                .consumeSeconds(eating.seconds())
                .animation(ItemUseAnimation.EAT);
        if (sound != null) {
            builder.sound(sound);
        }
        item.setData(DataComponentTypes.CONSUMABLE, builder);
    }

    /**
     * Writes attribute modifiers onto an item.
     *
     * @param item      the item
     * @param modifiers what to add
     * @param problems  where to report names nobody recognises
     */
    static void modifiers(ItemStack item, List<Modifier> modifiers,
                          TraitApplier.Reporter problems) {
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.itemAttributes();
        boolean any = false;
        for (Modifier modifier : modifiers) {
            Attribute attribute = Registries.attribute(modifier.key());
            if (attribute == null) {
                problems.found("attribute",
                        "there is no attribute called \"" + modifier.attribute() + "\"");
                continue;
            }
            builder.addModifier(attribute, new AttributeModifier(
                    new NamespacedKey("exylia", "item_" + modifier.key()),
                    modifier.amount(), AttributeModifier.Operation.ADD_NUMBER));
            any = true;
        }
        if (any) {
            item.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, builder);
        }
    }
}
