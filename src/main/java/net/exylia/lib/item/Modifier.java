package net.exylia.lib.item;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * One attribute modifier on an item.
 *
 * <p>Written as {@code ATTRIBUTE|value}, with the short names people actually
 * type accepted alongside the vanilla ones:
 *
 * <pre>{@code
 * attributes:
 *   - "attack_damage|8"
 *   - "GENERIC_MOVEMENT_SPEED|0.05"
 * }</pre>
 *
 * @param attribute the attribute name, as written
 * @param amount    how much to add
 * @since 1.22.0
 */
public record Modifier(@NotNull String attribute, double amount) {

    /**
     * Reads one {@code attributes} line.
     *
     * @param line the line as written
     * @return the modifier
     * @throws IllegalArgumentException if there is no {@code |}, or the amount is not a number
     */
    public static @NotNull Modifier parse(@NotNull String line) {
        int separator = line.indexOf('|');
        if (separator <= 0) {
            throw new IllegalArgumentException(
                    "An attribute is written NAME|value, got \"" + line + "\"");
        }
        String name = line.substring(0, separator).trim();
        String value = line.substring(separator + 1).trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("An attribute needs a name: \"" + line + "\"");
        }
        try {
            return new Modifier(name, Double.parseDouble(value));
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(
                    "The amount of attribute \"" + name + "\" is not a number: \"" + value + "\"");
        }
    }

    /**
     * The vanilla attribute key this names.
     *
     * <p>Resolving is left to the caller, which has the registry; this only
     * expands the shorthand. The {@code GENERIC_} prefix vanilla drops in 1.21
     * is stripped, so files written either way keep working.
     *
     * @return the key, lowercase and without a namespace
     */
    public @NotNull String key() {
        String name = attribute.toLowerCase(Locale.ROOT).replace('.', '_');
        if (name.startsWith("generic_")) {
            name = name.substring("generic_".length());
        }
        if (name.startsWith("player_")) {
            name = name.substring("player_".length());
        }
        return switch (name) {
            case "health", "maxhealth" -> "max_health";
            case "damage", "attackdamage" -> "attack_damage";
            case "speed", "movementspeed" -> "movement_speed";
            case "attackspeed" -> "attack_speed";
            case "knockbackresistance" -> "knockback_resistance";
            case "armortoughness" -> "armor_toughness";
            case "followrange" -> "follow_range";
            case "flyingspeed" -> "flying_speed";
            case "stepheight" -> "step_height";
            default -> name;
        };
    }
}
