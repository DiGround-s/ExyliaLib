package net.exylia.lib.web.internal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The placeholders a snapshot resolves for the preview are the ones the template compiler would read. */
class SnapshotTest {

    @Test
    void everyPercentPairOnALineIsCollectedWhateverItHolds() {
        Set<String> tokens = new LinkedHashSet<>();
        Snapshot.collect(JsonParser.parseString("""
                {"lines": ["%player_name% and %pinger_online_172.18.0.1:40001%",
                           "%progress_bar_{bungee_survivalpvp}_c:&#ffc58f-_l:10_m:300_fullbar:SERVER LLENO!%",
                           "%eco_balance:comma|none% 50% off %"],
                 "nested": {"x": "%a%%b%"}}
                """), tokens);
        assertEquals(List.of("%player_name%", "%pinger_online_172.18.0.1:40001%",
                "%progress_bar_{bungee_survivalpvp}_c:&#ffc58f-_l:10_m:300_fullbar:SERVER LLENO!%",
                "%eco_balance:comma|none%", "% off %", "%a%", "%b%"), List.copyOf(tokens));
    }

    @Test
    void enumListsAreSentOncePerClassAndAClashStaysInline() {
        JsonObject first = JsonParser.parseString("""
                {"schema": {"type": "section", "fields": [
                  {"key": "block", "type": {"type": "enum", "class": "Material", "values": ["stone", "dirt"]}},
                  {"key": "blocks", "type": {"type": "list", "of": {"type": "enum", "class": "Material", "values": ["stone", "dirt"]}}},
                  {"key": "mode", "type": {"type": "enum", "values": ["a", "b"]}}]}}
                """).getAsJsonObject();
        JsonObject second = JsonParser.parseString("""
                {"schema": {"type": "section", "fields": [
                  {"key": "block", "type": {"type": "enum", "class": "Material", "values": ["stone", "dirt"]}},
                  {"key": "kind", "type": {"type": "enum", "class": "Type", "values": ["x"]}},
                  {"key": "other", "type": {"type": "enum", "class": "Type", "values": ["y"]}}]}}
                """).getAsJsonObject();

        JsonObject payload = Snapshot.payload(List.of(first, second), new JsonObject());

        JsonObject enums = payload.getAsJsonObject("enums");
        assertEquals(JsonParser.parseString("[\"stone\", \"dirt\"]"), enums.get("Material"));
        assertEquals(JsonParser.parseString("[\"x\"]"), enums.get("Type"));
        String text = payload.getAsJsonArray("subjects").toString();
        assertEquals(1, text.split("\"y\"", -1).length - 1, "a clashing Type keeps its own values inline");
        assertFalse(text.contains("\"stone\""), "Material values left the schema");
        assertTrue(text.contains("\"a\""), "an enum without a class keeps its values");
        assertTrue(payload.has("placeholders"));
    }
}
