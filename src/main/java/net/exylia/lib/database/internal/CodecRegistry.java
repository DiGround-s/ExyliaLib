package net.exylia.lib.database.internal;

import net.exylia.lib.database.Codec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every codec the mapper can reach: the ones a plugin registered, and the ones
 * the library ships.
 *
 * <h2>Why a registry rather than a lookup on the type</h2>
 * A codec belongs to whoever owns the type, and the library owns almost none of
 * them. {@code ItemStack} and {@code Location} come from Bukkit, {@code Region}
 * comes from another module, and a plugin's own value type comes from the
 * plugin. None of them can carry a codec on themselves, so something has to
 * hold the association, and it has to be one thing: two registries means a type
 * encoded one way on write and read the other way back.
 *
 * <h2>Precedence</h2>
 * A registered codec wins over a built-in one. That is deliberate and it is the
 * only escape hatch there is for a table whose rows were written in a shape the
 * library's format does not match — a server with such a table can teach the
 * library its own format instead of migrating a million rows.
 *
 * <h2>Threads</h2>
 * Registration happens on the main thread at enable; lookup happens on whatever
 * background thread is reading or writing a row. A {@link ConcurrentHashMap}
 * covers both without a lock on the read path.
 *
 * <h2>Ordering</h2>
 * A codec must be registered before the first repository that needs it is
 * built. {@link EntityModel} resolves each column to a codec once, when the
 * record is compiled, and holds it: that is what keeps a row cheap. Registering
 * a codec afterwards does not change a model that was already compiled, and
 * registering one for a type the model rejected is too late — compilation
 * already failed, loudly, at enable.
 */
public final class CodecRegistry {

    private static final Map<Class<?>, Codec<?>> REGISTERED = new ConcurrentHashMap<>();

    private CodecRegistry() {
    }

    /**
     * Teaches the library how to store a type.
     *
     * <p>Replaces any previous registration for the same type, including a
     * built-in one. Silently, on purpose: a plugin overriding
     * {@code Location} because its rows predate the library is doing something
     * intentional, and a warning about it would be noise on every start.
     *
     * @param type  the type stored
     * @param codec how it is stored
     * @param <T>   the type stored
     */
    public static <T> void register(@NotNull Class<T> type, @NotNull Codec<T> codec) {
        REGISTERED.put(type, codec);
    }

    /**
     * The codec for a type, or {@code null} when the library cannot store it.
     *
     * <p>{@code null} is not a failure here — {@link EntityModel} asks this
     * question about every component and turns a {@code null} into a precise
     * registration error naming the component, which is a far more useful
     * message than anything this method could throw.
     *
     * @param type the type stored
     * @param <T>  the type stored
     * @return the codec, or {@code null}
     */
    @SuppressWarnings("unchecked")
    public static <T> @Nullable Codec<T> find(@NotNull Class<T> type) {
        Codec<T> registered = (Codec<T>) REGISTERED.get(type);
        return registered != null ? registered : Codecs.builtIn(type);
    }

    /**
     * Whether a type can be stored as text.
     *
     * @param type the type
     * @return whether a codec exists for it
     */
    public static boolean has(@NotNull Class<?> type) {
        return find(type) != null;
    }

    /**
     * Test seam: forgets every registered codec, leaving the built-ins.
     *
     * <p>Package-private because nothing in production should ever want it —
     * a codec disappearing mid-run makes rows unreadable — but a test that
     * registers one must not leak it into the next test.
     */
    static void forgetRegistered() {
        REGISTERED.clear();
    }
}
