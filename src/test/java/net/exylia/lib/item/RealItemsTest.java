package net.exylia.lib.item;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every item ExyliaSpecialsV3 and ExyliaShields ship, read unchanged.
 *
 * <p>Seventy-seven real files, copied from the plugins rather than written for
 * the test. A parser that handles the examples somebody thought to write down
 * is not the same as one that handles what is deployed, and the difference only
 * shows up on a live server.
 *
 * <p>These are also the files that are <em>not</em> menus, which is the whole
 * argument for the module existing: special items, tools and shields are the
 * same block of YAML as a menu icon, and were being parsed by a different copy
 * of the same code.
 */
class RealItemsTest {

    private static Path items() throws URISyntaxException {
        return Path.of(RealItemsTest.class.getResource("/real-items").toURI());
    }

    @Test
    @DisplayName("every shipped item file is read without being edited")
    void everyRealItemLoads() throws Exception {
        Path root = items();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(path -> path.toString().endsWith(".yml")).sorted().toList();
        }
        assertTrue(files.size() >= 70, "expected the real item files, found " + files.size());

        List<String> failures = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int named = 0;
        int enchanted = 0;
        int withTraits = 0;

        for (Path file : files) {
            String name = root.relativize(file).toString();
            try {
                YamlConfiguration config = new YamlConfiguration();
                config.loadFromString(Files.readString(file));
                Item item = Items.parse(config,
                        (where, problem) -> problems.add(name + " " + where + ": " + problem));
                if (item.name() != null) {
                    named++;
                }
                if (!item.enchantments().isEmpty()) {
                    enchanted++;
                }
                if (!item.traits().isEmpty()) {
                    withTraits++;
                }
            } catch (Exception | AssertionError failure) {
                failures.add(name + ": " + failure);
            }
        }

        if (!failures.isEmpty()) {
            fail("item files that did not load:\n  " + String.join("\n  ", failures));
        }
        if (!problems.isEmpty()) {
            fail("parts that could not be read:\n  " + String.join("\n  ", problems));
        }
        assertTrue(named >= 60, "expected most items to be named, found " + named);
        assertTrue(enchanted >= 5, "expected the tools to be enchanted, found " + enchanted);
        assertTrue(withTraits >= 1, "expected the consumable among them, found " + withTraits);
    }

    @Test
    @DisplayName("an item nested inside another plugin's config is read the same way")
    void nestedItem() throws Exception {
        // ExyliaSpecialsV3 describes the potion a refill kit hands out under
        // its own action config. It is an item like any other, which is the
        // point: the reader takes a section, not a file.
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(Files.readString(items().resolve("specials/refill.yml")));

        ConfigurationSection nested =
                config.getConfigurationSection("action-config.refill-item");
        assertTrue(nested != null, "refill.yml should describe the potion it gives");

        Item potion = Items.parse(nested);

        assertTrue(potion.traits().potion() != null, "the refill item is a potion");
        // Configured as HEALING with upgraded: true, which is Instant Health II.
        assertTrue("STRONG_HEALING".equals(potion.traits().potion().base()),
                "expected the strong variant, found " + potion.traits().potion().base());
    }

    @Test
    @DisplayName("the shield patterns file reads every design it ships")
    void shieldDesigns() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(Files.readString(items().resolve("shields.yml")));

        ConfigurationSection patterns = config.getConfigurationSection("shield_patterns");
        assertTrue(patterns != null, "shields.yml should list its patterns");

        List<String> problems = new ArrayList<>();
        int designs = 0;
        for (String key : patterns.getKeys(false)) {
            ConfigurationSection item = patterns.getConfigurationSection(key + ".item");
            if (item == null) {
                continue;
            }
            Banner banner = Items.parse(item,
                    (where, problem) -> problems.add(key + " " + where + ": " + problem))
                    .traits().banner();
            if (banner != null) {
                designs++;
            }
        }

        if (!problems.isEmpty()) {
            fail("shield designs that could not be read:\n  " + String.join("\n  ", problems));
        }
        assertTrue(designs >= 20, "expected the shipped shield designs, found " + designs);
    }

    @Test
    @DisplayName("the tools that ask to hide their enchantments now say so")
    void toolsAskToHideEnchantments() throws Exception {
        Path tools = items().resolve("tools");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(tools)) {
            files = walk.filter(path -> path.toString().endsWith(".yml")).toList();
        }

        int hiding = 0;
        for (Path file : files) {
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(Files.readString(file));
            if (Items.parse(config).appearance().flags().contains("HIDE_ENCHANTS")) {
                hiding++;
            }
        }

        // These files have carried the key for years with nothing reading it.
        assertTrue(hiding >= 5, "expected the tools to hide their enchantments, found " + hiding);
    }
}
