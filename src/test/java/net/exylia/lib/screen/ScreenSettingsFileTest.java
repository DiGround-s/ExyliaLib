package net.exylia.lib.screen;

import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The frame cursor and the instant hover follow what an owner writes in {@code screens.yml}. */
class ScreenSettingsFileTest {

    @TempDir
    Path folder;

    @Test
    void offInTheFileTurnsBothOff() throws Exception {
        net.exylia.lib.FakeServer.install();
        Configs.releaseAll();
        Plugin plugin = net.exylia.lib.FakeServer.newPlugin("ScreenSettingsFileTest", folder.toFile());
        ConfigFile<ScreenSettings> file = Configs.define(plugin, "screens", ScreenSettings.class).load();
        assertTrue(file.get().cursorPolls());
        assertTrue(file.get().hoverHighlights());
        Path yml = folder.resolve("screens.yml");
        String text = Files.readString(yml, StandardCharsets.UTF_8)
                .replace("cursor-poll: AUTO", "cursor-poll: OFF")
                .replace("hover-highlight: AUTO", "hover-highlight: OFF");
        Files.writeString(yml, text, StandardCharsets.UTF_8);
        file.reload();
        assertFalse(file.get().cursorPolls(), "cursor-poll: OFF");
        assertFalse(file.get().hoverHighlights(), "hover-highlight: OFF");
        // The menu shows the word that was written, not the boolean YAML read it as.
        org.junit.jupiter.api.Assertions.assertEquals("OFF", file.get().cursorPoll());
    }
}
