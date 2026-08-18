package net.exylia.lib.text.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.text.Palette;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns text into components, as cheaply as the text allows.
 *
 * <p>The pipeline is deliberately staged so that most calls skip most of it:
 * <ol>
 *   <li>scan once for formatting characters;</li>
 *   <li>if there are none, build a plain component and stop;</li>
 *   <li>otherwise expand palette tokens, rewrite legacy codes to MiniMessage,
 *       and parse once;</li>
 *   <li>cache the result, because the same strings recur constantly.</li>
 * </ol>
 *
 * <p>The cache is what makes scoreboards and action bars affordable: a line that
 * is rebuilt every tick is parsed once and then reused until its text actually
 * changes. It is bounded and expiring, so a plugin that generates unique strings
 * forever cannot turn it into a leak.
 */
public final class TextEngine {

    /**
     * Cached parses, keyed by the raw text.
     *
     * <p>Sized for the working set of a busy server: scoreboard lines, menu
     * titles, repeated messages. Entries expire so that text from a since
     * unloaded menu does not sit there forever, and the bound caps the worst
     * case regardless.
     */
    private static final Cache<String, Component> CACHE = Caffeine.newBuilder()
            .maximumSize(4096)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /** Palette tokens mapped to the MiniMessage tag they expand to. */
    private static volatile Map<String, String> tokens = tokensOf(new Palette());

    /** Whether every line is drawn as small capitals. */
    private static volatile boolean smallText;

    private TextEngine() {
    }

    /**
     * Turns small capitals on or off for the whole server.
     *
     * <p>Cached components were built in the other style, so the cache is
     * dropped — the same reason a palette change drops it. Called by ExyliaLib
     * when its config is loaded or reloaded; nothing else should.
     *
     * @param enabled whether letters are drawn as small capitals
     */
    public static void smallText(boolean enabled) {
        if (smallText == enabled) {
            return;
        }
        smallText = enabled;
        CACHE.invalidateAll();
    }

    /** Returns whether small capitals are on. */
    public static boolean smallText() {
        return smallText;
    }

    /**
     * Replaces the active palette.
     *
     * <p>Every cached component was built with the previous colours, so the
     * cache is dropped. Reloading a palette is rare; parsing is not.
     *
     * @param palette the new palette
     */
    public static void palette(Palette palette) {
        tokens = tokensOf(palette);
        CACHE.invalidateAll();
    }

    /** Returns the current token table, for diagnostics and tests. */
    public static Map<String, String> tokens() {
        return tokens;
    }

    /**
     * Parses text into a component, using the cache.
     *
     * @param text the raw text
     * @return the parsed component
     */
    public static Component parse(String text) {
        if (text.isEmpty()) {
            return Component.empty();
        }

        int flags = FormatScanner.scan(text);
        if (FormatScanner.isPlain(flags)) {
            // The common case: no formatting at all. Not worth a cache entry,
            // since building a plain component is cheaper than hashing the key.
            return Component.text(smallText ? SmallText.apply(text) : text);
        }

        Component cached = CACHE.getIfPresent(text);
        if (cached != null) {
            return cached;
        }

        Component parsed = parseUncached(text, flags);
        CACHE.put(text, parsed);
        return parsed;
    }

    /**
     * Parses without touching the cache.
     *
     * <p>Used for text that is known to be unique, such as a line that already
     * had per-player values substituted into it. Caching those would evict the
     * entries that actually repeat.
     *
     * @param text the raw text
     * @return the parsed component
     */
    public static Component parseUncached(String text) {
        if (text.isEmpty()) {
            return Component.empty();
        }
        int flags = FormatScanner.scan(text);
        if (FormatScanner.isPlain(flags)) {
            return Component.text(smallText ? SmallText.apply(text) : text);
        }
        return parseUncached(text, flags);
    }

    private static Component parseUncached(String text, int flags) {
        // Before anything expands: a palette token is left alone here, but
        // once it becomes "<#8a51c4>" it is indistinguishable from a tag the
        // author wrote, and the hex digits inside it are letters.
        String prepared = smallText ? SmallText.apply(text) : text;

        if (FormatScanner.has(flags, FormatScanner.BRACE)) {
            prepared = TokenResolver.resolve(prepared, tokens);
            // Expanding a token introduces '<', which the next stage must see.
            flags |= FormatScanner.ANGLE;
        }

        prepared = LegacyTranslator.toMiniMessage(prepared, flags);

        try {
            return MINI_MESSAGE.deserialize(prepared);
        } catch (RuntimeException exception) {
            // A malformed tag must not cost the caller its message. Showing the
            // raw text is far more useful than an exception in the log.
            return Component.text(text);
        }
    }

    private static Map<String, String> tokensOf(Palette palette) {
        Map<String, String> result = new HashMap<>(32);
        put(result, "primary", palette.primary());
        put(result, "secondary", palette.secondary());
        put(result, "secondary_light", palette.secondaryLight());
        put(result, "letters", palette.letters());
        put(result, "letters_black", palette.lettersBlack());
        put(result, "error", palette.error());
        put(result, "success", palette.success());
        put(result, "success_light", palette.successLight());
        put(result, "warning", palette.warning());
        put(result, "warning_light", palette.warningLight());
        put(result, "info", palette.info());
        put(result, "info_light", palette.infoLight());
        put(result, "accent", palette.accent());
        put(result, "neutral", palette.neutral());
        put(result, "highlight", palette.highlight());
        put(result, "muted", palette.muted());
        return Map.copyOf(result);
    }

    /**
     * Registers a token under both its canonical name and a camelCase alias, so
     * {@code {secondary_light}} and {@code {secondaryLight}} both work.
     */
    private static void put(Map<String, String> target, String name, String hex) {
        String tag = "<" + normaliseHex(hex) + ">";
        target.put(name, tag);
        int underscore = name.indexOf('_');
        if (underscore > 0) {
            StringBuilder camel = new StringBuilder(name.length());
            boolean upper = false;
            for (int i = 0; i < name.length(); i++) {
                char character = name.charAt(i);
                if (character == '_') {
                    upper = true;
                } else {
                    camel.append(upper ? Character.toUpperCase(character) : character);
                    upper = false;
                }
            }
            target.put(camel.toString(), tag);
        }
    }

    /**
     * Accepts the ways people write a colour, so a palette edited by hand still
     * works: {@code #8a51c4}, {@code 8a51c4}, or a named colour.
     */
    private static String normaliseHex(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("<") && trimmed.endsWith(">")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.startsWith("#")) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        if (trimmed.length() == 6 && isHex(trimmed)) {
            return "#" + trimmed.toLowerCase(Locale.ROOT);
        }
        // A named colour such as "red", left for MiniMessage to resolve.
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static boolean isHex(String candidate) {
        for (int i = 0; i < candidate.length(); i++) {
            char character = candidate.charAt(i);
            boolean valid = (character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F');
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    /** Empties the cache. Used when the palette changes and by tests. */
    public static void invalidate() {
        CACHE.invalidateAll();
    }

    /** Returns how many parses are currently cached, for diagnostics. */
    public static long cacheSize() {
        CACHE.cleanUp();
        return CACHE.estimatedSize();
    }
}
