package net.exylia.lib.config.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Converts raw YAML values into the types a schema declares.
 *
 * <p>YAML is edited by hand, so the parsed type rarely matches exactly: a value
 * meant to be a {@code long} arrives as an {@code Integer}, and someone writes
 * {@code "20"} with quotes. Rejecting those would be technically correct and
 * practically useless, so anything unambiguous is accepted.
 *
 * <p>What is <em>not</em> accepted is a guess that could silently change
 * behaviour. {@code "yes"} is a boolean because YAML users mean it that way;
 * {@code "banana"} is not a number, and produces a reported issue plus the
 * default rather than a zero nobody asked for.
 */
final class Coercions {

    private Coercions() {
    }

    /** Result of a conversion: either a value, or a description of what was expected. */
    record Result(Object value, String expected) {

        static Result ok(Object value) {
            return new Result(value, null);
        }

        static Result fail(String expected) {
            return new Result(null, expected);
        }

        boolean failed() {
            return expected != null;
        }
    }

    /**
     * Converts a raw value to the target type.
     *
     * @param raw    the value as parsed from YAML
     * @param target the type the schema declares
     * @param generic the generic type, used to find a list's element type
     * @return the converted value, or a failure describing what was expected
     */
    static Result coerce(Object raw, Class<?> target, java.lang.reflect.Type generic) {
        if (raw == null) {
            return Result.fail("a value");
        }

        if (target == String.class) {
            // Anything renders as text, including numbers people write unquoted.
            return Result.ok(String.valueOf(raw));
        }

        if (target == boolean.class || target == Boolean.class) {
            return toBoolean(raw);
        }

        if (target == int.class || target == Integer.class) {
            return toWhole(raw, Integer.MIN_VALUE, Integer.MAX_VALUE, "a whole number", value -> (int) value);
        }

        if (target == long.class || target == Long.class) {
            return toWhole(raw, Long.MIN_VALUE, Long.MAX_VALUE, "a whole number", value -> value);
        }

        if (target == double.class || target == Double.class) {
            return toDecimal(raw, Number::doubleValue);
        }

        if (target == float.class || target == Float.class) {
            return toDecimal(raw, Number::floatValue);
        }

        if (target.isEnum()) {
            return toEnum(raw, target);
        }

        if (List.class.isAssignableFrom(target)) {
            return toList(raw, generic);
        }

        if (target.isInstance(raw)) {
            return Result.ok(raw);
        }

        return Result.fail("a " + target.getSimpleName());
    }

    private static Result toBoolean(Object raw) {
        if (raw instanceof Boolean value) {
            return Result.ok(value);
        }
        String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "true", "yes", "on", "enabled", "1" -> Result.ok(Boolean.TRUE);
            case "false", "no", "off", "disabled", "0" -> Result.ok(Boolean.FALSE);
            default -> Result.fail("true or false");
        };
    }

    private static Result toWhole(Object raw, long min, long max, String expected,
                                  java.util.function.LongFunction<Object> narrow) {
        Long parsed = null;
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            // A decimal where a whole number belongs is a real mistake, not a
            // rounding opportunity: 0.5 seconds silently becoming 0 is worse.
            if (value == Math.floor(value) && !Double.isInfinite(value)) {
                parsed = number.longValue();
            }
        } else {
            try {
                parsed = Long.parseLong(String.valueOf(raw).trim());
            } catch (NumberFormatException ignored) {
                // reported below
            }
        }

        if (parsed == null) {
            return Result.fail(expected);
        }
        if (parsed < min || parsed > max) {
            return Result.fail(expected + " between " + min + " and " + max);
        }
        return Result.ok(narrow.apply(parsed));
    }

    private static Result toDecimal(Object raw, java.util.function.Function<Number, Object> narrow) {
        if (raw instanceof Number number) {
            return Result.ok(narrow.apply(number));
        }
        try {
            return Result.ok(narrow.apply(Double.parseDouble(String.valueOf(raw).trim())));
        } catch (NumberFormatException ignored) {
            return Result.fail("a number");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Result toEnum(Object raw, Class<?> target) {
        String text = String.valueOf(raw).trim().replace('-', '_').toUpperCase(Locale.ROOT);
        try {
            return Result.ok(Enum.valueOf((Class<? extends Enum>) target, text));
        } catch (IllegalArgumentException ignored) {
            Object[] constants = target.getEnumConstants();
            List<String> names = new ArrayList<>(constants.length);
            for (Object constant : constants) {
                names.add(((Enum<?>) constant).name().toLowerCase(Locale.ROOT));
            }
            return Result.fail("one of " + String.join(", ", names));
        }
    }

    private static Result toList(Object raw, java.lang.reflect.Type generic) {
        List<?> source;
        if (raw instanceof List<?> list) {
            source = list;
        } else {
            // A single value where a list belongs is a common shorthand, and
            // treating it as a one element list is unambiguous.
            source = List.of(raw);
        }

        Class<?> element = elementType(generic);
        if (element == null || element == Object.class) {
            return Result.ok(List.copyOf(source));
        }

        List<Object> converted = new ArrayList<>(source.size());
        for (Object item : source) {
            Result result = coerce(item, element, element);
            if (result.failed()) {
                return Result.fail("a list of " + element.getSimpleName().toLowerCase(Locale.ROOT) + " values");
            }
            converted.add(result.value());
        }
        return Result.ok(List.copyOf(converted));
    }

    private static Class<?> elementType(java.lang.reflect.Type generic) {
        if (generic instanceof java.lang.reflect.ParameterizedType parameterized) {
            java.lang.reflect.Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length == 1 && arguments[0] instanceof Class<?> element) {
                return element;
            }
        }
        return null;
    }
}
