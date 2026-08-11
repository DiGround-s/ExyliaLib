package net.exylia.lib.scoreboard;

import net.exylia.lib.FakeServer;
import net.exylia.lib.config.Configs;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The promise that moving a plugin from ExyliaCommons to ExyliaLib does not
 * make a server owner rewrite their scoreboards.
 *
 * <p>The files here are written exactly as ExyliaCommons wrote them, down to
 * the scalar title and the tick-based interval, and they have to load into the
 * new record with the same meaning.
 */
class SidebarConfigTest {

    @TempDir
    Path folder;

    private Plugin plugin;

    /** A plugin's scoreboards section, as one would declare it. */
    record Boards(SidebarConfig ffa, SidebarConfig lobby) {
        Boards() {
            this(new SidebarConfig(), new SidebarConfig());
        }
    }

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Configs.releaseAll();
        plugin = FakeServer.newPlugin("SidebarConfigTestPlugin", folder.toFile());
    }

    private void write(String name, String yaml) throws IOException {
        Files.writeString(folder.resolve(name + ".yml"), yaml, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a scoreboard file written by ExyliaCommons loads unchanged")
    void commonsFileLoads() throws IOException {
        write("scoreboards", """
                ffa:
                  enabled: true
                  title: '{primary}&lFFA'
                  lines:
                  - ''
                  - ' {muted}❙ {letters}Kills: {success}%kills%'
                  - ' {highlight}exylia.net'
                  update:
                    interval: 15
                    smart: true
                    cache: false
                lobby:
                  enabled: false
                  title: 'LOBBY'
                  lines:
                  - 'a line'
                """);

        Boards boards = Configs.define(plugin, "scoreboards", Boards.class).load().get();

        SidebarConfig ffa = boards.ffa();
        assertTrue(ffa.enabled());
        // A scalar title is one frame, so an old file animates nothing.
        assertEquals(List.of("{primary}&lFFA"), ffa.title());
        assertEquals(3, ffa.lines().size());
        assertEquals(" {highlight}exylia.net", ffa.lines().get(2));
        // Ticks, as ExyliaCommons meant them: fifteen ticks, not fifteen seconds.
        assertEquals(15, ffa.update().interval());
        assertTrue(ffa.update().smart());
        assertFalse(ffa.update().cache());

        SidebarConfig lobby = boards.lobby();
        assertFalse(lobby.enabled());
        // A missing update section keeps the ExyliaCommons defaults.
        assertEquals(20, lobby.update().interval());
        assertTrue(lobby.update().smart());
        assertTrue(lobby.update().cache());
    }

    @Test
    @DisplayName("a title written as a list becomes animation frames")
    void titleListBecomesFrames() throws IOException {
        write("frames", """
                ffa:
                  enabled: true
                  title:
                  - '{primary}FFA'
                  - '{secondary}FFA'
                  lines:
                  - 'a line'
                """);

        Boards boards = Configs.define(plugin, "frames", Boards.class).load().get();

        assertEquals(List.of("{primary}FFA", "{secondary}FFA"), boards.ffa().title());
    }

    @Test
    @DisplayName("a refresh interval below one tick is clamped instead of stalling")
    void intervalIsClamped() {
        assertEquals(1, new SidebarConfig.Update(0, true, true).interval());
        assertEquals(1, new SidebarConfig.Update(-40, true, true).interval());
    }

    @Test
    @DisplayName("an unwritten section is a disabled board rather than a crash")
    void defaultsAreHarmless() {
        SidebarConfig config = new SidebarConfig();

        assertFalse(config.enabled());
        assertEquals(List.of(), config.title());
        assertEquals(List.of(), config.lines());
        assertEquals(20, config.update().interval());
    }
}
