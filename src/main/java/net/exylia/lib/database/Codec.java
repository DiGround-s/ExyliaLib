package net.exylia.lib.database;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Turns a value into something a database can store, and back.
 *
 * <p>Register one for a type the library does not already know:
 *
 * <pre>{@code
 * Databases.codec(MyThing.class, Codec.of(
 *         thing -> thing.name() + ":" + thing.count(),
 *         text -> new MyThing(text.split(":")[0], Integer.parseInt(text.split(":")[1]))));
 * }</pre>
 *
 * <p>The library already carries codecs for {@code ItemStack},
 * {@code ItemStack[]}, {@code Location}, {@code UUID}, enums and lists of any
 * of them, in the exact format ExyliaCommons wrote — a server that swaps the
 * library reads its existing rows unchanged.
 *
 * <h2>Threads</h2>
 * Called on whichever thread is reading or writing, which is a background one.
 * A codec must not touch the Bukkit API beyond what is safe there:
 * {@code ItemStack} serialisation is, looking up a {@code World} by name is,
 * spawning something is not.
 *
 * <h2>Null</h2>
 * Never asked to encode or decode {@code null}; the mapper handles absence
 * itself, so a codec that returns {@code null} is saying the value could not be
 * represented, which is reported rather than stored.
 *
 * @param <T> the type stored
 * @since 1.24.0
 */
public interface Codec<T> {

    /**
     * Encodes a value.
     *
     * @param value the value, never {@code null}
     * @return the stored form, or {@code null} when it cannot be represented
     */
    @Nullable String encode(@NotNull T value);

    /**
     * Decodes a stored value.
     *
     * @param stored the stored form, never {@code null} or empty
     * @return the value, or {@code null} when the stored form is unreadable
     */
    @Nullable T decode(@NotNull String stored);

    /**
     * A codec from two functions.
     *
     * @param encoder turns a value into its stored form
     * @param decoder turns a stored form back into a value
     * @param <T>     the type stored
     * @return the codec
     */
    static <T> @NotNull Codec<T> of(@NotNull java.util.function.Function<T, String> encoder,
                                    @NotNull java.util.function.Function<String, T> decoder) {
        return new Codec<>() {
            @Override
            public String encode(@NotNull T value) {
                return encoder.apply(value);
            }

            @Override
            public T decode(@NotNull String stored) {
                return decoder.apply(stored);
            }
        };
    }
}
