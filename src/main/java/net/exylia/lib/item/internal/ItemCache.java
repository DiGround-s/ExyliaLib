package net.exylia.lib.item.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.item.Item;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;

/**
 * Finished items whose appearance cannot change.
 *
 * <p>A menu of decorations is the common case and the one worth making free:
 * fifty glass panes with a name and no placeholders are one render and forty-nine
 * copies, rather than fifty rounds of parsing text and writing metadata.
 *
 * <p>Only static items are held. Anything with a placeholder, a head template or
 * a dynamic trait is different per viewer, so caching it would be wrong rather
 * than merely useless.
 *
 * <h2>Correctness</h2>
 * Every entry holds text parsed with the palette that was active when it was
 * built, so the cache is dropped whenever the palette changes. Without that, a
 * recoloured server would keep handing out the old colours — the same bug static
 * effects had in 1.16.0.
 *
 * <p>Copies go out, never the entry itself: an {@code ItemStack} is mutable, and
 * one handed out directly would be renamed by the first caller for everybody.
 */
public final class ItemCache {

    /**
     * Rendered items, keyed by the definition that produced them.
     *
     * <p>{@link Item} is a record, so equality is by value and two identical
     * definitions from different files share one entry. Bounded and expiring:
     * a plugin generating definitions in a loop cannot turn this into a leak.
     */
    private static final Cache<Item, ItemStack> CACHE = Caffeine.newBuilder()
            .maximumSize(2048)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    private ItemCache() {
    }

    /**
     * Returns whether a definition may be cached.
     *
     * <p>Two reasons not to. A dynamic item is different per viewer, so a
     * shared copy would show one player another player's head. An item with
     * stored values is written under the owning plugin's namespace, so two
     * plugins sharing a definition must not share the result.
     *
     * <p>Separate from the rendering so it can be tested: building an
     * {@code ItemStack} needs a live server, and deciding what to cache does
     * not.
     *
     * @param definition the definition
     * @return whether one render can serve everybody
     */
    public static boolean isCacheable(Item definition) {
        return !definition.isDynamic() && definition.traits().data().isEmpty();
    }

    /**
     * Returns a copy of a cached item, or {@code null} when there is none.
     *
     * @param definition what was asked for
     * @return a fresh copy, or {@code null}
     */
    public static ItemStack get(Item definition) {
        ItemStack held = CACHE.getIfPresent(definition);
        return held == null ? null : held.clone();
    }

    /**
     * Remembers a finished item.
     *
     * <p>The stack is copied on the way in for the same reason it is copied on
     * the way out: the caller still holds theirs and may write on it.
     *
     * @param definition what produced it
     * @param item       the finished item
     */
    public static void put(Item definition, ItemStack item) {
        CACHE.put(definition, item.clone());
    }

    /**
     * Forgets everything.
     *
     * <p>Called when the palette changes: the text in these items is already
     * parsed, so what they say is right and what colour they say it in is not.
     */
    public static void invalidateAll() {
        CACHE.invalidateAll();
    }

    /** How many items are held, for diagnostics and tests. */
    public static long size() {
        CACHE.cleanUp();
        return CACHE.estimatedSize();
    }
}
