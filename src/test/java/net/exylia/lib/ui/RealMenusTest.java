package net.exylia.lib.ui;

import net.exylia.lib.FakeServer;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.ui.internal.MenuLoader;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every menu ExyliaPracticeCore ships, loaded unchanged.
 *
 * <p>Sixty real files, copied from the plugin rather than written for the
 * test. A parser that handles the examples somebody thought to write down is
 * not the same as one that handles what is actually deployed, and the
 * difference only shows up on a live server.
 */
class RealMenusTest {

    /** Finds the action ids the menus reference, so they can be registered. */
    private static final Pattern ACTION = Pattern.compile("[a-z_]+:([a-z_0-9]+)");

    private PluginActions actions;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Actions.releaseAll();
        Plugin plugin = FakeServer.newPlugin("Practice", null);
        actions = Actions.of(plugin, "practice");
    }

    @AfterEach
    void tearDown() {
        Actions.releaseAll();
        FakeServer.reset();
    }

    private static Path menus() throws URISyntaxException {
        return Path.of(RealMenusTest.class.getResource("/practice-menus").toURI());
    }

    @Test
    @DisplayName("every menu ExyliaPracticeCore ships loads without being edited")
    void everyRealMenuLoads() throws Exception {
        Path root = menus();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(path -> path.toString().endsWith(".yml")).sorted().toList();
        }
        assertTrue(files.size() >= 50, "expected the real menu files, found " + files.size());

        registerEveryReferencedAction(files);

        List<String> failures = new ArrayList<>();
        List<String> deadButtons = new ArrayList<>();
        int paginated = 0;
        int multiSection = 0;
        int withOpenSound = 0;
        int withRefresh = 0;
        int withPaginationFiller = 0;
        int items = 0;
        for (Path file : files) {
            String name = root.relativize(file).toString();
            try {
                YamlConfiguration config = new YamlConfiguration();
                config.loadFromString(Files.readString(file));
                UiDefinition menu = MenuLoader.load("practice:" + name, config,
                        actions::template, UiSounds.DEFAULTS,
                        (where, problem) -> deadButtons.add(name + " " + where + ": " + problem));
                items += menu.items().size();
                if (menu.isPaginated()) {
                    paginated++;
                }
                if (menu.sections().size() > 1) {
                    multiSection++;
                }
                if (menu.fillers().pagination() != null) {
                    withPaginationFiller++;
                }
                if (menu.refresh().mode() != net.exylia.lib.ui.UiRefresh.Mode.DISABLED) {
                    withRefresh++;
                }
                if (menu.sounds().open() != null
                        && !menu.sounds().open().equals(UiSounds.DEFAULTS.open())) {
                    withOpenSound++;
                }
            } catch (Exception | AssertionError failure) {
                failures.add(name + ": " + failure);
            }
        }

        if (!failures.isEmpty()) {
            fail("menus that did not load:\n  " + String.join("\n  ", failures));
        }
        // Two of these files really do contain broken buttons today: one mixes
        // the click and command syntaxes, the other calls an action that does
        // not exist. Both fail silently on the live server. Loading the menu
        // anyway is right; saying nothing about it was not.
        assertTrue(deadButtons.size() <= 4,
                "more dead buttons than the two known ones:\n  "
                        + String.join("\n  ", deadButtons));
        assertTrue(paginated >= 5, "expected paginated menus among them, found " + paginated);
        assertTrue(multiSection >= 4,
                "expected the menus with several lists on one screen, found " + multiSection);
        // Every one of these files writes open_sounds. Reading only a "sounds"
        // block would have made the whole ecosystem silent and passed anyway.
        assertTrue(withOpenSound >= 40,
                "expected the menus to declare their own open sound, found " + withOpenSound);
        assertTrue(withRefresh >= 20,
                "expected the menus that ask to redraw themselves, found " + withRefresh);
        assertTrue(withPaginationFiller >= 15,
                "expected the menus that say why a list is empty, found " + withPaginationFiller);
        assertTrue(items >= 200, "expected a few hundred items, found " + items);
    }

    @Test
    @DisplayName("a deployed menu puts a working button under a page arrow")
    void buttonBeneathAPageArrow() throws Exception {
        // spectator.yml really does put "toggle spectators" at slot 45, where
        // the previous-page arrow also lives. A hidden arrow that painted
        // glass there would take away a button that works, on every menu of
        // spectators short enough to fit one page — which is all of them.
        Path file = menus().resolve("player/spectator.yml");
        registerEveryReferencedAction(List.of(file));
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(Files.readString(file));
        UiDefinition menu = MenuLoader.load("practice:spectator", config,
                actions::template, UiSounds.DEFAULTS);

        UiSection list = menu.section();
        assertNotNull(list);
        assertEquals(45, list.previous().slot(), "the arrow shares the slot with an item");
        assertSame(menu.items().get(45), menu.beneath(45),
                "the fixed button comes back rather than being covered by filler");
        assertSame(menu.fillers().global(), menu.beneath(53),
                "a slot with no item of its own falls through to the background");
    }

    /**
     * Registers whatever the files call, so the test measures parsing rather
     * than which actions this test happened to think of.
     */
    private void registerEveryReferencedAction(List<Path> files) throws IOException {
        Set<String> ids = new HashSet<>();
        for (Path file : files) {
            Matcher matcher = ACTION.matcher(Files.readString(file));
            while (matcher.find()) {
                ids.add(matcher.group(1));
            }
        }
        // The built-ins a menu can use without any plugin registering them.
        ids.addAll(List.of("previous_page", "next_page", "back", "close"));
        for (String id : ids) {
            try {
                actions.registerSync(id, (context, args) -> ActionResult.success());
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // Not a usable id, or already registered.
            }
        }
    }
}
