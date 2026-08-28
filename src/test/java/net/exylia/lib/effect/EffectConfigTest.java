package net.exylia.lib.effect;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.effect.internal.EffectRuntime;
import net.exylia.lib.effect.internal.Packets;
import net.exylia.lib.text.Colors;
import net.exylia.lib.text.Palette;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The path a server owner actually uses: YAML into a record, record onto the
 * screen.
 *
 * <p>This is the point of the module, so it is tested end to end rather than in
 * pieces.
 */
class EffectConfigTest {

    @TempDir
    Path folder;

    private Plugin plugin;
    private FakePlayer viewer;

    /** A plugin's own config, with effects nested inside it as a section. */
    record Arena(EffectConfig onWin, EffectConfig onCountdown) {

        /** The defaults the config module writes out on first run. */
        Arena() {
            this(new EffectConfig(), new EffectConfig());
        }
    }

    /** The same, with one effect that actually says something. */
    record Round(EffectConfig onWin) {

        Round() {
            this(new EffectConfig(new EffectConfig.Title("{primary}VICTORY", "", 0, 3, 1, 0, "auto"),
                    new EffectConfig.ActionBar(), new EffectConfig.BossBar(),
                    new EffectConfig.Sound(), new EffectConfig.Particle(),
                    new EffectConfig.Firework()));
        }
    }

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Packets.override(false);
        Colors.apply(new Palette());

