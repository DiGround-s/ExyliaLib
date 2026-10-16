package net.exylia.lib.web.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.exylia.lib.config.internal.RecordJson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The browser draws a form from this alone, so every kind of key has to come out named. */
class RecordJsonTest {

    private static JsonObject field(JsonObject schema, String key) {
        for (var element : schema.getAsJsonArray("fields")) {
            JsonObject field = element.getAsJsonObject();
            if (field.get("key").getAsString().equals(key)) {
                return field;
            }
        }
        throw new AssertionError("No field " + key + " in " + schema);
    }

    @Test
    void everyKindOfKeyIsDescribed() {
        JsonObject schema = RecordJson.schema(Board.class, "Test");
        assertEquals("section", schema.get("type").getAsString());

        JsonObject refresh = field(schema, "refresh").getAsJsonObject("type");
        assertEquals("duration", refresh.get("type").getAsString());
        assertEquals("ticks", refresh.get("unit").getAsString());
        assertEquals("Ticks between refreshes.", field(schema, "refresh").getAsJsonArray("comments").get(0).getAsString());

        assertEquals("written", field(schema, "lasts").getAsJsonObject("type").get("unit").getAsString());

        JsonObject mode = field(schema, "mode").getAsJsonObject("type");
        assertEquals("enum", mode.get("type").getAsString());
        JsonArray values = mode.getAsJsonArray("values");
        assertEquals("fast", values.get(0).getAsString());
        assertEquals("slow-motion", values.get(1).getAsString());

        JsonObject lines = field(schema, "lines").getAsJsonObject("type");
        assertEquals("list", lines.get("type").getAsString());
        assertEquals("string", lines.getAsJsonObject("of").get("type").getAsString());

        // A record inside a list is described from its components, comments included.
        JsonObject pages = field(schema, "pages").getAsJsonObject("type");
        assertEquals("list", pages.get("type").getAsString());
        JsonObject page = pages.getAsJsonObject("of");
        assertEquals("section", page.get("type").getAsString());
        assertEquals("list", field(page, "click").getAsJsonObject("type").get("type").getAsString());

        // A map keeps its value side, which the public Schema drops.
        JsonObject entries = field(schema, "entries").getAsJsonObject("type");
        assertEquals("map", entries.get("type").getAsString());
        JsonObject entry = entries.getAsJsonObject("of");
        assertEquals("boolean", field(entry, "enabled").getAsJsonObject("type").get("type").getAsString());
        assertEquals("number", field(entry, "scale").getAsJsonObject("type").get("type").getAsString());
        JsonObject click = field(entry, "click").getAsJsonObject("type");
        assertEquals("section", click.get("type").getAsString());
        assertEquals("What a click runs.", field(click, "commands").getAsJsonArray("comments").get(0).getAsString());

        assertTrue(field(schema, "look").getAsJsonObject("type").has("fields"));
    }
}
