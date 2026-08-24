package net.exylia.lib.util.loot;

import net.exylia.lib.util.loot.internal.LootItems;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compact grammar a loot pool is written in.
 *
 * <p>{@code MATERIAL MIN MAX WEIGHT [TIER]} is what every event config in the
 * ecosystem holds, so what counts as a readable line is not ours to change.
 *
 * <p>Not one of these needs a server: building the item is the module's single
 * Bukkit seam and a stand-in fills it, which is the whole reason the seam
 * exists. What is tested here is the grammar and what it refuses.
 */
class LootLinesTest {

    private final List<String> problems = new ArrayList<>();
    private final Items items = new Items();

    private LootEntry parse(String line) {
        return net.exylia.lib.util.loot.internal.LootLines.parse(
                line, items, (where, problem) -> problems.add(where + ": " + problem));
    }

    @Test
    @DisplayName("a four-token line is an item entry with its amounts and weight")
    void plain() {
        LootEntry entry = parse("DIAMOND 1 3 25.5");

        assertNotNull(entry);
        assertEquals(LootType.ITEM, entry.type());
        assertEquals("bytes:DIAMOND", entry.itemSnapshot());
        assertEquals(1, entry.minAmount());
        assertEquals(3, entry.maxAmount());
        assertEquals(25.5, entry.weight());
        assertNull(entry.tier());
        assertTrue(problems.isEmpty(), problems::toString);
    }

    @Test
    @DisplayName("a fifth token is the tier, uppercased as the tables hold it")
    void tier() {
        assertEquals("RARE", parse("DIAMOND 1 1 5 rare").tier());
    }

    @Test
    @DisplayName("a tier written with spaces survives whole")
    void tierWithSpaces() {
        assertEquals("VERY RARE", parse("DIAMOND 1 1 5 very rare").tier());
    }

    @Test
    @DisplayName("the material token is uppercased and spacing is free")
    void spacing() {
        assertEquals("bytes:GOLDEN_APPLE", parse("  golden_apple   1  2   40  ").itemSnapshot());
    }

    @Test
    @DisplayName("a potion prefix reaches the item seam untouched")
    void potionPrefix() {
        assertEquals("bytes:SPLASH:HEALING", parse("splash:healing 1 1 20").itemSnapshot());
    }

    @Test
    @DisplayName("a line with too few tokens is skipped and reported")
    void tooShort() {
        assertNull(parse("DIAMOND 1 3"));
        assertEquals(1, problems.size(), problems::toString);
    }

    @Test
    @DisplayName("amounts and weight that are not numbers are skipped")
    void notNumbers() {
        assertNull(parse("DIAMOND one 3 25"));
        assertNull(parse("DIAMOND 1 3 lots"));
        assertEquals(2, problems.size(), problems::toString);
    }

    @Test
    @DisplayName("an amount of zero, a range the wrong way round and a weight of zero are refused")
    void impossibleNumbers() {
        assertNull(parse("DIAMOND 0 3 25"));
        assertNull(parse("DIAMOND 5 3 25"));
        assertNull(parse("DIAMOND 1 3 0"));
        assertEquals(3, problems.size(), problems::toString);
    }

    @Test
    @DisplayName("a material the server does not know costs the line, and says which")
    void unknownMaterial() {
        assertNull(parse("NOT_A_THING 1 1 5"));
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("NOT_A_THING"), problems::toString);
    }

    @Test
    @DisplayName("a blank line is nothing at all, not a problem to report")
    void blank() {
        assertNull(parse(null));
        assertNull(parse(""));
        assertNull(parse("   "));
        assertTrue(problems.isEmpty(), problems::toString);
    }

    @Test
    @DisplayName("both spellings of a renamed potion find the same type")
    void aliases() {
        assertEquals("SWIFTNESS", LootItems.alias("SPEED"));
        assertEquals("SPEED", LootItems.alias("SWIFTNESS"));
        assertEquals("HEALING", LootItems.alias("INSTANT_HEAL"));
        assertEquals("HARMING", LootItems.alias("INSTANT_DAMAGE"));
        assertEquals("LEAPING", LootItems.alias("JUMP"));
        assertEquals("REGENERATION", LootItems.alias("REGEN"));
        assertNull(LootItems.alias("STRENGTH"));
    }

    /** The item seam, without a registry: a token is an item if we say so. */
    private static final class Items implements LootItems {

        private static final Set<String> KNOWN = Set.of(
                "DIAMOND", "GOLDEN_APPLE", "BREAD", "SPLASH:HEALING");

        @Override
        public ItemStack of(String token) {
            return KNOWN.contains(token) ? new Named(token) : null;
        }

        @Override
        public String snapshot(ItemStack item) {
            return "bytes:" + ((Named) item).token;
        }

        @Override
        public ItemStack build(String snapshot) {
            return new Named(snapshot);
        }
    }

    /** An item that is only the token it came from. */
    private static final class Named extends ItemStack {

        private final String token;

        private Named(String token) {
            this.token = token;
        }
    }
}
