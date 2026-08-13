package net.exylia.lib.skull;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.UUID;

/**
 * Where the texture of a head comes from.
 *
 * <p>A source is a value, not a request: it can be compared, used as a cache
 * key and held in a config long before anything is fetched. Three kinds cover
 * everything the old ExyliaCommons could do:
 *
 * <ul>
 *   <li>{@link #texture(String)} — a base64 property, already complete;
 *   <li>{@link #url(String)} — a skin URL, wrapped into a texture locally;
 *   <li>{@link #player(String)} — a player name, which has to be resolved.
 * </ul>
 *
 * <p>Only the third kind ever touches the network, which is worth knowing:
 * heads written into a menu as base64 or a URL are free and instant, so a menu
 * built from those never waits for anything.
 *
 * @since 1.19.0
 */
public sealed interface SkullSource {

    /**
     * A cache key for this source.
     *
     * <p>Stable across restarts, so it can be written to disk, and cheap
     * enough to compute on a menu-building hot path.
     *
     * @return the key
     */
    @NotNull String key();

    /**
     * Returns whether resolving this source may need the network.
     *
     * <p>Callers that must not block, or must not queue work, use this to
     * decide whether a head is free.
     *
     * @return {@code true} when resolving may go out to Mojang
     */
    boolean needsLookup();

    /**
     * A head from a base64 texture property.
     *
     * <p>The value used in configs and shared on head websites. Nothing is
     * fetched: the string already carries the skin.
     *
     * @param base64 the texture property
     * @return the source
     */
    static @NotNull SkullSource texture(@NotNull String base64) {
        return new Texture(base64);
    }

    /**
     * A head from a skin URL.
     *
     * <p>Both the full {@code https://textures.minecraft.net/texture/<hash>}
     * form and the bare hash are accepted, because configs in the wild carry
     * both. The URL is wrapped into a texture property locally — again, no
     * network.
     *
     * @param url the skin URL, or its trailing hash
     * @return the source
     */
    static @NotNull SkullSource url(@NotNull String url) {
        return new Url(url);
    }

    /**
     * A head belonging to a player, by name.
     *
     * <p>The only kind that may need a lookup. Names are matched
     * case-insensitively, as Mojang treats them.
     *
     * @param name the player name
     * @return the source
     */
    static @NotNull SkullSource player(@NotNull String name) {
        return new PlayerName(name);
    }

    /**
     * A head belonging to a player, by unique id.
     *
     * <p>Preferred over {@link #player(String)} when the id is already known:
     * it skips the name-to-id lookup, which is a whole HTTP round trip, and it
     * survives the player renaming themselves.
     *
     * @param id the player's unique id
     * @return the source
     */
    static @NotNull SkullSource player(@NotNull UUID id) {
        return new PlayerId(id);
    }

    /** A base64 texture property. */
    record Texture(@NotNull String base64) implements SkullSource {
        @Override
        public @NotNull String key() {
            // Long base64 strings make poor map keys and worse file names; the
            // hash is stable, short and collision-free enough for a cache.
            return "t:" + Integer.toHexString(base64.hashCode());
        }

        @Override
        public boolean needsLookup() {
            return false;
        }
    }

    /** A skin URL, or the bare texture hash. */
    record Url(@NotNull String url) implements SkullSource {
        @Override
        public @NotNull String key() {
            return "u:" + url;
        }

        @Override
        public boolean needsLookup() {
            return false;
        }
    }

    /** A player name, resolved through Mojang. */
    record PlayerName(@NotNull String name) implements SkullSource {
        @Override
        public @NotNull String key() {
            return "p:" + name.toLowerCase(Locale.ROOT);
        }

        @Override
        public boolean needsLookup() {
            return true;
        }
    }

    /** A player's unique id, resolved through Mojang. */
    record PlayerId(@NotNull UUID id) implements SkullSource {
        @Override
        public @NotNull String key() {
            return "i:" + id;
        }

        @Override
        public boolean needsLookup() {
            return true;
        }
    }
}
