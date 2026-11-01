package net.exylia.lib.platform;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Looks up a Bukkit constant by its field name, on any supported server.
 *
 * <p>In 1.21.3 {@code Sound}, {@code Attribute}, {@code Biome} and a dozen
 * other types turned from enums into interfaces. {@code Sound.valueOf(name)}
 * compiled against the interface is an interface call, and on 1.21.1, where
 * the type is still an enum, it throws {@code IncompatibleClassChangeError}.
 * Both shapes keep their constants as public static fields, so this reads the
 * fields instead, which works on both.
 *
 * <pre>{@code
 * Sound sound = Constants.named(Sound.class, "ENTITY_PLAYER_LEVELUP");
 * NamespacedKey key = sound == null ? null : Registry.SOUNDS.getKey(sound);
 * }</pre>
 *
 * <p>For a constant's key, ask its registry ({@code Registry.SOUNDS.getKey(sound)}),
 * never {@code sound.getKey()}, for the same reason.
 *
 * @since 1.442.0
 */
public final class Constants {

    private static final Map<Class<?>, Map<String, Object>> BY_TYPE = new ConcurrentHashMap<>();

    private Constants() {
    }

    /**
     * The constant of {@code type} whose field is called {@code name}, ignoring case.
     *
     * @return the constant, or {@code null} when the type has no field by that name
     */
    public static <T> @Nullable T named(@NotNull Class<T> type, @NotNull String name) {
        String upper = name.trim().toUpperCase(Locale.ROOT);
        // The type's own valueOf first, reached by reflection so it binds to
        // whichever shape the server has. Paper's maps names a Minecraft update
        // renamed, which a plain field read would miss.
        try {
            Object found = type.getMethod("valueOf", String.class).invoke(null, upper);
            if (type.isInstance(found)) return type.cast(found);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError noSuchName) {
            // Unknown name, or no valueOf at all: the fields decide.
        }
        Object found = BY_TYPE.computeIfAbsent(type, Constants::read).get(upper);
        return type.isInstance(found) ? type.cast(found) : null;
    }

    private static Map<String, Object> read(Class<?> type) {
        Map<String, Object> constants = new HashMap<>();
        for (Field field : type.getFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || !type.isAssignableFrom(field.getType())) continue;
            try {
                Object value = field.get(null);
                if (value != null) constants.put(field.getName(), value);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
                // A constant the server cannot resolve is one there is not.
            }
        }
        return Map.copyOf(constants);
    }
}
