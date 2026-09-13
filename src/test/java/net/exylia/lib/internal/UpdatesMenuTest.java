package net.exylia.lib.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.action.Actions;
import net.exylia.lib.config.internal.DefaultUpdates;
import net.exylia.lib.config.internal.DefaultsMerge;
import net.exylia.lib.debug.DebugCapture;
import net.exylia.lib.text.Lines;
import net.exylia.lib.ui.Menus;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdatesMenuTest {

    @TempDir
    Path folder;

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("ExyliaLib", folder.toFile());
    }

    @AfterEach
    void tearDown() {
        UpdatesMenu.release();
        Menus.releaseAll();
        Actions.releaseAll();
        DebugCapture.stop();
        DefaultUpdates.releaseAll();
        FakeServer.reset();
    }

    @Test
    void everyScreenCompilesWithoutADeadButton() {
        List<String> messages = DebugCapture.start();

        UpdatesMenu.init(plugin);

        var menus = Menus.of(plugin, UpdatesMenu.NAMESPACE);
        assertTrue(menus.definition(UpdatesMenu.PLUGINS).isPresent());
        assertTrue(menus.definition(UpdatesMenu.FILES).isPresent());
        assertTrue(menus.definition(UpdatesMenu.CHANGES).isPresent());
        assertTrue(messages.isEmpty(), messages::toString);
    }

    @Test
    void rowsAreGroupedByPluginAndFile() {
        List<DefaultUpdates.Pending> pending = List.of(
                pending(1, "ExyliaFFA", "config.yml", "cooldown"),
                pending(2, "ExyliaFFA", "config.yml", "range"),
                pending(3, "ExyliaFFA", "menus/main.yml", "title"),
                pending(4, "ExyliaClans", "config.yml", "tag"));

        assertEquals(2, UpdatesMenu.pluginRows(pending).size());
        assertEquals(2, UpdatesMenu.fileRows(pending, "ExyliaFFA").size());
        assertEquals(2, UpdatesMenu.changeRows(pending, "ExyliaFFA", "config.yml").size());
    }

    @Test
    void longValuesBecomeShortLoreLines() {
        String shown = UpdatesMenu.lines(Map.of("lore", List.of("{letters}a very long line of lore that keeps on going past the edge", "b")));
        List<String> lines = List.of(shown.split(Lines.NEWLINE));

        assertEquals("lore:", lines.getFirst());
        assertTrue(lines.get(1).startsWith("  - {letters}a very"), lines.toString());
        assertTrue(lines.get(1).endsWith("…"), lines.toString());
        assertEquals("nothing", UpdatesMenu.lines(null));
        assertEquals(8, UpdatesMenu.lines(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)).split(Lines.NEWLINE).length);
    }

    private static DefaultUpdates.Pending pending(int id, String plugin, String file, String key) {
        return new DefaultUpdates.Pending(id, plugin, file,
                new DefaultsMerge.Change(List.of(key), DefaultsMerge.Kind.CHANGED, 1, 2));
    }
}
