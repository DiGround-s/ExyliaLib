package net.exylia.lib.web.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.web.Subject;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which files of a plugin discovery offers, and as what. */
class DiscoveryTest {

    @TempDir
    Path folder;

    private Plugin plugin;
    private ConfigFile<Board> settings;
    private ConfigFile<Board> messages;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("ExyliaMines", folder.toFile());
        settings = Configs.define(plugin, "board", Board.class).load();
        messages = Configs.define(plugin, "messages", Board.class).translated().load();
    }

    @AfterEach
    void tearDown() {
        Configs.release(plugin);
    }

    private void write(String path, String text) throws IOException {
        Path file = folder.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static final String MENU = "title: Mines\nsize: 27\nitems:\n  stone:\n    slot: 13\n    material: STONE\n";

    private Map<String, SubjectImpl> discover(List<SubjectImpl> registered, List<String> excluded, Set<String> bundled) {
        Map<String, SubjectImpl> out = new LinkedHashMap<>();
        for (SubjectImpl subject : Discovery.discover(plugin, registered, excluded, "en", bundled, Configs.loaded())) {
            out.put(subject.source().name(), subject);
        }
        return out;
    }

    @Test
    void everyRuleDecidesWhatIsOffered() throws IOException {
        write("menus/user/mines.yml", MENU);
        write("menus/admin/editor.yml", MENU);
        write("lang/en/extra.yml", "a: 1\n");
        write("lang/es/extra.yml", "a: 1\n");
        write("lang/custom/extra.yml", "a: 1\n");
        write("data/state.yml", "a: 1\n");
        write("Players/uuid.yml", "a: 1\n");
        write("arenas/one.yml", "a: 1\n");
        write("broken.yml", "a: [\n");
        write("notes.txt", "a: 1\n");
        write("rewards.yml", "daily:\n  money: 5\n");
        write("huge.yml", "a: '" + "x".repeat((int) Discovery.MAX_FILE_BYTES) + "'\n");

        Map<String, SubjectImpl> found = discover(List.of(), List.of("arenas/**"), Set.of("menus/admin"));

        assertEquals(Set.of("board", "lang/en/messages", "menus/user/mines", "lang/en/extra", "lang/custom/extra", "rewards"),
                found.keySet());
        assertEquals("settings", found.get("board").group());
        assertEquals("messages", found.get("lang/en/messages").group());
        assertEquals("messages", found.get("lang/en/messages").id());
        SubjectImpl menu = found.get("menus/user/mines");
        assertEquals("menus", menu.group());
        assertEquals("lib/menu", menu.kind());
        assertEquals("menus-user-mines", menu.id());
        assertEquals(List.of("items.*.actions"), menu.sensitive());
        assertEquals("files", found.get("rewards").group());
        assertEquals(Subject.GENERIC, found.get("rewards").kind());
        assertEquals(Discovery.PERMISSION, found.get("rewards").permission());
        assertTrue(found.values().stream().allMatch(SubjectImpl::isDiscovered));
    }

    @Test
    void aRegisteredSubjectCoversItsFileWhateverItsPermission() throws IOException {
        write("cosmetics/tags.yml", "tags:\n  star:\n    display: x\n");
        SubjectImpl board = new SubjectImpl(plugin, "board", settings, "test/board");
        board.permission("only.owners");
        SubjectImpl tags = YamlSubjectImpl.of(plugin, "tags", "cosmetics/tags", Subject.GENERIC);

        Map<String, SubjectImpl> found = discover(List.of(board, tags), List.of(), Set.of());

        assertFalse(found.containsKey("board"), "a file a registration covers is never offered twice");
        assertFalse(found.containsKey("cosmetics/tags"));
        assertTrue(found.containsKey("lang/en/messages"));
        assertFalse(found.values().stream().anyMatch(subject -> subject.id().equals("board")), "ids stay unique");
    }

    @Test
    void anExcludedConfigFileAndAWholeFolderStayOut() throws IOException {
        write("menus/user/mines.yml", MENU);
        Map<String, SubjectImpl> found = discover(List.of(), List.of("board", "menus"), Set.of());
        assertEquals(Set.of("lang/en/messages"), found.keySet());
    }

    @Test
    void idsAreValidUniqueAndShort() {
        Set<String> taken = new HashSet<>(Set.of("menus-user-mines"));
        assertEquals("menus-user-mines-2", Discovery.id("menus/user/mines", taken));
        assertEquals("menus-user-mines-3", Discovery.id("menus/user/mines", taken));
        String long1 = Discovery.id("a".repeat(30) + "/" + "b".repeat(30), taken);
        String long2 = Discovery.id("a".repeat(30) + "/" + "b".repeat(30), taken);
        assertEquals(48, long1.length());
        assertTrue(SubjectImpl.ID.matcher(long2).matches(), long2);
        assertFalse(long1.equals(long2));
        assertEquals("v1-2-shop", Discovery.id("v1.2 shop", taken));
    }

    @Test
    void globsCrossFoldersOnlyWithTwoStars() {
        assertTrue(Discovery.excluded("arenas/one.yml", List.of(Discovery.glob("arenas/*"))));
        assertFalse(Discovery.excluded("arenas/deep/one.yml", List.of(Discovery.glob("arenas/*"))));
        assertTrue(Discovery.excluded("arenas/deep/one.yml", List.of(Discovery.glob("arenas/**"))));
        assertTrue(Discovery.excluded("one.yml", List.of(Discovery.glob("**/one"))));
        assertTrue(Discovery.excluded("a/b/one.yml", List.of(Discovery.glob("**/one.yml"))));
    }
}
