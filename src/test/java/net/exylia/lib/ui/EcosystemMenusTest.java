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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every menu the whole ecosystem ships, read unchanged.
 *
 * <p>Two hundred and sixty-nine files from twelve plugins, copied rather
 * than written for the test. {@code RealMenusTest} covers one plugin in depth;
 * this covers the breadth, and the two failures it is here to catch are the
 * ones that pass every hand-written example: a key everybody uses that nothing
 * reads, and a shape only one plugin happens to write.
 */
class EcosystemMenusTest {

    private static final Pattern ACTION = Pattern.compile("([a-z0-9_.-]+:[a-z0-9_.-]+)");

    private Plugin plugin;
    private PluginActions actions;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Actions.releaseAll();
        plugin = FakeServer.newPlugin("Ecosystem", null);
        actions = Actions.of(plugin, "ecosystem");
    }

    @AfterEach
    void tearDown() {
        Actions.releaseAll();
        FakeServer.reset();
    }

    private static Path menus() throws URISyntaxException {
        return Path.of(EcosystemMenusTest.class.getResource("/ecosystem-menus").toURI());
    }

    @Test
    @DisplayName("every menu the ecosystem ships loads, and its settings are read")
    void everythingLoads() throws Exception {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(menus())) {
            files = walk.filter(path -> path.toString().endsWith(".yml")).sorted().toList();
        }
        assertTrue(files.size() >= 260, "expected the ecosystem's menus, found " + files.size());
        registerEveryReferencedAction(files);

        List<String> failures = new ArrayList<>();
        List<String> deadButtons = new ArrayList<>();
        int paginated = 0;
        int multiSection = 0;
        int animated = 0;
        int refreshing = 0;
        int sounded = 0;
        int emptyListFiller = 0;
        int conditional = 0;
        int items = 0;

        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                YamlConfiguration config = new YamlConfiguration();
                config.loadFromString(Files.readString(file));
                UiDefinition menu = MenuLoader.load("eco:" + name, config,
                        actions::template, UiSounds.DEFAULTS,
                        (where, problem) -> deadButtons.add(name + " " + where + ": " + problem));

                items += menu.items().size();
                if (menu.isPaginated()) {
                    paginated++;
                }
                if (menu.sections().size() > 1) {
                    multiSection++;
                }
                if (menu.openAnimation() != null) {
                    animated++;
                }
                if (menu.refresh().mode() != UiRefresh.Mode.DISABLED) {
                    refreshing++;
                }
                if (menu.sounds().open() != null
                        && !menu.sounds().open().equals(UiSounds.DEFAULTS.open())) {
                    sounded++;
                }
                if (menu.fillers().pagination() != null) {
                    emptyListFiller++;
                }
                for (UiItem item : menu.items().values()) {
                    if (item.condition() != null) {
                        conditional++;
                    }
                }
            } catch (Exception | AssertionError failure) {
                failures.add(name + ": " + failure);
            }
        }

        if (!failures.isEmpty()) {
            fail("menus that did not load:\n  " + String.join("\n  ", failures));
        }

        // Every one of these is a key the files write and the parser has to
        // read. A count of zero means it is being silently ignored, which is
        // exactly how flags went unparsed in commons for years.
        assertTrue(paginated >= 145, "menus with a list: " + paginated);
        assertTrue(multiSection >= 12, "menus with several lists: " + multiSection);
        assertTrue(animated >= 62, "menus with an open animation: " + animated);
        assertTrue(refreshing >= 122, "menus that redraw themselves: " + refreshing);
        assertTrue(sounded >= 255, "menus with their own open sound: " + sounded);
        assertTrue(emptyListFiller >= 127, "menus that say why a list is empty: " + emptyListFiller);
        assertTrue(conditional >= 20, "slots shown conditionally: " + conditional);
        assertTrue(items >= 1190, "slots across the ecosystem: " + items);

        // Two buttons in the wild really are broken: actions that no plugin
        // registers under the namespace the file writes. The bound is tight so
        // a change that breaks fifty more cannot pass quietly.
        assertTrue(deadButtons.size() <= 5,
                "more dead buttons than the known ones (" + deadButtons.size() + "):\n  "
                        + String.join("\n  ", deadButtons.subList(0, Math.min(20, deadButtons.size()))));
    }

    @Test
    @DisplayName("every template name the ecosystem invented is read")
    void everyTemplateName() throws Exception {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(menus())) {
            files = walk.filter(path -> path.toString().endsWith(".yml")).toList();
        }
        registerEveryReferencedAction(files);

        Set<String> names = new HashSet<>();
        for (Path file : files) {
            YamlConfiguration config = new YamlConfiguration();
            try {
                config.loadFromString(Files.readString(file));
            } catch (Exception unreadable) {
                continue;
            }
            UiDefinition menu = MenuLoader.load("eco:" + file.getFileName(), config,
                    actions::template, UiSounds.DEFAULTS, (where, problem) -> { });
            for (UiSection section : menu.sections().values()) {
                names.addAll(section.templates().keySet());
            }
        }

        // Read by shape rather than from a list, which is the only way this
        // holds: a plugin is free to invent another one tomorrow.
        assertTrue(names.contains("selected"), "found: " + names);
        assertTrue(names.contains("not_selected"), "found: " + names);
        assertTrue(names.contains(UiSection.DEFAULT), "found: " + names);
        assertTrue(names.size() >= 8, "expected the invented names, found " + names);
    }

    /** Registers whatever the files call, so this measures parsing. */
    private void registerEveryReferencedAction(List<Path> files) throws IOException {
        Set<String> ids = new HashSet<>();
        for (Path file : files) {
            Matcher matcher = ACTION.matcher(Files.readString(file));
            while (matcher.find()) {
                ids.add(matcher.group(1));
            }
        }
        // Registered under the namespace each id actually names, since a menu
        // written for ExyliaArmorTrims calls armortrim:select_piece and nothing
        // else will answer to that.
        for (String qualified : ids) {
            int colon = qualified.indexOf(':');
            String namespace = qualified.substring(0, colon);
            String id = qualified.substring(colon + 1);
            try {
                Actions.of(plugin, namespace)
                        .registerSync(id, (context, args) -> ActionResult.success());
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // Not a usable id, or already registered.
            }
        }
        for (String builtIn : List.of("previous_page", "next_page", "back", "close", "refresh")) {
            try {
                actions.registerSync(builtIn, (context, args) -> ActionResult.success());
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // Already registered.
            }
        }
    }
}
