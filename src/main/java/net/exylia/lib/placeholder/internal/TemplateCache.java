package net.exylia.lib.placeholder.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.placeholder.Template;

import java.time.Duration;
import java.util.logging.Logger;

/**
 * Caches compiled templates so the same text is only analysed once.
 *
 * <p>Servers send the same strings over and over: a scoreboard line, a join
 * message, a menu title. Compiling is cheap but not free, and doing it per tick
 * per player is exactly the kind of waste this module exists to remove.
 *
 * <p>Bounded and expiring, so text that is generated once and never repeated
 * cannot grow the cache without limit.
 */
public final class TemplateCache {

    private static final Cache<String, CompiledTemplate> CACHE = Caffeine.newBuilder()
            .maximumSize(4096)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    private TemplateCache() {
    }

    /**
     * Returns the compiled form of a string, compiling it if needed.
     *
     * @param text   the raw text
     * @param logger where resolver failures are reported
     * @return the compiled template
     */
    public static CompiledTemplate get(String text, Logger logger) {
        CompiledTemplate cached = CACHE.getIfPresent(text);
        if (cached != null) {
            // The common case by far, and it avoids allocating the loader
            // lambda below, which captures the logger and so cannot be reused.
            return cached;
        }
        return CACHE.get(text, key -> compile(key, logger));
    }

    /**
     * Compiles without caching.
     *
     * <p>For text a caller will hold on to itself, which should not take a slot
     * from the strings that genuinely repeat.
     *
     * @param text   the raw text
     * @param logger where resolver failures are reported
     * @return a fresh compiled template
     */
    public static CompiledTemplate compile(String text, Logger logger) {
        return new CompiledTemplate(text, TemplateCompiler.compile(text, Registry::has), logger);
    }

    /**
     * Drops everything.
     *
     * <p>Called when a placeholder is registered or removed: that changes how
     * text splits into name and arguments, so previously compiled templates may
     * now be wrong.
     */
    public static void invalidate() {
        CACHE.invalidateAll();
    }

    /** Returns how many templates are cached, for diagnostics. */
    public static long size() {
        CACHE.cleanUp();
        return CACHE.estimatedSize();
    }

    /** Convenience for callers that only have text. */
    public static Template of(String text, Logger logger) {
        return get(text, logger);
    }
}