        plugin = FakeServer.newPlugin("ArenaPlugin", folder.toFile());
        Effects.owner(plugin);
        viewer = new FakePlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        EffectRuntime.stopEverything();
        Configs.release("ArenaPlugin");
        FakeServer.reset();
        Packets.reset();
    }

    @Test
    @DisplayName("an effect written in YAML is generated with its manual")
    void yamlIsGeneratedWithComments() throws Exception {
        Configs.define(plugin, "round", Round.class).load();

        String yaml = Files.readString(new File(folder.toFile(), "round.yml").toPath());

        assertTrue(yaml.contains("on-win:"), "sections are named as written in YAML");
        assertTrue(yaml.contains("title:"), "nested records become nested sections");
        assertTrue(yaml.contains("time-style:"), "camel case becomes dashed keys");
        assertTrue(yaml.contains("# "), "an owner needs the comments to know what to change");

        // The part that made these files unreadable: an effect that is one
        // title also wrote an action bar, a boss bar, a sound, particles and a
        // firework, every one of them empty and every key of them commented.
        // Fifteen effects in a plugin's config was a thousand lines of nothing.
        assertFalse(yaml.contains("firework:"), "a section that does nothing is not written:\n" + yaml);
        assertFalse(yaml.contains("particle:"), yaml);
        assertFalse(yaml.contains("boss-bar:"), yaml);
    }

    @Test
    @DisplayName("the empty blocks are not written back on the next load either")
    void omittedSectionsStayOut() throws Exception {
        Configs.define(plugin, "round", Round.class).load();
        Configs.release("ArenaPlugin");
        Configs.define(plugin, "round", Round.class).load();

        String yaml = Files.readString(new File(folder.toFile(), "round.yml").toPath());

        assertFalse(yaml.contains("firework:"),
                "an omitted section is not a missing key, or it grows back:\n" + yaml);
    }

    @Test
    @DisplayName("a title appears the moment it is sent")
    void titlesDoNotFadeInByDefault() {
        // A title reacting to something that just happened has to be on screen
        // when it happens; half a second of it fading up reads as lag.
        assertEquals(0.0, new EffectConfig.Title().fadeIn());
    }

    @Test
    @DisplayName("an effect written by hand is read back and played")
    void handWrittenEffectPlays() throws Exception {
        Files.writeString(new File(folder.toFile(), "arena.yml").toPath(), """
                on-win:
                  title:
                    text: '{primary}VICTORY'
                    subtitle: 'Well played'
                    fade-in: 0.5
                    stay: 3.0
                    fade-out: 0.5
                  action-bar:
                    text: 'You win'
                    duration: 2.0
                  sound:
                    name: ENTITY_PLAYER_LEVELUP
                    volume: 0.8
                    pitch: 1.2
                on-countdown:
                  boss-bar:
                    text: 'Starting in %time%s'
                    colour: PURPLE
                    countdown: 10.0
                    time-style: tenths
                """);

        ConfigFile<Arena> config = Configs.define(plugin, "arena", Arena.class).load();
        Arena arena = config.get();

        assertNotNull(arena.onWin());
        assertEquals("{primary}VICTORY", arena.onWin().title().text());
        assertEquals(3.0, arena.onWin().title().stay(), 0.0001);
        assertEquals("ENTITY_PLAYER_LEVELUP", arena.onWin().sound().name());
        assertEquals(10.0, arena.onCountdown().bossBar().countdown(), 0.0001);

        Effects.play(arena.onWin(), viewer.player());
        FakeServer.tick(1);

        assertTrue(viewer.actionBars().contains("You win"),
                "what is written in the file must reach the screen");
    }

    @Test
    @DisplayName("a configured countdown really counts")
    void configuredCountdownCounts() throws Exception {
        Files.writeString(new File(folder.toFile(), "arena.yml").toPath(), """
                on-countdown:
                  boss-bar:
                    text: '%time%'
                    countdown: 2.0
                    time-style: tenths
                """);

        Arena arena = Configs.define(plugin, "arena", Arena.class).load().get();
        Display display = Effects.play(arena.onCountdown(), viewer.player());
        FakeServer.tick(1);

        assertNotNull(display, "a bar that stays on screen must return a handle");
        assertNotNull(display.timer());
        assertEquals(2.0, display.timer().displayed(), 0.1);

        FakeServer.tick(20);

        assertEquals(1.0, display.timer().displayed(), 0.1, "a second of ticks is a second gone");
    }

    @Test
    @DisplayName("a section left out simply does not play")
    void missingSectionsAreSkipped() throws Exception {
        Files.writeString(new File(folder.toFile(), "arena.yml").toPath(), """
                on-win:
                  action-bar:
                    text: 'Only this'
                    duration: 1.0
                """);

        Arena arena = Configs.define(plugin, "arena", Arena.class).load().get();
        Effects.play(arena.onWin(), viewer.player());
        FakeServer.tick(1);

        assertEquals(1, viewer.actionBars().size());
        assertEquals(0, viewer.bossBarsShown(), "a section that was not written must not play");
    }

    @Test
    @DisplayName("a typo in the file does not take the server down")
    void typosDegrade() throws Exception {
        Files.writeString(new File(folder.toFile(), "arena.yml").toPath(), """
                on-win:
                  boss-bar:
                    text: 'Still works'
                    colour: NOT_A_COLOUR
                    overlay: NONSENSE
                    countdown: not-a-number
                """);

        ConfigFile<Arena> config = Configs.define(plugin, "arena", Arena.class).load();
        Arena arena = config.get();

        assertFalse(config.issues().isEmpty(), "a bad value must be reported to the owner");

        Display display = Effects.play(arena.onWin(), viewer.player());
        FakeServer.tick(1);

        assertNotNull(display, "and the effect must still play with defaults");
        assertEquals(1, viewer.bossBarsShown());
    }

    @Test
    @DisplayName("a boss bar with no countdown stays until it is stopped")
    void configuredPermanentBarStays() throws Exception {
        Files.writeString(new File(folder.toFile(), "arena.yml").toPath(), """
                on-win:
                  boss-bar:
                    text: 'Waiting'
                """);

        Arena arena = Configs.define(plugin, "arena", Arena.class).load().get();
        Display display = Effects.play(arena.onWin(), viewer.player());

        FakeServer.tick(200);

        assertTrue(display.isShowing(), "a bar with no timer must not disappear on its own");

        display.stop();
        FakeServer.tick(1);

        assertFalse(display.isShowing());
        assertEquals(0, EffectRuntime.active());
    }

    @Test
    @DisplayName("a configured effect plays under the plugin that owns it")
    void ownedConfigEffectBelongsToItsPlugin() throws Exception {
        // A second owner is what breaks the static path: with more than one
        // registration the owner has to come from the caller's classloader,
        // which a shaded or externally loaded plugin does not have.
        Plugin other = FakeServer.newPlugin("OtherPlugin", folder.toFile());
        Effects.owner(other);
        try {
            Files.writeString(new File(folder.toFile(), "arena.yml").toPath(), """
                    on-win:
                      boss-bar:
                        text: 'Owned'
                    """);

            Arena arena = Configs.define(plugin, "arena", Arena.class).load().get();
            Display display = Effects.of(plugin).play(arena.onWin(), viewer.player());
            FakeServer.tick(1);

            assertNotNull(display, "the owner-scoped form must not need to guess a caller");
            assertEquals(1, EffectRuntime.stopAll("ArenaPlugin"),
                    "the display must be cleaned up with the plugin that played it");
        } finally {
            EffectRuntime.release("OtherPlugin");
        }
    }
}
