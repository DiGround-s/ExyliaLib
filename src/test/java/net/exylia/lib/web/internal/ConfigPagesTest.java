package net.exylia.lib.web.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.config.Comment;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.config.Menu;
import net.exylia.lib.ui.UiDefinition;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a settings file becomes cards and pages, and how a screen is cut to what it shows. */
class ConfigPagesTest {

    record Limits(int maxPlayers, @Comment("Seconds between two uses. Long text after.") int cooldown) {
        Limits() {
            this(10, 5);
        }
    }

    record Plain(boolean enabled, String prefix, Limits limits, Map<String, Integer> weights) {
        Plain() {
            this(true, "x", new Limits(), Map.of("a", 1));
        }
    }

    record Settings(
            @Menu.Page("fees") @Menu.Format(Menu.Style.PERCENT) @Menu.Range(min = 0, max = 100, step = 5)
            double listingFee,
            @Menu.Page("limits") @Menu.Range(min = 0, max = 1000, none = -1) @Menu.Format(Menu.Style.MONEY)
            double maxPrice) {
        Settings() {
            this(10, -1);
        }
    }

    @Menu.Pages({@Menu.Card(id = "fees", label = "Fees & tax", icon = "GOLD_NUGGET", about = "What a sale costs."),
            @Menu.Card(id = "limits", icon = "CHEST")})
    record Annotated(@Menu.Label("Turned on") @Menu.Icon("BEACON") boolean enabled,
                     @Menu.Hidden String secret,
                     Settings settings) {
        Annotated() {
            this(true, "s", new Settings());
        }
    }

    record Rich(@Menu.Format(Menu.Style.MULTIPLIER) @Menu.Range(preset = Menu.Preset.MULTIPLIER) double fortune,
                @Menu.Format(Menu.Style.FRACTION) @Menu.Range(preset = Menu.Preset.FRACTION) double share,
                @Menu.Choices(value = {"survival", "adventure"}, labels = {"Survival", "Adventure"}) String gamemode,
                @Menu.Language String language,
                @Menu.Material String helmet,
                @Menu.Sound String click,
                @Menu.Unit("min") int window,
                @Menu.Range(preset = Menu.Preset.HUNDRED, max = 50) @Menu.AtMost("critical") int warning,
                @Menu.Range(preset = Menu.Preset.HUNDRED) @Menu.AtLeast("warning") int critical) {
        Rich() {
            this(1.25, 0.25, "survival", "default", "diamond_helmet", "ENTITY_EXPERIENCE_ORB_PICKUP|0.5|1.2", 90, 30, 60);
        }
    }

