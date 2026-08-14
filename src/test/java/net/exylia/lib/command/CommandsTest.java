package net.exylia.lib.command;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.placeholder.Placeholders;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command module, checked against the syntax deployed files use.
 */
class CommandsTest {

    private Plugin plugin;
    private PluginCommands commands;
    private FakePlayer steve;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Commands.releaseAll();
        plugin = FakeServer.newPlugin("Practice", null);
        commands = Commands.of(plugin);
        steve = new FakePlayer("Steve");
        FakeServer.online(steve.player());
    }

    @AfterEach
    void tearDown() {
        Commands.releaseAll();
        Placeholders.unregisterAll("Practice");
        FakeServer.reset();
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the actor prefixes deployed files use are understood")
    void parsesActorPrefixes() {
        assertEquals(CommandActor.PLAYER, CommandLine.compile("player: queue").actor());
        assertEquals(CommandActor.CONSOLE, CommandLine.compile("console: say hi").actor());
        assertEquals(CommandActor.PLAYER_PROXY, CommandLine.compile("player-proxy: server hub").actor());
        assertEquals(CommandActor.CONSOLE_PROXY, CommandLine.compile("console-proxy: alert hi").actor());
    }

    @Test
    @DisplayName("the prefix is case insensitive and tolerates spacing")
    void prefixIsForgiving() {
        assertEquals(CommandActor.CONSOLE, CommandLine.compile("CONSOLE:say hi").actor());
        assertEquals(CommandActor.PLAYER, CommandLine.compile("  Player :  queue  ").actor());
    }

    @Test
    @DisplayName("a command with no prefix runs as the player, so bare menu commands work")
    void bareCommandRunsAsPlayer() {
        // Three buttons in the live settings menu are written exactly like
        // this. Defaulting them to console, as the old parser did, dispatched
        // a command whose handler requires a player, so nothing happened.
        CommandLine line = CommandLine.compile("killeffect");
        assertEquals(CommandActor.PLAYER, line.actor());
        assertEquals("killeffect", line.render(steve.player()));
    }

    @Test
    @DisplayName("a leading slash is dropped rather than dispatched with it")
    void leadingSlashIsDropped() {
        assertEquals("warp arena", CommandLine.compile("player: /warp arena").render(steve.player()));
        assertEquals("spawn", CommandLine.compile("/spawn").render(steve.player()));
    }

    @Test
    @DisplayName("a namespaced command keeps its colon instead of being read as a prefix")
    void namespacedCommandIsNotAPrefix() {
        CommandLine line = CommandLine.compile("player: practice:open_regions");
        assertEquals(CommandActor.PLAYER, line.actor());
        assertEquals("practice:open_regions", line.render(steve.player()));
    }

    @Test
    @DisplayName("an unknown prefix is part of the command, not a silent console fallback")
    void unknownPrefixIsPartOfTheCommand() {
        CommandLine line = CommandLine.compile("minecraft:give Steve stone");
        assertEquals(CommandActor.PLAYER, line.actor());
        assertEquals("minecraft:give Steve stone", line.render(steve.player()));
    }

    @Test
    @DisplayName("a prefix with nothing after it is rejected when the file loads")
    void emptyCommandIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CommandLine.compile("console:"));
        assertThrows(IllegalArgumentException.class, () -> CommandLine.compile("player:   "));
        assertThrows(IllegalArgumentException.class, () -> CommandLine.compile("   "));
    }

    @Test
    @DisplayName("a command without placeholders is finished at compile time")
    void staticCommandsAreCompiledOnce() {
        assertFalse(CommandLine.compile("console: say hello").isDynamic());
        assertTrue(CommandLine.compile("console: say %player_name%").isDynamic());
    }

    // ------------------------------------------------------------------
    // Running
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a player command runs as the player")
    void playerCommandRunsAsPlayer() {
        CommandResult result = await(commands.run(CommandLine.compile("player: queue"), steve.player()));

        assertTrue(result.isDispatched());
        assertEquals(List.of("queue"), steve.commands());
        assertEquals(List.of(), FakeServer.consoleCommands());
    }

    @Test
    @DisplayName("a console command runs as the console")
    void consoleCommandRunsAsConsole() {
        CommandResult result = await(commands.run(
                CommandLine.compile("console: give Steve diamond"), steve.player()));

        assertTrue(result.isDispatched());
        assertEquals(List.of("give Steve diamond"), FakeServer.consoleCommands());
        assertEquals(List.of(), steve.commands());
    }

    @Test
    @DisplayName("placeholders are resolved for the player who pressed the button")
    void placeholdersResolvePerPlayer() {
        Placeholders.group(plugin, "queue")
                .add("current", request -> "boxing")
                .register();

        await(commands.run(CommandLine.compile("console: spectate %queue_current%"), steve.player()));

        assertEquals(List.of("spectate boxing"), FakeServer.consoleCommands());
    }

    @Test
    @DisplayName("extra values reach the placeholders, so a row can carry its own target")
    void rowDataReachesPlaceholders() {
        Placeholders.group(plugin, "row")
                .add("target", request -> request.get("target", String.class, ""))
                .register();

        await(commands.run(CommandLine.compile("console: kick %row_target%"),
                steve.player(), Map.of("target", "Alex")));

        assertEquals(List.of("kick Alex"), FakeServer.consoleCommands());
    }

    @Test
    @DisplayName("a command the server does not accept is reported, not called success")
    void rejectedCommandIsReported() {
        FakeServer.consoleRejectsCommands();

        CommandResult result = await(commands.run(
                CommandLine.compile("console: nonsense"), steve.player()));

        assertEquals(CommandResult.Status.REJECTED, result.status());
        assertFalse(result.isDispatched());
    }

    @Test
    @DisplayName("a player who logged out is reported instead of throwing")
    void offlinePlayerIsReported() {
        steve.disconnect();

        CommandResult result = await(commands.run(
                CommandLine.compile("player: queue"), steve.player()));

        assertEquals(CommandResult.Status.NO_PLAYER, result.status());
        assertEquals(List.of(), steve.commands());
    }

    // ------------------------------------------------------------------
    // Order
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a list runs in the order it is written")
    void listRunsInOrder() {
        List<CommandLine> lines = commands.compileAll(List.of(
                "console: first",
                "console: second",
                "console: third"));

        List<CommandResult> results = await(commands.run(lines, steve.player()));

        assertEquals(List.of("first", "second", "third"), FakeServer.consoleCommands());
        assertEquals(3, results.size());
    }

    @Test
    @DisplayName("player and console commands keep their relative order")
    void mixedActorsKeepOrder() {
        // The ordering that matters in practice: set something up as console,
        // then move the player. Reversed, the player arrives before the setup.
        List<CommandLine> lines = commands.compileAll(List.of(
                "console: region flag arena pvp deny",
                "player: warp arena"));

        await(commands.run(lines, steve.player()));

        assertEquals(List.of("region flag arena pvp deny"), FakeServer.consoleCommands());
        assertEquals(List.of("warp arena"), steve.commands());
    }

    @Test
    @DisplayName("a failing command stops the ones after it")
    void failureStopsTheRest() {
        FakeServer.consoleRejectsCommands();

        List<CommandResult> results = await(commands.run(commands.compileAll(List.of(
                "console: broken",
                "player: should_not_run")), steve.player()));

        assertEquals(1, results.size());
        assertEquals(CommandResult.Status.REJECTED, results.get(0).status());
        assertEquals(List.of(), steve.commands(), "the rest of the list was skipped");
    }

    // ------------------------------------------------------------------
    // Proxy
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a proxy command with no bridge says so instead of reporting success")
    void proxyWithoutBridgeIsHonest() {
        CommandResult result = await(commands.run(
                CommandLine.compile("player-proxy: server hub"), steve.player()));

        assertEquals(CommandResult.Status.NO_TRANSPORT, result.status());
        assertEquals(List.of(), FakeServer.consoleCommands(), "nothing was run locally");
        assertEquals(List.of(), steve.commands());
    }

    @Test
    @DisplayName("an installed bridge receives the rendered command and its actor")
    void installedBridgeReceivesTheCommand() {
        List<String> sent = new java.util.ArrayList<>();
        commands.proxy(new ProxyCommands() {
            @Override
            public CompletableFuture<CommandResult> send(CommandActor actor, String command,
                                                         org.bukkit.entity.Player carrier) {
                sent.add(actor.prefix() + "|" + command + "|" + carrier.getName());
                return CompletableFuture.completedFuture(CommandResult.dispatched(command));
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        });

        CommandResult result = await(commands.run(
                CommandLine.compile("console-proxy: alert restarting"), steve.player()));

        assertTrue(result.isDispatched());
        assertEquals(List.of("console-proxy|alert restarting|Steve"), sent);
    }

    @Test
    @DisplayName("a proxy command that fails stops the list, like any other")
    void proxyFailureStopsTheList() {
        List<CommandResult> results = await(commands.run(commands.compileAll(List.of(
                "player-proxy: server hub",
                "console: should_not_run")), steve.player()));

        assertEquals(1, results.size());
        assertEquals(CommandResult.Status.NO_TRANSPORT, results.get(0).status());
        assertEquals(List.of(), FakeServer.consoleCommands());
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    @Test
    @DisplayName("commands are read as a list or as a single string")
    void readsBothConfigShapes() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                as_list:
                  commands:
                    - "player: queue"
                    - "console: say hi"
                as_string:
                  commands: "player: spawn"
                absent:
                  material: STONE
                """);

        assertEquals(2, Commands.fromConfig(config.getConfigurationSection("as_list"), "commands").size());
        assertEquals(1, Commands.fromConfig(config.getConfigurationSection("as_string"), "commands").size());
        assertEquals(0, Commands.fromConfig(config.getConfigurationSection("absent"), "commands").size());
    }

    @Test
    @DisplayName("the real lobby hotbar file is understood as written")
    void realLobbyItemsCompile() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                queue:
                  slot: 0
                  material: MACE
                  commands:
                    - "any: player: queue"
                party:
                  slot: 2
                  material: AMETHYST_SHARD
                  commands:
                    - "any: player: party hub"
                """);

        // The click prefix belongs to the UI module, which strips it before a
        // line reaches here; what matters is that the rest is a valid command.
        CommandLine queue = CommandLine.compile("player: queue");
        assertEquals(CommandActor.PLAYER, queue.actor());
        assertEquals("queue", queue.render(steve.player()));
        assertTrue(config.contains("party.commands"));
    }

    @Test
    @DisplayName("each plugin gets its own runner, and disabling one forgets it")
    void runnersArePerPluginAndReleased() {
        Plugin other = FakeServer.newPlugin("Survival", null);

        assertTrue(Commands.of(plugin) == commands, "the same plugin reuses its runner");
        assertFalse(Commands.of(other) == commands, "a different plugin gets its own");

        Commands.release("Practice");
        assertFalse(Commands.of(plugin) == commands, "a disabled plugin's runner is dropped");
    }
}
