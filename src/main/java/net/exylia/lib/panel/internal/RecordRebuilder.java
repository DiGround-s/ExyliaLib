package net.exylia.lib.panel.internal;

import net.exylia.lib.input.Validation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Puts a record back together from the values a panel is holding.
 *
 * <p>Plain JDK reflection over {@link Class#getRecordComponents()} and the
 * canonical constructor — public API on a public class. The panel deliberately
 * does not reach into {@code net.exylia.lib.config.internal} for this: the
 * schema it draws from is a pure value, and keeping the rebuild here is what
 * lets it stay one.
 *
 * <h2>It is pure, and that is the contract</h2>
 * Nothing here writes anything. The rebuild happens <em>before</em> a write path
 * exists, so a value the record refuses costs a message to the player and
 * nothing else: the working copy is untouched, the config file is never opened,
 * and there is no half-applied state to undo.
 *
 * <p>That matters because a compact constructor is allowed to say no.
 * {@code Effects.ParsedEffect} throws on a blank name; several config records
 * normalise or reject in theirs. Wrapping {@code ConfigFile.update} in a
 * try/catch would put the refusal <em>after</em> the decision to write, which is
 * the wrong side of it.
 *
 * <h2>Threads</h2>
 * Any thread. There is no Bukkit API here, no I/O and no shared state, so a
 * panel computes a candidate record wherever it happens to be and only then
 * goes looking for the thread that owns the file.
 */
@ApiStatus.Internal
public final class RecordRebuilder {

    private RecordRebuilder() {
        throw new AssertionError("No instances.");
    }

    /**
     * The outcome of a rebuild: a record, or the reason there is not one.
     *
     * <p>Deliberately not an {@code Optional<T>} alone. A rejection has to say
     * <em>what</em> was wrong in words a player can act on — "an effect needs a
     * name", not "empty" — because the message is what they are shown.
     *
     * @param <T> the record type
     */
    public static final class Rebuilt<T> {

        private final @Nullable T value;
        private final @Nullable String rejection;

        private Rebuilt(@Nullable T value, @Nullable String rejection) {
            this.value = value;
            this.rejection = rejection;
        }

        /** Whether there is a record to write. */
        public boolean accepted() {
            return rejection == null;
        }

        /** The rebuilt record, or {@code null} when it was rejected. */
        public @Nullable T value() {
            return value;
        }

        /** Why it was rejected, or {@code null} when it was accepted. */
        public @Nullable String rejection() {
            return rejection;
        }

        /**
         * The same answer as the input module states it.
         *
         * <p>A rejection is shown to a player, and the panel asks its questions
         * through the input module, so it says no the way every other refusal in
         * this library does rather than inventing a second shape for it.
         *
         * @return valid when accepted, otherwise an error carrying the rejection
         */
        public @NotNull Validation validation() {
            return rejection == null ? Validation.ok() : Validation.error(rejection);
        }
    }

    /**
     * Rebuilds a record from component values, in declared order.
     *
     * <p>Order comes from the record, never from the map: a map handed over with
     * its entries in a different order would otherwise pass a name into an age
     * slot whenever the two happen to be type-compatible, and the constructor
     * would accept it.
     *
     * <p>Never throws for a bad value. Both ways a rebuild can fail —
     * {@link IllegalArgumentException} for a value of the wrong type, and
     * {@link InvocationTargetException} for a compact constructor that refused —
     * come back as a rejection carrying a message worth showing.
     *
     * @param type   the record class
     * @param values one entry per component, keyed by declared component name
     * @param <T>    the record type
     * @return the rebuilt record, or the reason there is not one
     */
    public static <T> @NotNull Rebuilt<T> rebuild(@NotNull Class<T> type,
                                                  @NotNull Map<String, Object> values) {
        RecordComponent[] components = type.getRecordComponents();
        if (components == null) {
            return rejected("Only a record can be rebuilt, and " + type.getSimpleName() + " is not one.");
        }

        Object[] arguments = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            RecordComponent component = components[index];
            if (!values.containsKey(component.getName())) {
                // Defaulting it silently would write a value nobody chose over
                // whatever the owner had in the file.
                return rejected("No value for \"" + component.getName() + "\".");
            }
            Object value = values.get(component.getName());
            String wrong = mismatch(component, value);
            if (wrong != null) {
                return rejected(wrong);
            }
            arguments[index] = value;
        }

        try {
            Constructor<T> canonical = canonical(type, components);
            canonical.setAccessible(true);
            return new Rebuilt<>(canonical.newInstance(arguments), null);
        } catch (InvocationTargetException refused) {
            // The record said no in its compact constructor. The wrapper's own
            // message is null; what a player can act on is the cause's.
            Throwable cause = refused.getCause();
            String message = cause == null ? null : cause.getMessage();
            return rejected(message == null || message.isBlank()
                    ? "That value was refused by " + type.getSimpleName() + "."
                    : message);
        } catch (IllegalArgumentException mismatch) {
            return rejected(describe(mismatch, type));
        } catch (ReflectiveOperationException unreachable) {
            // A record always has its canonical constructor, and the components
            // were read off this very class. Reported rather than swallowed, so
            // an impossible case does not become a silent no-op.
            return rejected("Could not rebuild " + type.getSimpleName() + ": " + unreachable);
        }
    }

    /**
     * Reads every component of a record, keyed by declared name.
     *
     * <p>This is where a panel's working copy comes from, so the order is the
     * order the panel draws in and the order a rebuild reads back.
     *
     * @param record the record to read
     * @return its components, in canonical-constructor order
     */
    public static @NotNull Map<String, Object> componentsOf(@NotNull Record record) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            try {
                component.getAccessor().setAccessible(true);
                values.put(component.getName(), component.getAccessor().invoke(record));
            } catch (ReflectiveOperationException unreachable) {
                throw new IllegalStateException(
                        "Could not read " + record.getClass().getSimpleName()
                                + "." + component.getName(), unreachable);
            }
        }
        return values;
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static <T> Constructor<T> canonical(Class<T> type, RecordComponent[] components)
            throws NoSuchMethodException {
        Class<?>[] parameters = new Class<?>[components.length];
        for (int index = 0; index < components.length; index++) {
            parameters[index] = components[index].getType();
        }
        return (Constructor<T>) type.getDeclaredConstructor(parameters);
    }

    /**
     * Why a value cannot go into a component, or {@code null} when it can.
     *
     * <p>Checked before the constructor rather than after, so the message names
     * the component. Reflection's own {@code IllegalArgumentException} says
     * "argument type mismatch" and nothing else, which sends a player looking at
     * the whole screen.
     */
    private static @Nullable String mismatch(RecordComponent component, @Nullable Object value) {
        Class<?> declared = component.getType();
        if (value == null) {
            // A reference component may legitimately be null; a primitive has no
            // empty value, and passing one throws inside the constructor.
            return declared.isPrimitive()
                    ? "\"" + component.getName() + "\" needs a value, and "
                    + declared.getSimpleName() + " has no empty one."
                    : null;
        }
        Class<?> accepted = declared.isPrimitive() ? boxed(declared) : declared;
        return accepted.isInstance(value)
                ? null
                : "\"" + component.getName() + "\" needs " + article(declared)
                + " " + declared.getSimpleName() + ".";
    }

    /** The wrapper a primitive component accepts through reflection. */
    private static Class<?> boxed(Class<?> primitive) {
        if (primitive == int.class) {
            return Integer.class;
        }
        if (primitive == long.class) {
            return Long.class;
        }
        if (primitive == double.class) {
            return Double.class;
        }
        if (primitive == float.class) {
            return Float.class;
        }
        if (primitive == boolean.class) {
            return Boolean.class;
        }
        if (primitive == short.class) {
            return Short.class;
        }
        if (primitive == byte.class) {
            return Byte.class;
        }
        if (primitive == char.class) {
            return Character.class;
        }
        return primitive;
    }

    private static String article(Class<?> type) {
        return "aeiou".indexOf(Character.toLowerCase(type.getSimpleName().charAt(0))) >= 0 ? "an" : "a";
    }

    private static String describe(IllegalArgumentException mismatch, Class<?> type) {
        String message = mismatch.getMessage();
        return message == null || message.isBlank()
                ? "Those values do not fit " + type.getSimpleName() + "."
                : message;
    }

    private static <T> Rebuilt<T> rejected(String reason) {
        return new Rebuilt<>(null, reason);
    }
}
