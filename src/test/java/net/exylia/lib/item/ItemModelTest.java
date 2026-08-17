package net.exylia.lib.item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a definition promises before anything is drawn.
 *
 * <p>The two questions that matter here are whether an item can change per
 * viewer — which decides whether a menu keeps re-rendering it — and which name
 * a plugin quotes back to a player.
 */
class ItemModelTest {

    @Test
    @DisplayName("an item with no placeholders anywhere is static")
    void staticItem() {
        Item item = Item.of("STONE")
                .name("{primary}Decoration")
                .lore(List.of("{letters}Just here to look at"))
                .build();

        assertFalse(item.isDynamic());
    }

    @Test
    @DisplayName("a placeholder anywhere makes an item dynamic")
    void dynamicSources() {
        assertTrue(Item.of("%kit_icon%").build().isDynamic(), "material");
        assertTrue(Item.of("STONE").name("Hi %player_name%").build().isDynamic(), "name");
        assertTrue(Item.of("STONE").lore(List.of("%coins%")).build().isDynamic(), "lore");
        assertTrue(Item.of("STONE").amount("%owned%").build().isDynamic(), "amount");
        assertTrue(Item.of("playerhead-%player_name%").build().isDynamic(), "head template");
    }

    @Test
    @DisplayName("a trim whose pattern is a placeholder makes the item dynamic")
    void dynamicTrim() {
        Item item = Item.of("DIAMOND_HELMET")
                .traits(Traits.builder()
                        .trim(new Trim("%helmet_trim_pattern%", "%helmet_trim_material%"))
                        .build())
                .build();

        assertTrue(item.isDynamic());
    }

    @Test
    @DisplayName("a fixed trim leaves the item static")
    void staticTrim() {
        Item item = Item.of("DIAMOND_HELMET")
                .traits(Traits.builder().trim(new Trim("sentry", "redstone")).build())
                .build();

        assertFalse(item.isDynamic());
    }

    @Test
    @DisplayName("display-name is what a plugin quotes, not the painted name")
    void labelPrefersDisplayName() {
        // A special item's tooltip name is bold and gradient-filled; the name a
        // cooldown message says out loud is the plain one next to it.
        Item item = Item.of("RED_DYE")
                .name("&#ca9a12Damage &8[&#ffc58f%current_uses%&8]")
                .displayName("&#ca9a12Damage")
                .build();

        assertEquals("&#ca9a12Damage", item.label());
    }

    @Test
    @DisplayName("without a display-name the ordinary name is quoted")
    void labelFallsBackToName() {
        assertEquals("Sword", Item.of("DIAMOND_SWORD").name("Sword").build().label());
    }

    @Test
    @DisplayName("an item with neither name has no label")
    void labelCanBeAbsent() {
        assertEquals(null, Item.of("STONE").build().label());
    }

    @Test
    @DisplayName("an unremarkable appearance is the shared one")
    void plainAppearanceIsShared() {
        assertSame(Appearance.PLAIN, Appearance.builder().build());
        assertSame(Appearance.PLAIN, Appearance.builder().glow(false).modelData(-1).build());
    }

    @Test
    @DisplayName("traits with nothing in them are the shared none")
    void emptyTraitsAreShared() {
        assertSame(Traits.NONE, Traits.builder().build());
        assertSame(Traits.NONE, Traits.builder().modifiers(List.of()).data(Map.of()).build());
    }

    @Test
    @DisplayName("an attribute line is read as a name and an amount")
    void attributeLine() {
        Modifier modifier = Modifier.parse("attack_damage|8.5");

        assertEquals("attack_damage", modifier.attribute());
        assertEquals(8.5, modifier.amount());
    }

    @Test
    @DisplayName("attribute shorthands and the dropped GENERIC_ prefix resolve alike")
    void attributeAliases() {
        assertEquals("attack_damage", Modifier.parse("damage|1").key());
        assertEquals("attack_damage", Modifier.parse("GENERIC_ATTACK_DAMAGE|1").key());
        assertEquals("attack_damage", Modifier.parse("attack_damage|1").key());
        assertEquals("max_health", Modifier.parse("health|1").key());
        assertEquals("block_interaction_range",
                Modifier.parse("PLAYER_BLOCK_INTERACTION_RANGE|1").key());
    }

    @Test
    @DisplayName("a malformed attribute is reported, not silently dropped")
    void malformedAttribute() {
        // Commons swallowed these, so an administrator's typo looked exactly
        // like a working line until somebody noticed the sword hit for four.
        assertThrows(IllegalArgumentException.class, () -> Modifier.parse("attack_damage"));
        assertThrows(IllegalArgumentException.class, () -> Modifier.parse("attack_damage|lots"));
        assertThrows(IllegalArgumentException.class, () -> Modifier.parse("|8"));
    }

    @Test
    @DisplayName("a definition cannot be changed through the lists it was given")
    void defensivelyCopied() {
        List<String> lore = new java.util.ArrayList<>(List.of("one"));
        Item item = Item.of("STONE").lore(lore).build();

        lore.add("two");

        assertEquals(1, item.lore().size());
    }
}
