package net.exylia.lib.web.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A YAML file with no record behind it: what is inferred, what survives a write, what is refused. */
class YamlSourceTest {

    private static final String CATALOG = """
            # Tags — what is drawn before a name.

            categories:
              basic:
                name: '{primary}&lBASIC'
                priority: 1
            # One entry per tag.
            tags:
              star:
                category: basic
                display: '&e★'
                description: A star
                price: 500
              crown:
                category: basic
                display: '&6♛'
                description:
                - A crown
                - for kings
                price:
                  amount: 50
                  currency: gems
                weight: 1.5
              v1.2:
                category: basic
                display: '&a2'
                weight: 2
            """;

    @TempDir
    Path folder;

    private YamlSource source() throws IOException {
        Path file = folder.resolve("cosmetics/tags.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, CATALOG);
        return new YamlSource(file.toFile(), "cosmetics/tags");
    }

    private static JsonObject field(JsonObject section, String key) {
        for (JsonElement element : section.getAsJsonArray("fields")) {
            if (element.getAsJsonObject().get("key").getAsString().equals(key)) {
                return element.getAsJsonObject();
            }
        }
        throw new AssertionError("no field " + key);
    }

    @Test
    void blocksOfBlocksAreMapsWithTheEntriesKeysMerged() throws IOException {
        JsonObject schema = source().schema("ExyliaChatCosmetics");
        assertEquals("Tags — what is drawn before a name.", schema.getAsJsonArray("comments").get(0).getAsString());
        JsonObject tags = field(schema, "tags");
        assertEquals("One entry per tag.", tags.getAsJsonArray("comments").get(0).getAsString());
        JsonObject type = tags.getAsJsonObject("type");
        assertEquals("map", type.get("type").getAsString());
        JsonObject entry = type.getAsJsonObject("of");
        assertEquals("string", field(entry, "display").getAsJsonObject("type").get("type").getAsString());
        assertEquals("any", field(entry, "description").getAsJsonObject("type").get("type").getAsString());
        assertEquals("any", field(entry, "price").getAsJsonObject("type").get("type").getAsString());
        assertEquals("number", field(entry, "weight").getAsJsonObject("type").get("type").getAsString());
        assertEquals("integer", field(field(schema, "categories").getAsJsonObject("type").getAsJsonObject("of"), "priority")
                .getAsJsonObject("type").get("type").getAsString());
    }

    @Test
    void aWriteKeepsOrderTypesCommentsAndDottedKeys() throws IOException {
        YamlSource source = source();
        JsonObject values = source.values("ExyliaChatCosmetics");
        assertTrue(values.getAsJsonObject("tags").has("v1.2"), "a dotted key is one key");
        values.getAsJsonObject("tags").getAsJsonObject("star").addProperty("display", "&b★");
        JsonObject fresh = new JsonObject();
        fresh.addProperty("category", "basic");
        fresh.addProperty("display", "&c♥");
        values.getAsJsonObject("tags").add("heart", fresh);

        Source.Prepared prepared = source.prepare("ExyliaChatCosmetics", values);
        assertNull(prepared.problem());
        assertNotNull(prepared.write());
        prepared.write().run();

        String written = Files.readString(folder.resolve("cosmetics/tags.yml"));
        assertTrue(written.startsWith("# Tags — what is drawn before a name."), written);
        assertTrue(written.contains("# One entry per tag."), written);
        assertTrue(written.contains("display: '&b★'"), written);
        assertTrue(written.contains("price: 500\n"), "a whole number stays whole: " + written);
        assertTrue(written.contains("weight: 1.5"), written);
        assertTrue(written.indexOf("star:") < written.indexOf("crown:") && written.indexOf("v1.2:") < written.indexOf("heart:"), written);
        JsonObject reread = source.values("ExyliaChatCosmetics");
        assertEquals(values, reread);
    }

    @Test
    void theCheckerRefusesAndNothingIsWritten() throws IOException {
        YamlSource source = source();
        source.check(yaml -> yaml.getConfigurationSection("tags").getKeys(false).contains("broken")
                ? List.of("tags.broken: no display") : List.of());
        JsonObject values = source.values("ExyliaChatCosmetics");
        values.getAsJsonObject("tags").add("broken", new JsonObject());

        Source.Prepared prepared = source.prepare("ExyliaChatCosmetics", values);
        assertEquals("tags.broken: no display", prepared.problem());
        assertNull(prepared.write());
        assertEquals(CATALOG, Files.readString(folder.resolve("cosmetics/tags.yml")));
    }

    @Test
    void aListOfBlocksIsAListOfSections() throws IOException {
        Path file = folder.resolve("menu.yml");
        Files.writeString(file, "rows:\n- slot: 1\n  item: STONE\n- slot: 2\n  glow: true\nempty: []\n");
        JsonObject schema = new YamlSource(file.toFile(), "menu").schema("Test");
        JsonObject rows = field(schema, "rows").getAsJsonObject("type");
        assertEquals("list", rows.get("type").getAsString());
        JsonArray fields = rows.getAsJsonObject("of").getAsJsonArray("fields");
        assertEquals(3, fields.size());
        assertEquals("string", field(schema, "empty").getAsJsonObject("type").getAsJsonObject("of").get("type").getAsString());
    }
}
