package net.exylia.lib.region;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Result of resolving a policy at a point.
 *
 * @param key resolved policy key
 * @param value effective value, either explicit or default
 * @param source region that explicitly supplied the value, or {@code null} for the key default
 * @param <T> policy value type
 * @since 1.23.0
 */
public record PolicyResolution<T>(@NotNull PolicyKey<T> key, @NotNull T value,
                                  @Nullable RegionSnapshot source) {

    /** Validates the resolution and its typed value. */
    public PolicyResolution {
        Objects.requireNonNull(key, "key");
        value = key.cast(value);
    }

    /** Returns whether the value came from an explicit region declaration. */
    public boolean explicit() {
        return source != null;
    }
}
