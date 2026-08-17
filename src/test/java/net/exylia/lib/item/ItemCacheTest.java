package net.exylia.lib.item;

import net.exylia.lib.item.internal.ItemCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which items are safe to render once and copy.
 *
 * <p>Only the decision is exercised, not the rendering: an {@code ItemStack}
 * cannot be built without a live server. That is fine, because every way this
 * can go wrong is a wrong answer here — a viewer seeing another viewer's head,
 * or one plugin's stored values on another plugin's item.
 */
class ItemCacheTest {

    @Test
    @DisplayName("an item with no placeholders is rendered once for everybody")
    void staticItemIsCacheable() {
        Item item = Item.of("BLACK_STAINED_GLASS_PANE")
                .appearance(Appearance.builder().hideTooltip(true).build())
                .build();

        assertTrue(ItemCache.isCacheable(item));
    }

    @Test
    @DisplayName("anything that differs per viewer is not shared")
    void dynamicItemsAreNotCacheable() {
        assertFalse(ItemCache.isCacheable(Item.of("STONE").name("%player_name%").build()),
                "a name that names the viewer");
        assertFalse(ItemCache.isCacheable(Item.of("playerhead-%player_name%").build()),
                "a head that belongs to the viewer");
        assertFalse(ItemCache.isCacheable(Item.of("%kit_icon%").build()),
                "a material the viewer decides");
        assertFalse(ItemCache.isCacheable(Item.of("STONE").amount("%owned%").build()),
                "an amount the viewer decides");
        assertFalse(ItemCache.isCacheable(Item.of("STONE")
                        .lore(List.of("{letters}Coins: %coins%")).build()),
                "lore that quotes the viewer");
    }

    @Test
    @DisplayName("an item carrying stored values is not shared between plugins")
    void storedValuesAreNotCacheable() {
        // The values go under the owning plugin's namespace, so the same
        // definition rendered by two plugins is two different items.
        Item item = Item.of("STONE")
                .traits(Traits.builder().data(Map.of("kind", "special")).build())
                .build();

        assertFalse(ItemCache.isCacheable(item));
    }

    @Test
    @DisplayName("two identical definitions are one entry")
    void definitionsCompareByValue() {
        // Item is a record, so a menu that repeats the same filler in fifty
        // slots asks the cache the same question fifty times.
        Item first = Item.of("STONE").name("{primary}Rock").build();
        Item second = Item.of("STONE").name("{primary}Rock").build();

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    @DisplayName("a fixed trim is shared, one driven by a placeholder is not")
    void trimsDecideByWhetherTheyResolve() {
        assertTrue(ItemCache.isCacheable(Item.of("DIAMOND_HELMET")
                .traits(Traits.builder().trim(new Trim("sentry", "redstone")).build())
                .build()));

        assertFalse(ItemCache.isCacheable(Item.of("DIAMOND_HELMET")
                .traits(Traits.builder()
                        .trim(new Trim("%helmet_trim_pattern%", "%helmet_trim_material%"))
                        .build())
                .build()));
    }
}
