package net.exylia.lib.ability.internal;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the block matcher compiles: types, tags in both spellings, and the names it refuses. */
class BlockMatcherTest {

    private final List<String> problems = new ArrayList<>();

    private Set<Material> compile(Object written) {
        return BlockMatcher.compile(written, problems::add,
                tag -> tag.equals("minecraft:logs") ? List.of(Material.OAK_LOG, Material.BIRCH_LOG) : null);
    }

    @Test
    @DisplayName("types and tags of the keyed form add up")
    void keyed() {
        assertEquals(Set.of(Material.DIAMOND_ORE, Material.OAK_LOG, Material.BIRCH_LOG),
                compile(Map.of("types", List.of("DIAMOND_ORE"), "tags", List.of("minecraft:logs"))));
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    @DisplayName("the short form reads '#' as a tag and a single value as a list of one")
    void shortForm() {
        assertEquals(Set.of(Material.STONE, Material.OAK_LOG, Material.BIRCH_LOG),
                compile(List.of("stone", "#minecraft:logs")));
        assertEquals(Set.of(Material.STONE), compile("STONE"));
        assertTrue(compile(null).isEmpty());
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    @DisplayName("an unknown block and an unknown tag are each reported and skipped")
    void refused() {
        assertEquals(Set.of(Material.STONE),
                compile(Map.of("types", List.of("STONE", "NOPE"), "tags", "minecraft:nope")));
        assertEquals(2, problems.size(), problems.toString());
    }
}
