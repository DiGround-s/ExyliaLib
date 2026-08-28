package net.exylia.lib.region;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Objects;

/**
 * The stable, namespaced identifier of a region or region policy.
 *
 * <p>Both parts use the same grammar as action identifiers: lower-case ASCII letters, digits,
 * underscores, periods, and hyphens. Input is trimmed and normalized to lower case.
 *
 * @param namespace the module or plugin that owns the identifier
 * @param value the identifier within that namespace
 * @since 1.23.0
 */
public record RegionId(@NotNull String namespace, @NotNull String value)
        implements Comparable<RegionId> {

    /** Validates and normalizes both identifier parts. */
    public RegionId {
        namespace = normalize(namespace, "namespace");
        value = normalize(value, "value");
    }

    /**
     * Parses an identifier written as {@code namespace:value}.
     *
     * @param raw the complete identifier
     * @return the parsed identifier
     * @throws IllegalArgumentException if the identifier is malformed
     */
    public static @NotNull RegionId parse(@NotNull String raw) {
        Objects.requireNonNull(raw, "raw");
        int colon = raw.indexOf(':');
        if (colon <= 0 || colon == raw.length() - 1 || raw.indexOf(':', colon + 1) >= 0) {
            throw new IllegalArgumentException("Region id must be namespace:value, got: " + raw);
        }
        return new RegionId(raw.substring(0, colon), raw.substring(colon + 1));
    }

    /**
     * Turns text somebody typed into something this grammar accepts.
     *
     * <pre>{@code
     * RegionId.sanitize("Arena Two/zone");   // "arena-two-zone"
     * }</pre>
     *
     * <p>A region id is normally built out of names an admin wrote — a config
     * id, a zone name, an arena — and those obey no grammar. Without this every
     * caller either writes the character class out again or, more often,
     * discovers the rule when a slash reaches the constructor and takes down
     * whatever was starting.
     *
     * <p>Runs of anything unacceptable collapse into a single hyphen, so
     * {@code "a // b"} is {@code "a-b"} rather than {@code "a---b"}.
     *
     * @param raw the text, in whatever shape it arrived
     * @return an identifier part this record accepts
     * @throws IllegalArgumentException if nothing usable is left, which needs
     *         text holding no letter or digit at all
     * @since 1.72.3
     */
    public static @NotNull String sanitize(@NotNull String raw) {
        Objects.requireNonNull(raw, "raw");
        String cleaned = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "-");
        if (cleaned.isEmpty() || cleaned.chars().noneMatch(Character::isLetterOrDigit)) {
            throw new IllegalArgumentException(
                    "Nothing in \"" + raw + "\" can be part of a region id");
        }
        return cleaned;
    }

    private static String normalize(String input, String part) {
        Objects.requireNonNull(input, part);
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || !normalized.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid region " + part + ": " + input);
        }
        return normalized;
    }

    /** Orders identifiers lexicographically by their complete stable representation. */
    @Override
    public int compareTo(@NotNull RegionId other) {
        return toString().compareTo(Objects.requireNonNull(other, "other").toString());
    }

    /** Returns the complete {@code namespace:value} representation. */
    @Override
    public @NotNull String toString() {
        return namespace + ':' + value;
    }
}
