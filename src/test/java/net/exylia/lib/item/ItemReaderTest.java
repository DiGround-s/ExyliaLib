package net.exylia.lib.item;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Items written for ExyliaCommons, read unchanged.
 *
 * <p>Every block of YAML here is copied from a deployed file rather than
 * written for the test. Thousands of these exist across the ecosystem and none
 * are going to be migrated, so reading them is the feature, not a courtesy.
 */
class ItemReaderTest {

    /** Collects what the reader could not make sense of. */
    private final List<String> problems = new ArrayList<>();

    private Item read(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (Exception invalid) {
            throw new IllegalStateException("test yaml is not valid", invalid);
        }
        return Items.parse(config, (where, problem) -> problems.add(where + ": " + problem));
    }

    @Test
    @DisplayName("a menu icon reads its material, name and lore")
    void menuIcon() {
        Item item = read("""
                material: DIAMOND_SWORD
                name: "{accent}&lKITS"
                lore:
                  - "{muted} ┃ {letters}Pick a kit"
                  - ""
                """);

        assertEquals("DIAMOND_SWORD", assertInstanceOf(Source.OfMaterial.class,
                item.source()).raw());
        assertEquals("{accent}&lKITS", item.name());
        assertEquals(2, item.lore().size());
        assertTrue(problems.isEmpty(), problems::toString);
    }

    @Test
    @DisplayName("a special item keeps its painted name and its plain one apart")
    void specialItem() {
        // ExyliaSpecialsV3, items/refill.yml.
        Item item = read("""
                material: "HOPPER"
                name: '&#3fa9f5Refill Kit &8[&#ffc58f%current_uses%&8/&#8fffc1%max_uses%&8]'
                display-name: '&#3fa9f5Refill Kit'
                glow: true
                hide-attributes: true
                max_stack_size: 1
                """);

        assertEquals("&#3fa9f5Refill Kit", item.displayName());
        assertEquals("&#3fa9f5Refill Kit", item.label());
        assertEquals("true", item.appearance().glow());
        assertTrue(item.appearance().hideAttributes());
        assertEquals(1, item.appearance().maxStackSize());
    }

    @Test
    @DisplayName("a tool reads enchantments written as a section")
    void enchantmentsAsSection() {
        // ExyliaSpecialsV3, tools/vein_miner.yml.
        Item item = read("""
                material: NETHERITE_PICKAXE
                enchantments:
                  EFFICIENCY: 5
                  UNBREAKING: 3
                  MENDING: 1
                unbreakable: true
                glow: true
                """);

        assertEquals(3, item.enchantments().size());
        assertEquals(5, item.enchantments().get("EFFICIENCY"));
        assertTrue(item.appearance().unbreakable());
    }

    @Test
    @DisplayName("enchantments written as a list are read too")
    void enchantmentsAsList() {
        Item item = read("""
                material: DIAMOND_SWORD
                enchantments:
                  - "SHARPNESS:5"
                  - "KNOCKBACK|2"
                  - "FIRE_ASPECT"
                """);

        assertEquals(5, item.enchantments().get("SHARPNESS"));
        assertEquals(2, item.enchantments().get("KNOCKBACK"));
        assertEquals(1, item.enchantments().get("FIRE_ASPECT"), "a bare name is level one");
    }

