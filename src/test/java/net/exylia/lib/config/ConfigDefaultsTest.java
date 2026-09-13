package net.exylia.lib.config;

import net.exylia.lib.FakeServer;
import net.exylia.lib.config.internal.DefaultUpdates;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A changed default never overwrites a value an owner may have chosen: it waits
 * for a decision, and only keys that did not exist are added on their own.
 */
class ConfigDefaultsTest {

    @TempDir
    Path folder;

    private Plugin plugin;

    record Combat(int cooldown, String prefix) {
        Combat() {
            this(35, "new");
        }
    }

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Configs.releaseAll();
        DefaultUpdates.releaseAll();
        plugin = FakeServer.newPlugin("DefaultsPlugin", folder.toFile());
    }

    @AfterEach
    void tearDown() {
        Configs.releaseAll();
        DefaultUpdates.releaseAll();
    }

    private void write(String path, String text) throws IOException {
        Path file = folder.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static List<String> pendingKeys() {
        return DefaultUpdates.pending().stream().map(pending -> pending.change().dotted()).toList();
    }

    @Test
    void aChangedDefaultWaitsAndApplyingItMakesItLive() throws IOException {
        write("combat.yml", "cooldown: 30\nprefix: old\n");
        write(".defaults/configs/combat.yml", "cooldown: 30\nprefix: old\n");

        ConfigFile<Combat> config = Configs.define(plugin, "combat", Combat.class).load();

        assertEquals(30, config.get().cooldown());
        assertEquals(List.of("cooldown", "prefix"), pendingKeys());

        DefaultUpdates.Decision applied = DefaultUpdates.decide(
                pending -> pending.change().dotted().equals("cooldown"), true);

        assertEquals(1, applied.applied());
        assertEquals(35, config.get().cooldown());
        assertEquals("old", config.get().prefix());
        assertEquals(List.of("prefix"), pendingKeys());
    }

    @Test
    void keepingAChangeLeavesTheValueAndNeverAsksAgain() throws IOException {
        write("combat.yml", "cooldown: 30\nprefix: old\n");
        write(".defaults/configs/combat.yml", "cooldown: 30\nprefix: old\n");
        Configs.define(plugin, "combat", Combat.class).load();

        DefaultUpdates.Decision kept = DefaultUpdates.decide(pending -> true, false);

        assertEquals(2, kept.kept());
        Configs.releaseAll();
        ConfigFile<Combat> again = Configs.define(plugin, "combat", Combat.class).load();
        assertEquals(30, again.get().cooldown());
        assertEquals("old", again.get().prefix());
        assertTrue(DefaultUpdates.pending().isEmpty(), pendingKeys()::toString);
    }

    @Test
    void anEditedValueIsNeverOffered() throws IOException {
        write("combat.yml", "cooldown: 45\nprefix: old\n");
        write(".defaults/configs/combat.yml", "cooldown: 30\nprefix: old\n");

        ConfigFile<Combat> config = Configs.define(plugin, "combat", Combat.class).load();

        assertEquals(45, config.get().cooldown());
        assertEquals(List.of("prefix"), pendingKeys());
    }

    @Test
    void aServerThatPredatesReviewedDefaultsKeepsEveryValue() throws IOException {
        write("combat.yml", "cooldown: 30\n");

        ConfigFile<Combat> config = Configs.define(plugin, "combat", Combat.class).load();

        assertEquals(30, config.get().cooldown());
        assertEquals("new", config.get().prefix());
        assertTrue(DefaultUpdates.pending().isEmpty());
        assertTrue(Files.exists(folder.resolve(".defaults/configs/combat.yml")));
    }

    @Test
    void aFreshInstallHasNothingToReview() {
        Configs.define(plugin, "combat", Combat.class).load();

        assertTrue(DefaultUpdates.pending().isEmpty());
        assertTrue(Files.exists(folder.resolve(".defaults/configs/combat.yml")));
    }
}
