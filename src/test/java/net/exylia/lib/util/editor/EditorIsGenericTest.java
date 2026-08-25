package net.exylia.lib.util.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor engine knows nothing about what it edits.
 *
 * <p>Read from compiled bytecode rather than from source text, because a
 * reference is what actually couples two classes: an import can be absent while
 * a fully-qualified name inline does the coupling anyway.
 *
 * <h2>Which direction is forbidden, and why</h2>
 * A domain module may know about the editor — {@code PluginRewards.editor},
 * {@code Loot.editor}, {@code NamedCommands.editor} all do — and the editor may
 * never know about a domain. The moment it does, adding a seventh editor means
 * touching the engine, which is exactly how ExyliaCommons ended up with five
 * copies of the same screen.
 *
 * <p>Plain value types are not domains and are not banned: an
 * {@code ItemStack} and an {@code ExyliaLocation} are values every module
 * passes around, and the descriptors over them have nothing to couple to.
 */
class EditorIsGenericTest {

    /** Modules with a runtime of their own, which the engine must not name. */
    private static final List<String> FORBIDDEN = List.of(
            "net/exylia/lib/util/reward",
            "net/exylia/lib/util/loot",
            "net/exylia/lib/util/command",
            "net/exylia/lib/util/sequence");

    @Test
    @DisplayName("no class in the editor module names a domain module")
    void engineIsGeneric() throws IOException, URISyntaxException {
        List<String> offenders = new ArrayList<>();

        for (Path file : classFiles()) {
            String bytes = new String(Files.readAllBytes(file),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            for (String forbidden : FORBIDDEN) {
                if (bytes.contains(forbidden)) {
                    offenders.add(file.getFileName() + " names " + forbidden);
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "the editor engine must not know what it edits: " + offenders);
    }

    @Test
    @DisplayName("the sweep actually read the editor module, and its detector detects")
    void sweepIsNotVacuous() throws IOException, URISyntaxException {
        List<Path> files = classFiles();
        List<String> names = files.stream().map(path -> path.getFileName().toString()).toList();

        assertTrue(names.contains("Editors.class"), "found: " + names);
        assertTrue(names.contains("ListEditor.class"), "found: " + names);
        assertTrue(names.contains("EditorDescriptor.class"), "found: " + names);
        assertTrue(files.size() >= 8, "expected the whole module, found: " + names);

        // An absence assertion that examined nothing passes for the wrong
        // reason, and so does one whose detector cannot detect. A reward class
        // does name its own package, so the same check must find it there.
        Path reward = classesRoot().resolve("net/exylia/lib/util/reward/RewardDescriptor.class");
        assertTrue(Files.exists(reward), "expected a reward class to test the detector against");
        String bytes = new String(Files.readAllBytes(reward),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(bytes.contains("net/exylia/lib/util/reward"),
                "the detector must find a reference that is really there");
        assertFalse(FORBIDDEN.isEmpty());
        assertEquals(4, FORBIDDEN.size());
    }

    /** Where the compiled classes are, which is what both sweeps read. */
    private static Path classesRoot() throws URISyntaxException {
        return Path.of(Editors.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
    }

    private static List<Path> classFiles() throws IOException, URISyntaxException {
        Path root = classesRoot().resolve("net/exylia/lib/util/editor");
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".class")).toList();
        }
    }
}
