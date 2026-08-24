package net.exylia.lib.config;

import net.exylia.lib.FakeServer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the public schema projection: what a UI can learn about a config
 * record without touching {@code net.exylia.lib.config.internal}.
 *
 * <p>The projection is a value copy of an analysis the library already performs,
 * so these tests are about fidelity (nothing is lost on the way out) and purity
 * (nothing live leaks, nothing mutates).
 */
class SchemaProjectionTest {

    @TempDir
    Path folder;

    private Plugin plugin;

    // ------------------------------------------------------------------
    // Schemas used by the tests
    // ------------------------------------------------------------------

    @Comment("Storage settings.")
    @Comment("Second header line.")
    record Storage(
            @Comment("Connections kept open.")
            @Comment("Rule of thumb: cores x 2.")
            int poolSize,

            @Key("host-name")
            String hostName,

            List<String> worlds,

            Retry retry
    ) {
        Storage() {
            this(10, "localhost", List.of("world"), new Retry());
        }

        record Retry(int attempts) {
            Retry() {
                this(3);
            }
        }
    }

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Configs.releaseAll();
        plugin = FakeServer.newPlugin("SchemaProjectionTestPlugin", folder.toFile());
    }

    private Schema storageSchema() {
        return Configs.define(plugin, "storage", Storage.class).load().schema();
    }

    // ------------------------------------------------------------------
    // 1.1 — every component is projected
    // ------------------------------------------------------------------

    @Test
    @DisplayName("projects every component in canonical-constructor order")
    void projectsComponentsInDeclarationOrder() {
        Schema schema = storageSchema();

        assertEquals(Storage.class, schema.type());
        assertEquals(List.of("Storage settings.", "Second header line."), schema.comments());
        assertEquals(List.of("poolSize", "hostName", "worlds", "retry"),
                schema.fields().stream().map(Schema.Field::name).toList());
    }

    @Test
    @DisplayName("keeps the Java name while @Key renames only the YAML key")
    void keyRenamesOnlyTheYamlKey() {
        Schema.Field host = field(storageSchema(), "hostName");

        assertEquals("hostName", host.name());
        assertEquals("host-name", host.key());
        assertEquals(String.class, host.type());
    }

    @Test
    @DisplayName("projects @Comment lines in declaration order")
    void projectsCommentsInDeclarationOrder() {
        Schema.Field pool = field(storageSchema(), "poolSize");

        assertEquals(List.of("Connections kept open.", "Rule of thumb: cores x 2."), pool.comments());
    }

    @Test
    @DisplayName("projects a nested record as a non-null nested schema")
    void projectsNestedRecord() {
        Schema.Field retry = field(storageSchema(), "retry");

        Schema nested = retry.nested();
        assertNotNull(nested, "a record component should carry its own schema");
        assertEquals(Storage.Retry.class, nested.type());
        assertEquals(List.of("attempts"), nested.fields().stream().map(Schema.Field::name).toList());
        assertNull(field(nested, "attempts").nested(), "a value component has no nested schema");
    }

    // ------------------------------------------------------------------
    // 1.2 — kebab-case fallback
    // ------------------------------------------------------------------

    @Test
    @DisplayName("falls back to kebab-case when there is no @Key")
    void fallsBackToKebabCase() {
        Schema.Field worlds = field(storageSchema(), "worlds");
        Schema.Field pool = field(storageSchema(), "poolSize");

        assertEquals("pool-size", pool.key(), "camelCase should become kebab-case");
        assertEquals("worlds", worlds.key(), "a single-word name is unchanged");
        assertEquals(List.of(), worlds.comments(), "an undocumented component has no comments");
    }

    // ------------------------------------------------------------------
    // 1.3 — generic type survives
    // ------------------------------------------------------------------

    @Test
    @DisplayName("carries the generic type so the element type is recoverable")
    void carriesGenericElementType() {
        Schema.Field worlds = field(storageSchema(), "worlds");

        assertEquals(List.class, worlds.type());
        assertTrue(worlds.generic() instanceof ParameterizedType,
                "a declared List<String> should project a parameterized type, got: " + worlds.generic());
        ParameterizedType generic = (ParameterizedType) worlds.generic();
        assertEquals(String.class, generic.getActualTypeArguments()[0]);
    }

    // ------------------------------------------------------------------
    // 1.4 — returned collections are immutable
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rejects mutation of fields() and comments()")
    void rejectsMutation() {
        Schema schema = storageSchema();
        Schema.Field pool = field(schema, "poolSize");

        assertThrows(UnsupportedOperationException.class,
                () -> schema.fields().add(pool), "fields() must be immutable");
        assertThrows(UnsupportedOperationException.class,
                () -> schema.comments().add("injected"), "section comments must be immutable");
        assertThrows(UnsupportedOperationException.class,
                () -> pool.comments().add("injected"), "field comments must be immutable");
    }

    @Test
    @DisplayName("copies the lists it is handed, so a later mutation cannot reach it")
    void copiesTheListsItIsGiven() {
        List<String> comments = new java.util.ArrayList<>(List.of("original"));
        Schema schema = new Schema(Storage.class, comments, List.of());

        comments.add("added afterwards");

        assertEquals(List.of("original"), schema.comments(),
                "the projection must copy, not alias, the list it was built from");
    }

    // ------------------------------------------------------------------
    // 1.5 — the projection is a pure value, independent of the file's values
    // ------------------------------------------------------------------

    @Test
    @DisplayName("projects equal schemas for two files of one type holding different values")
    void schemaCarriesNoValues() throws IOException {
        Files.writeString(folder.resolve("first.yml"), "pool-size: 1\n", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("second.yml"), "pool-size: 99\n", StandardCharsets.UTF_8);

        ConfigFile<Storage> first = Configs.define(plugin, "first", Storage.class).load();
        ConfigFile<Storage> second = Configs.define(plugin, "second", Storage.class).load();

        assertEquals(1, first.get().poolSize(), "the fixture must actually hold different values");
        assertEquals(99, second.get().poolSize());
        assertEquals(first.schema(), second.schema(), "schema describes the type, not the values");
    }

    @Test
    @DisplayName("survives a reload of its source unchanged")
    void survivesReload() throws IOException {
        ConfigFile<Storage> file = Configs.define(plugin, "storage", Storage.class).load();
        Schema before = file.schema();

        Files.writeString(folder.resolve("storage.yml"), "pool-size: 77\n", StandardCharsets.UTF_8);
        file.reload();

        assertEquals(77, file.get().poolSize(), "the reload must actually have changed the values");
        assertEquals(List.of("poolSize", "hostName", "worlds", "retry"),
                before.fields().stream().map(Schema.Field::name).toList(),
                "a projection taken earlier is still readable and unchanged");
        assertEquals(before, file.schema(), "re-projecting after a reload returns an equal value");
    }

    @Test
    @DisplayName("never answers null for schema()")
    void schemaIsNeverNull() {
        ConfigFile<Storage> file = Configs.define(plugin, "storage", Storage.class).load();

        assertNotNull(file.schema());
        assertSame(file.schema(), file.schema(),
                "the projection is taken once per file, not rebuilt per call");
    }

    // ------------------------------------------------------------------

    private static Schema.Field field(Schema schema, String name) {
        return schema.fields().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field named " + name + " in " + schema));
    }
}
