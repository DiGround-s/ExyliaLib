package net.exylia.lib.item.internal;

import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.banner.PatternType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.Locale;

/**
 * Turning the names in a config file into the things the server knows.
 *
 * <p>Every lookup goes through {@link Registry} rather than {@code valueOf} on
 * an enum. Several of these types stopped being enums in 1.21 — calling
 * {@code values()} on them throws {@link IncompatibleClassChangeError} at
 * runtime while compiling perfectly — and the registry form works on both
 * sides of that change.
 *
 * <p>Every failure is {@code null}. A name nobody recognises is an item that
 * misses one detail, reported by the caller, not an exception in whatever menu
 * was being drawn.
 *
 * <p>Four of these registry constants are deprecated in favour of Paper's
 * {@code RegistryAccess}, and they are used anyway. The replacement lives in
 * {@code io.papermc.paper.registry}, which a Spigot server does not have, and
 * the library must load there. The Bukkit constants are the portable form and
 * work on both.
 */
@SuppressWarnings("deprecation")
public final class Registries {

    private Registries() {
    }

    /**
     * A material by name.
     *
     * <p>{@link Material#matchMaterial} rather than {@code valueOf}: it accepts
     * the namespaced form and the legacy spellings people write.
     */
    public static Material material(String name) {
        return Material.matchMaterial(name.trim());
    }

    /** An enchantment by key or legacy name. */
    public static Enchantment enchantment(String name) {
        NamespacedKey key = key(name);
        return key == null ? null : Registry.ENCHANTMENT.get(key);
    }

    /** A potion type by name, such as {@code STRONG_HEALING}. */
    public static PotionType potion(String name) {
        NamespacedKey key = key(name);
        return key == null ? null : Registry.POTION.get(key);
    }

    /** A potion effect by name, such as {@code SPEED}. */
    public static PotionEffectType effect(String name) {
        NamespacedKey key = key(name);
        return key == null ? null : Registry.EFFECT.get(key);
    }

    /** A trim pattern by name, such as {@code sentry}. */
    public static TrimPattern trimPattern(String name) {
        NamespacedKey key = key(name);
        return key == null ? null : Registry.TRIM_PATTERN.get(key);
    }

    /** A trim material by name, such as {@code redstone}. */
    public static TrimMaterial trimMaterial(String name) {
        NamespacedKey key = key(name);
        return key == null ? null : Registry.TRIM_MATERIAL.get(key);
    }

    /** A banner pattern by name, such as {@code stripe_top}. */
    public static PatternType pattern(String name) {
        NamespacedKey key = key(name);
        return key == null ? null : Registry.BANNER_PATTERN.get(key);
    }

    /** An attribute by key, as {@link net.exylia.lib.item.Modifier#key} produces. */
    public static Attribute attribute(String name) {
        NamespacedKey key = key(name);
        return key == null ? null : Registry.ATTRIBUTE.get(key);
    }

    /** A dye colour by name, such as {@code light_gray}. */
    public static DyeColor dye(String name) {
        try {
            return DyeColor.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** An item flag by name, such as {@code HIDE_ENCHANTS}. */
    public static ItemFlag flag(String name) {
        try {
            return ItemFlag.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /**
     * A colour written as {@code #rrggbb} or by name.
     *
     * <p>The named colours are the ones {@link Color} defines, which is what
     * potion and leather configuration has always accepted.
     */
    public static Color colour(String value) {
        String text = value.trim();
        if (text.startsWith("#")) {
            try {
                return Color.fromRGB(Integer.parseInt(text.substring(1), 16));
            } catch (RuntimeException notAColour) {
                return null;
            }
        }
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "white" -> Color.WHITE;
            case "silver" -> Color.SILVER;
            case "gray", "grey" -> Color.GRAY;
            case "black" -> Color.BLACK;
            case "red" -> Color.RED;
            case "maroon" -> Color.MAROON;
            case "yellow" -> Color.YELLOW;
            case "olive" -> Color.OLIVE;
            case "lime" -> Color.LIME;
            case "green" -> Color.GREEN;
            case "aqua", "cyan" -> Color.AQUA;
            case "teal" -> Color.TEAL;
            case "blue" -> Color.BLUE;
            case "navy" -> Color.NAVY;
            case "fuchsia", "magenta" -> Color.FUCHSIA;
            case "purple" -> Color.PURPLE;
            case "orange" -> Color.ORANGE;
            default -> null;
        };
    }

    /**
     * Reads a name as a key.
     *
     * <p>A bare name is a vanilla one; a namespaced one is taken as written, so
     * a data pack's own trim pattern resolves too.
     */
    private static NamespacedKey key(String name) {
        String text = name.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        return text.indexOf(':') >= 0
                ? NamespacedKey.fromString(text)
                : NamespacedKey.minecraft(text);
    }
}