    @Test
    @DisplayName("an enchantment with an unreadable level is reported, not guessed")
    void badEnchantmentLevel() {
        Item item = read("""
                material: DIAMOND_SWORD
                enchantments:
                  SHARPNESS: "lots"
                """);

        assertTrue(item.enchantments().isEmpty());
        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("SHARPNESS"), problems::toString);
    }

    @Test
    @DisplayName("flags are read, which commons never did")
    void flags() {
        // ExyliaSpecialsV3, tools/seed_planter.yml. Fifteen files have asked
        // for this for years and been ignored, because nothing parsed the key.
        Item item = read("""
                material: NETHERITE_HOE
                flags:
                  - HIDE_ENCHANTS
                  - HIDE_ATTRIBUTES
                """);

        assertEquals(List.of("HIDE_ENCHANTS", "HIDE_ATTRIBUTES"), item.appearance().flags());
    }

    @Test
    @DisplayName("hide-attributes can be turned off, which commons made impossible")
    void hideAttributesCanBeDisabled() {
        // Commons wrote getBoolean(a, true) || getBoolean(b, true): true no
        // matter what the file said.
        assertFalse(read("material: STONE\nhide-attributes: false\n")
                .appearance().hideAttributes());
        assertFalse(read("material: STONE\nhide_attributes: false\n")
                .appearance().hideAttributes());
        assertTrue(read("material: STONE\nhide-attributes: true\n")
                .appearance().hideAttributes());
    }

    @Test
    @DisplayName("both spellings of a flag work on their own")
    void eitherSpelling() {
        assertEquals("true", read("material: STONE\nglow: true\n").appearance().glow());
        assertEquals("true", read("material: STONE\nglowing: true\n").appearance().glow());
        assertTrue(read("material: STONE\nhide_tooltip: true\n").appearance().hideTooltip());
        assertTrue(read("material: STONE\nhide-tooltip: true\n").appearance().hideTooltip());
    }

    @Test
    @DisplayName("a head written into material is a head")
    void headInMaterial() {
        // ExyliaClans writes 276 of these.
        Item item = read("""
                material: 'basehead-eyJ0ZXh0dXJlcyI6e30='
                name: "{accent}Leaderboard"
                """);

        assertInstanceOf(Source.OfHead.class, item.source());
        assertFalse(item.isDynamic());
    }

    @Test
    @DisplayName("a head named by a placeholder waits for a viewer")
    void templatedHead() {
        Item item = read("material: \"playerhead-%player_name%\"\n");

        assertInstanceOf(Source.OfHeadTemplate.class, item.source());
        assertTrue(item.isDynamic());
    }

    @Test
    @DisplayName("an amount can be a number or a placeholder")
    void amount() {
        assertEquals("3", read("material: STONE\namount: 3\n").amount());
        assertEquals("%owned%", read("material: STONE\namount: \"%owned%\"\n").amount());
        assertEquals("1", read("material: STONE\n").amount());
    }

    @Test
    @DisplayName("a lore line split with <nl> becomes several lines")
    void loreNewlines() {
        Item item = read("""
                material: STONE
                lore:
                  - "first<nl>second"
                  - "third"
                """);

        assertEquals(List.of("first", "second", "third"), item.lore());
    }

    @Test
    @DisplayName("an item with nothing unusual shares one appearance, which hides the vanilla block")
    void plainItemsShareTheirParts() {
        Item item = read("material: STONE\nname: \"Rock\"\n");
        Item another = read("material: DIRT\nname: \"Soil\"\n");

        // Not Appearance.PLAIN: a file that says nothing means what commons
        // did, which is a tooltip with nothing vanilla wrote in it.
        assertTrue(item.appearance().hideAttributes());
        assertSame(item.appearance(), another.appearance());
        assertSame(Traits.NONE, item.traits());
    }

    @Test
    @DisplayName("a potion reads its base type, and upgraded means the strong one")
    void potion() {
        // ExyliaSpecialsV3, items/refill.yml. Commons carried "upgraded" along
        // and then dropped it, so a kit configured for Instant Health II handed
        // out Instant Health I.
        Item item = read("""
                material: "POTION"
                name: '&#ff4d4dInstant Health II'
                potion:
                  base_type: "HEALING"
                  upgraded: true
                """);

        Potion potion = item.traits().potion();
        assertNotNull(potion);
        assertEquals("STRONG_HEALING", potion.base());
    }

    @Test
    @DisplayName("extended means the long variant")
    void extendedPotion() {
        Item item = read("""
                material: POTION
                potion:
                  base_type: "SWIFTNESS"
                  extended: true
                """);

        assertEquals("LONG_SWIFTNESS", item.traits().potion().base());
    }

    @Test
    @DisplayName("custom potion effects keep their amplifier and duration as written")
    void potionEffects() {
        Item item = read("""
                material: POTION
                potion:
                  custom_effects:
                    - type: SPEED
                      amplifier: "%level%"
                      duration: 600
                """);

        Potion.Effect effect = item.traits().potion().effects().getFirst();
        assertEquals("SPEED", effect.type());
        assertEquals("%level%", effect.amplifier(), "a placeholder survives to render time");
        assertEquals("600", effect.duration());
    }

    @Test
    @DisplayName("an armour trim keeps its placeholders")
    void trim() {
        // ExyliaArmorTrims, menus/main.yml.
        Item item = read("""
                material: DIAMOND_HELMET
                armor_trim:
                  pattern: "%helmet_trim_pattern%"
                  material: "%helmet_trim_material%"
                """);

        Trim trim = item.traits().trim();
        assertNotNull(trim);
        assertEquals("%helmet_trim_pattern%", trim.pattern());
        assertTrue(item.isDynamic());
    }

    @Test
    @DisplayName("leather reads its dye, hex or named, and a placeholder makes it dynamic")
    void dye() {
        // ExyliaStaff, modules/staffmode/hotbar.yml.
        Item item = read("""
                material: LEATHER_CHESTPLATE
                color: '#3FC7F4'
                armor_trim:
                  pattern: silence
                  material: diamond
                """);

        assertEquals("#3FC7F4", item.traits().dye());
        assertFalse(item.isDynamic(), "a written colour is the same for every viewer");
        assertEquals("aqua", read("""
                material: LEATHER_BOOTS
                leather-color: aqua
                """).traits().dye());
        assertTrue(read("""
                material: LEATHER_BOOTS
                color: "%clan_color%"
                """).isDynamic(), "a per-viewer colour cannot be cached");
    }

    @Test
    @DisplayName("half a trim is no trim")
    void halfATrimIsDropped() {
        Item item = read("""
                material: DIAMOND_HELMET
                armor_trim:
                  pattern: sentry
                """);

        assertNull(item.traits().trim(), "a trim with no material renders nothing anyway");
    }

    @Test
    @DisplayName("a banner reads its written patterns")
    void bannerPatterns() {
        // ExyliaShields, shields.yml.
        Item item = read("""
                material: WHITE_BANNER
                banner_patterns:
                  base_color: WHITE
                  patterns:
                    - pattern: STRIPE_BOTTOM
                      color: LIGHT_GRAY
                """);

        Banner banner = item.traits().banner();
        assertNotNull(banner);
        assertEquals("WHITE", banner.baseColor());
        assertEquals(1, banner.patterns().size());
        assertEquals("STRIPE_BOTTOM", banner.patterns().getFirst().pattern());
    }

    @Test
    @DisplayName("a saved banner design decodes to the same thing it was written from")
    void bannerRoundTrip() {
        Banner original = new Banner("white",
                List.of(new Banner.Layer("stripe_top", "red"),
                        new Banner.Layer("border", "black")));

        Banner decoded = Items.banner(Items.encode(original));

        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("a banner design that will not decode is reported, not thrown")
    void brokenBannerDesign() {
        Item item = read("material: SHIELD\nbanner_design: \"not base64 at all\"\n");
        assertNull(item.traits().banner());
        assertEquals(1, problems.size());
    }

    @Test
    @DisplayName("a banner design given as a placeholder is kept to resolve later")
    void bannerDesignTemplate() {
        // ExyliaShields, menus/design_editor.yml: every row previews the
        // player's current design plus the layer that row would add, so the
        // whole design is a value computed per row.
        Item item = read("material: SHIELD\nbanner_design: \"%pattern_preview%\"\n");
        Banner banner = item.traits().banner();
        assertNotNull(banner);
        assertEquals("%pattern_preview%", banner.template());
        assertTrue(banner.patterns().isEmpty());
        assertEquals(0, problems.size(),
                "a design that has not arrived yet is not a broken one");
    }

    @Test
    @DisplayName("an item whose banner is a placeholder is dynamic")
    void bannerTemplateIsDynamic() {
        // Without this the cache keeps the first viewer's design and hands it
        // to everyone, so twenty different shields draw the same picture.
        Item item = read("material: SHIELD\nbanner_design: \"%pattern_preview%\"\n");
        assertTrue(item.isDynamic());
    }

    @Test
    @DisplayName("an item whose banner design is spelled out is not dynamic")
    void spelledOutBannerIsNotDynamic() {
        Item item = read("""
                material: WHITE_BANNER
                banner_patterns:
                  base_color: WHITE
                  patterns:
                    - pattern: STRIPE_BOTTOM
                      color: LIGHT_GRAY
                """);
        assertFalse(item.isDynamic(),
                "a design known at load time is shared, not rebuilt per viewer");
    }

    @Test
    @DisplayName("a banner design with a placeholder in one field is dynamic")
    void bannerFieldPlaceholderIsDynamic() {
        Item item = read("""
                material: WHITE_BANNER
                banner_patterns:
                  base_color: "%base_colour%"
                  patterns:
                    - pattern: STRIPE_BOTTOM
                      color: LIGHT_GRAY
                """);
        assertTrue(item.isDynamic());
    }

    @Test
    @DisplayName("a saved design still decodes when it is not a placeholder")
    void bannerDesignWithoutTemplate() {
        Banner original = new Banner("white", List.of(new Banner.Layer("border", "black")));
        Item item = read("material: SHIELD\nbanner_design: \"" + Items.encode(original) + "\"\n");
        Banner banner = item.traits().banner();
        assertNotNull(banner);
        assertNull(banner.template(), "a design that is already here is not a promise of one");
        assertEquals(original, banner);
    }

    @Test
    @DisplayName("a consumable reads its timing and sound")
    void consumable() {
        // ExyliaSpecialsV3, items/golden_head.yml.
        Item item = read("""
                material: PLAYER_HEAD
                force-consumable: true
                consumable-time: 1.0
                consumable-nutrition: 6
                consumable-saturation: 14.4
                consumable-sound: ITEM_HONEY_BOTTLE_DRINK
                """);

        Consumable consumable = item.traits().consumable();
        assertNotNull(consumable);
        assertEquals(1.0f, consumable.seconds());
        assertEquals(6, consumable.nutrition());
        assertEquals(14.4f, consumable.saturation(), 0.001);
        assertEquals("ITEM_HONEY_BOTTLE_DRINK", consumable.sound());
    }

    @Test
    @DisplayName("without force-consumable there is nothing to eat")
    void consumableNeedsAsking() {
        assertNull(read("material: BREAD\nconsumable-time: 2.0\n").traits().consumable());
    }

    @Test
    @DisplayName("a malformed attribute is reported and the good ones survive")
    void attributes() {
        Item item = read("""
                material: DIAMOND_SWORD
                attributes:
                  - "attack_damage|8"
                  - "broken line"
                  - "movement_speed|0.05"
                """);

        assertEquals(2, item.traits().modifiers().size());
        assertEquals(1, problems.size(), problems::toString);
    }

    @Test
    @DisplayName("stored values are read from a section or a list")
    void storedValues() {
        assertEquals("special", read("""
                material: STONE
                nbt:
                  kind: special
                  uses: 3
                """).traits().data().get("kind"));

        assertEquals("3", read("""
                material: STONE
                nbt:
                  - "uses:3"
                """).traits().data().get("uses"));
    }

    @Test
    @DisplayName("an item model and a tooltip style are read")
    void modelAndStyle() {
        Item item = read("""
                material: STONE
                item_model: "exylia:ruby"
                tooltip_style: "exylia:fancy"
                """);

        assertEquals("exylia:ruby", item.appearance().model());
        assertEquals("exylia:fancy", item.appearance().tooltipStyle());
    }

    @Test
    @DisplayName("an empty section is a stone block, not a crash")
    void emptySection() {
        Item item = read("name: \"Nothing\"\n");

        assertEquals("STONE", assertInstanceOf(Source.OfMaterial.class, item.source()).raw());
    }

    @Test
    @DisplayName("glow survives as a placeholder and decides itself per viewer")
    void glowFollowsAPlaceholder() {
        Item item = read("material: STONE\nglow: \"%kit_enabled%\"\n");
        assertEquals("%kit_enabled%", item.appearance().glow());
        // A slot whose shimmer depends on state has to be redrawn, or it is
        // painted once and never follows the toggle it is reporting.
        assertTrue(item.isDynamic());
    }

    @Test
    @DisplayName("glow: false reads back as nothing written")
    void glowOffIsAbsent() {
        assertNull(read("material: STONE\nglow: false\n").appearance().glow());
    }
}
