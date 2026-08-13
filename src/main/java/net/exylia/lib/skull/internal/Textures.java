package net.exylia.lib.skull.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Turns skin URLs into the base64 texture property Minecraft expects, and
 * reads them back.
 *
 * <p>The property is a small JSON document, base64-encoded:
 *
 * <pre>{@code {"textures":{"SKIN":{"url":"https://textures.minecraft.net/texture/<hash>"}}}}</pre>
 *
 * <p>Written by hand rather than through a JSON library: the shape is fixed
 * and one string concatenation is cheaper than a parser, on a path that a menu
 * of fifty heads walks fifty times. Reading back is done by locating the URL
 * between quotes for the same reason — this is not general JSON, it is one
 * known document.
 */
public final class Textures {

    private static final String PREFIX = "{\"textures\":{\"SKIN\":{\"url\":\"";
    private static final String SUFFIX = "\"}}}";

    /** The canonical host, prepended when a config carries only the hash. */
    private static final String TEXTURE_HOST = "https://textures.minecraft.net/texture/";

    private Textures() {
    }

    /**
     * Encodes a skin URL as a texture property.
     *
     * @param url the full URL, or the bare hash
     * @return the base64 property
     */
    public static String fromUrl(String url) {
        String full = normalise(url);
        String json = PREFIX + full + SUFFIX;
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Accepts the several forms a config writes a skin in.
     *
     * <p>Head websites hand out the bare hash, the full URL, and the URL
     * without a scheme, and all three end up pasted into YAML. Rejecting two
     * of them would be technically correct and practically useless.
     *
     * @param url the value as written
     * @return a full texture URL
     */
    private static String normalise(String url) {
        String trimmed = url.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        if (trimmed.startsWith("textures.minecraft.net/")) {
            return "https://" + trimmed;
        }
        return TEXTURE_HOST + trimmed;
    }

    /**
     * Reads the skin URL back out of a texture property.
     *
     * <p>Used to tell two textures apart without decoding them into objects,
     * and to validate a property before it is cached.
     *
     * @param base64 the texture property
     * @return the URL, or {@code null} when the property is not one
     */
    public static String urlOf(String base64) {
        String json;
        try {
            json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
        int urlKey = json.indexOf("\"url\"");
        if (urlKey < 0) {
            return null;
        }
        int open = json.indexOf('"', json.indexOf(':', urlKey) + 1);
        if (open < 0) {
            return null;
        }
        int close = json.indexOf('"', open + 1);
        return close < 0 ? null : json.substring(open + 1, close);
    }

    /**
     * Returns whether a string is a usable texture property.
     *
     * <p>Cheap enough to run before caching or persisting a value: a truncated
     * base64 string that decodes to nothing renders as a blank head, and
     * finding that out at render time means it is already in the cache.
     *
     * @param base64 the candidate
     * @return whether it decodes to a texture document with a URL
     */
    public static boolean isValid(String base64) {
        return base64 != null && !base64.isEmpty() && urlOf(base64) != null;
    }
}
