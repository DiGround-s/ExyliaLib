package net.exylia.lib.command;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.ui.ClickBindings;
import net.exylia.lib.ui.ClickKind;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hotbar ExyliaPracticeCore ships, loaded from the real files.
 *
 * <p>The whole lobby hotbar is built from {@code commands}, so if this syntax
 * is read wrongly the first thing a player touches after joining does nothing.
 */
class RealItemCommandsTest {

    private PluginCommands commands;
    private FakePlayer steve;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Commands.releaseAll();
        Plugin plugin = FakeServer.newPlugin("Practice", null);
        commands = Commands.of(plugin);
        steve = new FakePlayer("Steve");
        FakeServer.online(steve.player());
    }

    @AfterEach
    void tearDown() {
        Commands.releaseAll();
        FakeServer.reset();
    }

    @Test
    @DisplayName("every shipped lobby item compiles, and its click prefix is understood")
    void everyRealItemCompiles() throws Exception {
        Path root = Path.of(getClass().getResource("/practice-items").toURI());
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(path -> path.toString().endsWith(".yml")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "the real item files should be there");

        List<String> compiled = new ArrayList<>();
        for (Path file : files) {
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(Files.readString(file));

            for (String key : config.getKeys(false)) {
                ConfigurationSection item = config.getConfigurationSection(key);
                if (item == null || !item.contains("commands")) {
                    continue;
                }
                ClickBindings.Builder bindings = new ClickBindings.Builder();
                for (String line : item.getStringList("commands")) {
                    bindings.addCommand(line);
                }
                ClickBindings built = bindings.build();
                for (CommandLine line : built.commandsForClick(ClickKind.LEFT)) {
                    compiled.add(key + " -> " + line.actor().prefix() + ": "
                            + line.render(steve.player()));
                }
            }
        }

        assertTrue(compiled.size() >= 10, "expected the hotbar's commands, found " + compiled);
        // "any: player: queue" has to survive both prefixes to reach the player.
        assertTrue(compiled.contains("queue -> player: queue"),
                "the queue button should run /queue as the player: " + compiled);
        assertTrue(compiled.stream().anyMatch(entry -> entry.startsWith("party -> player: party hub")),
                "the party button should run /party hub as the player: " + compiled);
    }

    @Test
    @DisplayName("the bare commands in the real settings menu run as the player")
    void bareSettingsCommandsRunAsPlayer() throws Exception {
        // settings.yml writes these three with no actor prefix at all. The old
        // parser defaulted that to console, and /killeffect requires a player,
        // so all three did nothing. They are the reason the default changed.
        Path menu = Path.of(getClass().getResource("/practice-menus/player/settings.yml").toURI());
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(Files.readString(menu));

        for (String key : List.of("effects", "hiteffect", "armor_trims")) {
            List<String> lines = config.getStringList("items." + key + ".commands");
            assertEquals(1, lines.size(), key + " should still be a single bare command");

            CommandLine line = CommandLine.compile(lines.get(0));
            assertEquals(CommandActor.PLAYER, line.actor(), key + " must run as the player");

            commands.run(line, steve.player()).join();
        }

        assertEquals(List.of("killeffect", "hiteffect", "armortrim"), steve.commands());
        assertEquals(List.of(), FakeServer.consoleCommands(), "none of these belong to the console");
    }

    @Test
    @DisplayName("the shipped hotbar buttons actually run when clicked")
    void realButtonsRun() throws Exception {
        Path lobby = Path.of(getClass().getResource("/practice-items/lobby.yml").toURI());
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(Files.readString(lobby));

        ClickBindings.Builder bindings = new ClickBindings.Builder();
        for (String line : config.getStringList("queue.commands")) {
            bindings.addCommand(line);
        }

        List<CommandLine> onClick = bindings.build().commandsForClick(ClickKind.LEFT);
        List<CommandResult> results = commands.run(onClick, steve.player()).join();

        assertEquals(1, results.size());
        assertTrue(results.get(0).isDispatched());
        assertEquals(List.of("queue"), steve.commands());
    }
}
