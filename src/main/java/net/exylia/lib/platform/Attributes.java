package net.exylia.lib.platform;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.Attributable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.Function;

/**
 * Attributes that resolve on every supported server.
 *
 * <p>Minecraft 1.21.2 dropped the {@code generic.}, {@code player.},
 * {@code zombie.} and {@code horse.} prefixes from attribute keys, and
 * 1.21.3 renamed the Bukkit fields to match ({@code GENERIC_MAX_HEALTH} became
 * {@code MAX_HEALTH}) and turned {@code Attribute} from an enum into an
 * interface. Code compiled against the new names dies on 1.21.1 with
 * {@code NoSuchFieldError}, and the old names do not compile at all. These
 * constants are looked up in the registry by key instead, trying the new key
 * and then the old one, so the same line works on both.
 *
 * <pre>{@code
 * AttributeInstance health = player.getAttribute(Attributes.MAX_HEALTH);
 * Attribute fromConfig = Attributes.byKey("generic.movement_speed"); // either spelling
 * }</pre>
 *
 * <p>Every constant here exists on every supported server (1.21.1 and newer),
 * so none is {@code null} there.
 *
 * @since 1.448.0
 */
public final class Attributes {

    /**
     * Prefixes keys carried before 1.21.2, tried in turn after the bare key.
     * Declared first: the constants below resolve through it while the class initialises.
     */
    private static final String[] OLD_PREFIXES = {"generic.", "player.", "zombie.", "horse."};

    public static final Attribute ARMOR = require("armor");
    public static final Attribute ARMOR_TOUGHNESS = require("armor_toughness");
    public static final Attribute ATTACK_DAMAGE = require("attack_damage");
    public static final Attribute ATTACK_KNOCKBACK = require("attack_knockback");
    public static final Attribute ATTACK_SPEED = require("attack_speed");
    public static final Attribute BLOCK_BREAK_SPEED = require("block_break_speed");
    public static final Attribute BLOCK_INTERACTION_RANGE = require("block_interaction_range");
    public static final Attribute ENTITY_INTERACTION_RANGE = require("entity_interaction_range");
    public static final Attribute FALL_DAMAGE_MULTIPLIER = require("fall_damage_multiplier");
    public static final Attribute FLYING_SPEED = require("flying_speed");
    public static final Attribute FOLLOW_RANGE = require("follow_range");
    public static final Attribute GRAVITY = require("gravity");
    public static final Attribute JUMP_STRENGTH = require("jump_strength");
    public static final Attribute KNOCKBACK_RESISTANCE = require("knockback_resistance");
    public static final Attribute LUCK = require("luck");
    public static final Attribute MAX_ABSORPTION = require("max_absorption");
    public static final Attribute MAX_HEALTH = require("max_health");
    public static final Attribute MOVEMENT_SPEED = require("movement_speed");
    public static final Attribute SAFE_FALL_DISTANCE = require("safe_fall_distance");
    public static final Attribute SCALE = require("scale");
    public static final Attribute SPAWN_REINFORCEMENTS = require("spawn_reinforcements");
    public static final Attribute STEP_HEIGHT = require("step_height");


    private Attributes() {
    }

    /**
     * An attribute by key, in the spelling of any supported version.
     *
     * <p>Accepts {@code max_health}, {@code generic.max_health},
     * {@code minecraft:generic.max_health} and the Bukkit field names
     * ({@code GENERIC_MAX_HEALTH}, {@code MAX_HEALTH}), so a config written for
     * either version keeps working on both.
     *
     * @param key the attribute as written
     * @return the attribute, or {@code null} when this server has none by that name
     */
    public static @Nullable Attribute byKey(@NotNull String key) {
        String written = key.trim().toLowerCase(Locale.ROOT);
        int colon = written.indexOf(':');
        if (colon >= 0 && !written.startsWith("minecraft:")) {
            NamespacedKey other = NamespacedKey.fromString(written);
            return other == null ? null : Registry.ATTRIBUTE.get(other);
        }
        return resolve(written, Attributes::get);
    }

    /**
     * The lookup behind {@link #byKey}, with the registry passed in so it can
     * be checked without a server.
     *
     * @param written the key, lowercase, in any supported spelling
     * @param lookup  the registry: a bare key without namespace to its value, or null
     */
    static <T> @Nullable T resolve(String written, Function<String, T> lookup) {
        if (written.startsWith("minecraft:")) {
            written = written.substring("minecraft:".length());
        }
        // A field name such as generic_max_health: the prefix was a word, not a path.
        if (written.indexOf('.') < 0) {
            for (String prefix : OLD_PREFIXES) {
                String word = prefix.substring(0, prefix.length() - 1) + "_";
                if (written.startsWith(word)) {
                    T found = resolve(prefix + written.substring(word.length()), lookup);
                    if (found != null) return found;
                }
            }
        }
        String bare = written;
        for (String prefix : OLD_PREFIXES) {
            if (bare.startsWith(prefix)) {
                bare = bare.substring(prefix.length());
                break;
            }
        }
        T found = lookup.apply(bare);
        if (found != null) return found;
        for (String prefix : OLD_PREFIXES) {
            found = lookup.apply(prefix + bare);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * An entity's instance of an attribute, or {@code null} when it has none.
     *
     * <p>Tolerates a {@code null} attribute, which {@code getAttribute} does not.
     */
    public static @Nullable AttributeInstance of(@NotNull Attributable entity, @Nullable Attribute attribute) {
        return attribute == null ? null : entity.getAttribute(attribute);
    }

    /**
     * The key an attribute has on this server, such as {@code minecraft:max_health}.
     *
     * <p>Asked of the registry rather than {@code attribute.getKey()}: that
     * call is compiled as an interface call, and on 1.21.1, where
     * {@code Attribute} is still an enum, it throws
     * {@code IncompatibleClassChangeError}.
     */
    public static @Nullable NamespacedKey key(@NotNull Attribute attribute) {
        return Registry.ATTRIBUTE.getKey(attribute);
    }

    private static @Nullable Attribute get(String key) {
        NamespacedKey namespaced = NamespacedKey.fromString(key);
        return namespaced == null ? null : Registry.ATTRIBUTE.get(namespaced);
    }

    private static Attribute require(String key) {
        try {
            return byKey(key);
        } catch (RuntimeException | LinkageError noServer) {
            // No registry behind the API: unit tests without a server. The
            // constant stays null rather than failing the class.
            return null;
        }
    }
}
