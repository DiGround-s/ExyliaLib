package net.exylia.lib.item;

import org.jetbrains.annotations.NotNull;

/**
 * Makes anything edible.
 *
 * <p>For custom items that should be held down and consumed like food while
 * being made of something that is not — a golden head, a flask, a scroll.
 * Vanilla decides edibility per material; the data components introduced in
 * 1.21 let it be decided per item, and this is that.
 *
 * @param seconds    how long holding it down takes
 * @param nutrition  hunger restored, in half-drumsticks
 * @param saturation saturation restored
 * @param sound      the sound key played while eating, such as {@code entity.generic.eat}
 * @since 1.22.0
 */
public record Consumable(float seconds, int nutrition, float saturation, @NotNull String sound) {

    /** What vanilla food does, for items that only want the animation. */
    public static final Consumable DEFAULT = new Consumable(1.5f, 0, 0f, "entity.generic.eat");
}