    @TempDir
    Path folder;

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("ExyliaMines", folder.toFile());
    }

    @AfterEach
    void tearDown() {
        Configs.release(plugin);
    }

    @Test
    void buttonsAreCentredTheWayTheAdminScreensAre() {
        assertEquals(new ConfigLayout.Fit(List.of(11, 13, 15), 36, false), ConfigLayout.fit(3, 1));
        assertEquals(new ConfigLayout.Fit(List.of(10, 12, 14, 16), 36, false), ConfigLayout.fit(4, 1));
        assertEquals(List.of(10, 12, 14, 16, 19, 21, 23, 25), ConfigLayout.fit(8, 1).slots(), "8 is two rows of 4");
        assertEquals(45, ConfigLayout.fit(8, 1).size());
        assertEquals(new ConfigLayout.Fit(List.of(21, 23), 45, false), ConfigLayout.fit(2, 2), "a hub's cards on row 3");
        ConfigLayout.Fit many = ConfigLayout.fit(29, 1);
        assertTrue(many.paginated());
        assertEquals(54, many.size());
        assertEquals(28, many.slots().size());
        assertFalse(ConfigLayout.fit(21, 1).paginated(), "three rows still fit");
    }

    @Test
    void aScreenIsCutToItsRowsWithItsControlsOnTheNewBottomRow() {
        List<String> messages = net.exylia.lib.debug.DebugCapture.start();
        try {
            Plugin library = FakeServer.newPlugin("ExyliaLib", folder.resolve("lib").toFile());
            FakeServer.packageMainResources("lang");
            ConfigMenu.anchor(FakeServer.class);
            ConfigMenu.init(library, "exylialib");
            var menus = net.exylia.lib.ui.Menus.of(library, "exylialib");
            ConfigMenu.load(menus);
            UiDefinition base = menus.definition(ConfigMenu.FIELDS).orElseThrow();
            UiDefinition cut = ConfigLayout.apply(base, ConfigLayout.fit(3, 1));
            assertEquals(36, cut.size());
            assertTrue(cut.items().containsKey(31), "back moves from 49 to 31");
            assertTrue(cut.items().containsKey(4), "the header stays");
            assertEquals(List.of(11, 13, 15), cut.section().slots());
            assertFalse(cut.title().contains("%current_page%"), "one page, no counter");
            assertTrue(ConfigLayout.apply(base, ConfigLayout.fit(40, 1)).title().contains("%current_page%"));
        } finally {
            ConfigMenu.release();
            net.exylia.lib.ui.Menus.releaseAll();
            net.exylia.lib.action.Actions.releaseAll();
            net.exylia.lib.debug.DebugCapture.stop();
        }
    }

    @Test
    void withoutAnnotationsTheFileIsGroupedTheWayItIsWritten() {
        ConfigFile<Plain> file = Configs.define(plugin, "plain", Plain.class).load();
        List<ConfigPages.Page> cards = ConfigPages.cards(file.schema());
        assertEquals(List.of(ConfigPages.GENERAL, "s:limits", "e:weights"), cards.stream().map(ConfigPages.Page::token).toList());
        assertEquals(List.of("enabled", "prefix"), cards.get(0).fields().stream().map(ConfigFields.Field::key).toList());
        assertEquals(List.of("limits.max-players", "limits.cooldown"),
                cards.get(1).fields().stream().map(ConfigFields.Field::key).toList());
        assertEquals("weights", cards.get(2).entries().key());
        assertNull(ConfigPages.holder(file.schema(), "limits"), "a root section is a card, back goes to the hub");
        assertEquals("s:limits", ConfigPages.holder(file.schema(), "limits.cooldown").token());

        ConfigFields.Field cooldown = ConfigFields.field(file.schema(), "limits.cooldown");
        assertEquals("HOPPER", ConfigFields.icon(ConfigFields.field(file.schema(), "limits.max-players"), 10));
        assertEquals("CLOCK", ConfigFields.icon(cooldown, 5), "chosen from the key's name");
        assertTrue(ConfigFields.about(cooldown).contains("Seconds between two uses."));
        assertFalse(ConfigFields.about(cooldown).contains("Long text"), "only the first sentence");
    }

    @Test
    void annotationsMakePagesNamesIconsFormatsAndRanges() {
        ConfigFile<Annotated> file = Configs.define(plugin, "annotated", Annotated.class).load();
        List<ConfigPages.Page> cards = ConfigPages.cards(file.schema());
        assertEquals(List.of(ConfigPages.GENERAL, "@fees", "@limits"), cards.stream().map(ConfigPages.Page::token).toList(),
                "the settings section went entirely to pages, so it has no card of its own");
        ConfigPages.Page fees = cards.get(1);
        assertEquals("FEES & TAX", fees.label());
        assertEquals("GOLD_NUGGET", fees.icon());
        assertTrue(fees.about().contains("What a sale costs."));
        assertEquals("CHEST", cards.get(2).icon());
        assertEquals("LIMITS", cards.get(2).label(), "named after its id");

        ConfigFields.Field enabled = ConfigFields.field(file.schema(), "enabled");
        assertEquals("TURNED ON", ConfigFields.label(enabled));
        assertEquals(List.of("enabled"), cards.get(0).fields().stream().map(ConfigFields.Field::key).toList(), "hidden left out");

        ConfigFields.Field fee = ConfigFields.field(file.schema(), "settings.listing-fee");
        assertEquals("{info}10%", ConfigFields.display(fee, 10.0));
        assertEquals(15.0, ConfigFields.step(fee, 10.0, 1, false));
        assertEquals(100.0, ConfigFields.step(fee, 95.0, 1, true), "a click stays inside the range");
        assertFalse(ConfigFields.allowed(fee, 120));

        ConfigFields.Field price = ConfigFields.field(file.schema(), "settings.max-price");
        assertTrue(ConfigFields.display(price, -1.0).contains("None"));
        assertEquals(0.0, ConfigFields.step(price, -1.0, 1, false), "stepping off none starts at the edge");
        assertEquals(-1.0, ConfigFields.step(price, 0.0, -1, false), "stepping below the range is none");
        assertTrue(ConfigFields.allowed(price, -1));
    }

    @Test
    void richerControlsFormatCycleAndKeepTheOrder() {
        ConfigFile<Rich> file = Configs.define(plugin, "rich", Rich.class).load();
        var schema = file.schema();
        ConfigFields.Field fortune = ConfigFields.field(schema, "fortune");
        assertEquals("{info}×1.25", ConfigFields.display(fortune, 1.25));
        assertEquals(1.35, ConfigFields.step(fortune, 1.25, 1, false), "the preset's step");
        assertFalse(ConfigFields.allowed(fortune, 101), "the preset's max");

        ConfigFields.Field share = ConfigFields.field(schema, "share");
        assertEquals("{info}25%", ConfigFields.display(share, 0.25));
        assertEquals(0.3, ConfigFields.step(share, 0.25, 1, false));

        ConfigFields.Field gamemode = ConfigFields.field(schema, "gamemode");
        assertEquals(ConfigFields.Kind.CHOICE, gamemode.kind());
        assertEquals("adventure", ConfigFields.step(gamemode, "survival", 1, false));
        assertEquals("survival", ConfigFields.step(gamemode, "adventure", 1, false), "wraps");
        assertEquals("survival", ConfigFields.step(gamemode, "creative", 1, false), "a value outside the set moves to the first");
        assertEquals("{highlight}Adventure", ConfigFields.display(gamemode, "adventure"));

        ConfigFields.Field language = ConfigFields.field(schema, "language");
        assertEquals("en", ConfigFields.step(language, "default", 1, false));
        assertEquals("{highlight}Español", ConfigFields.display(language, "es"));

        assertEquals(ConfigFields.Kind.MATERIAL, ConfigFields.field(schema, "helmet").kind());

        ConfigFields.Field click = ConfigFields.field(schema, "click");
        assertEquals(ConfigFields.Kind.SOUND, click.kind());
        assertEquals("BLOCK_NOTE_BLOCK_PLING|0.5|1.2", ConfigFields.step(click, "ENTITY_EXPERIENCE_ORB_PICKUP|0.5|1.2", 1, false),
                "the next house sound, written the way the file writes it, volume and pitch kept");
        assertEquals("minecraft:entity.villager.no", ConfigFields.nextSound("minecraft:ui.button.click", -1));

        assertTrue(ConfigFields.display(ConfigFields.field(schema, "window"), 90).contains("⌚"), "minutes read as a length of time");

        ConfigFields.Field warning = ConfigFields.field(schema, "warning");
        ConfigFields.Field critical = ConfigFields.field(schema, "critical");
        assertNull(ConfigFields.order(schema, file.get(), warning, 50));
        assertEquals("most", ConfigFields.order(schema, file.get(), warning, 61)[2], "warning may not pass critical");
        assertEquals("least", ConfigFields.order(schema, file.get(), critical, 20)[2], "critical may not drop under warning");
        assertFalse(ConfigFields.allowed(warning, 51), "max written beside the preset wins");
    }
}
