package net.exylia.lib.database.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Turning whatever a database handed back into the type a record component
 * actually declares.
 *
 * <h2>Why this exists at all</h2>
 * A driver returns the type <em>it</em> chose, not the one the record declares,
 * and the two disagree constantly:
 *
 * <ul>
 *   <li>MySQL returns {@code Integer} for a {@code BIGINT} that fits in one, and
 *       {@code Long} for the same column when it does not.</li>
 *   <li>SQLite has no boolean: a {@code boolean} column comes back as
 *       {@code Integer} 0 or 1, and sometimes as the string {@code "true"}.</li>
 *   <li>H2 returns {@code BigDecimal} for a {@code DECIMAL} column a record
 *       declares as {@code double}.</li>
 *   <li>Mongo stores a {@code float} as a {@code Double}, because BSON has no
 *       32-bit float.</li>
 * </ul>
 *
 * <p>Casting straight to the declared type works on the engine it was tested
 * against and throws {@link ClassCastException} on the next one. Every one of
 * those is a bug that only appears in production, on the one server running the
 * other database.
 *
 * <h2>Null</h2>
 * A {@code null} in a primitive column becomes that primitive's zero. The record
 * constructor cannot take {@code null} for an {@code int}, so the alternative is
 * a {@link NullPointerException} thrown from inside {@code MethodHandle.invoke}
 * — a stack trace that names neither the column nor the row. A column added to
 * a live table is {@code NULL} on every row that predates it, so this is the
 * normal case, not the exotic one.
 *
 * <p>A {@code null} in a boxed column stays {@code null}: a record declaring
 * {@code Integer} rather than {@code int} is asking to tell absence from zero,
 * and answering with zero would throw that distinction away.
 */
final class Coercions {

    /**
     * The zero of each primitive, boxed once.
     *
     * <p>Held rather than computed so that filling a null column is a map hit
     * and a reference copy, not an allocation. {@code Integer.valueOf(0)} is
     * already cached by the JDK, but {@code Float} and {@code Double} are not.
     */
    private static final Map<Class<?>, Object> PRIMITIVE_ZEROES = Map.of(
            int.class, 0,
            long.class, 0L,
            double.class, 0d,
            float.class, 0f,
            short.class, (short) 0,
            byte.class, (byte) 0,
            boolean.class, Boolean.FALSE,
            char.class, '\0');

    private Coercions() {
    }

    /**
     * The value a record constructor can accept for a column.
     *
     * @param stored  what the driver returned, possibly {@code null}
     * @param wanted  the record component's declared type
     * @return a value assignable to {@code wanted}, or {@code null} when the
     *         column is absent and the type can express absence
     */
    static @Nullable Object toJava(@Nullable Object stored, @NotNull Class<?> wanted) {
        if (stored == null) {
            return zeroOf(wanted);
        }
        if (wanted.isInstance(stored)) {
            return stored;
        }
        if (wanted == String.class) {
            return stored.toString();
        }
        if (wanted == boolean.class || wanted == Boolean.class) {
            return toBoolean(stored);
        }
        if (stored instanceof Number number) {
            Object narrowed = fromNumber(number, wanted);
            if (narrowed != null) {
                return narrowed;
            }
        }
        if (stored instanceof CharSequence text) {
            Object parsed = fromText(text.toString(), wanted);
            if (parsed != null) {
                return parsed;
            }
        }
        // Nothing sensible to do. Returning the value unchanged lets the record
        // constructor throw, and its ClassCastException names both types, which
        // is more than this method knows.
        return stored;
    }

    /**
     * The value that stands in for an absent one.
     *
     * @param wanted the declared type
     * @return the primitive's zero, or {@code null} for any reference type
     */
    static @Nullable Object zeroOf(@NotNull Class<?> wanted) {
        return wanted.isPrimitive() ? PRIMITIVE_ZEROES.get(wanted) : null;
    }

    /**
     * Reads a boolean out of whatever shape the engine chose for it.
     *
     * <p>Numeric zero is false and everything else is true, which is what every
     * engine without a boolean type means by the column. The textual forms are
     * there because a CSV import or a column that was once {@code VARCHAR}
     * leaves {@code "true"} and {@code "1"} sitting in a boolean column.
     */
    private static @NotNull Boolean toBoolean(@NotNull Object stored) {
        if (stored instanceof Boolean value) {
            return value;
        }
        if (stored instanceof Number number) {
            return number.longValue() != 0L;
        }
        String text = stored.toString();
        return text.equalsIgnoreCase("true") || text.equals("1") || text.equalsIgnoreCase("t")
                || text.equalsIgnoreCase("yes") || text.equalsIgnoreCase("y");
    }

    /**
     * Widens or narrows a number to the declared type.
     *
     * <p>Narrowing is allowed on purpose. A driver that hands back a
     * {@code Long} for a column a record declares as {@code int} is describing a
     * value that fits — the column is an {@code INT} in the schema the library
     * itself created — and refusing it would break reads on the engines that do
     * this, which is most of them.
     *
     * @return the converted number, or {@code null} when the type is not numeric
     */
    private static @Nullable Object fromNumber(@NotNull Number number, @NotNull Class<?> wanted) {
        if (wanted == int.class || wanted == Integer.class) {
            return number.intValue();
        }
        if (wanted == long.class || wanted == Long.class) {
            return number.longValue();
        }
        if (wanted == double.class || wanted == Double.class) {
            return number.doubleValue();
        }
        if (wanted == float.class || wanted == Float.class) {
            return number.floatValue();
        }
        if (wanted == short.class || wanted == Short.class) {
            return number.shortValue();
        }
        if (wanted == byte.class || wanted == Byte.class) {
            return number.byteValue();
        }
        if (wanted == BigDecimal.class) {
            // Through the decimal string rather than doubleValue: money is the
            // only reason a column is a BigDecimal, and routing it through a
            // binary double is how 0.1 becomes 0.09999999999999999.
            return number instanceof BigDecimal decimal ? decimal : new BigDecimal(number.toString());
        }
        return null;
    }

    /**
     * Parses a number a driver returned as text.
     *
     * <p>Happens with Mongo, where a value written by an older version of a
     * document is whatever type it was then, and with any column somebody
     * widened to {@code VARCHAR} by hand. An unparseable value yields the type's
     * zero rather than throwing: one malformed row must not fail a load of ten
     * thousand.
     *
     * @return the parsed number, or {@code null} when the type is not numeric
     */
    private static @Nullable Object fromText(@NotNull String text, @NotNull Class<?> wanted) {
        if (!isNumeric(wanted)) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return zeroOf(wanted);
        }
        try {
            if (wanted == BigDecimal.class) {
                return new BigDecimal(trimmed);
            }
            // Parsed as a decimal first: a MySQL DOUBLE column round-trips
            // through text as "42.0", and Integer.parseInt refuses that even
            // though the value is an integer.
            return fromNumber(new BigDecimal(trimmed), wanted);
        } catch (NumberFormatException malformed) {
            Object zero = zeroOf(wanted);
            return zero != null ? zero : Integer.valueOf(0);
        }
    }

    private static boolean isNumeric(@NotNull Class<?> wanted) {
        return wanted == int.class || wanted == Integer.class
                || wanted == long.class || wanted == Long.class
                || wanted == double.class || wanted == Double.class
                || wanted == float.class || wanted == Float.class
                || wanted == short.class || wanted == Short.class
                || wanted == byte.class || wanted == Byte.class
                || wanted == BigDecimal.class;
    }
}
