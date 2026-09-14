package net.exylia.lib.metrics.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers what decides a report before it reaches the network: which errors
 * are grouped and kept, which loader jars count as production, and the shape
 * and size of the body.
 */
class MetricsRuntimeTest {

    @Test
    void identicalErrorsAreCountedAndNewGroupsStopAtTheCap() {
        ErrorGroups groups = new ErrorGroups();
        Exception same = new IllegalStateException("boom");
        for (int i = 0; i < 3; i++) {
            groups.add("ExyliaCore", "1.0.0", "runtime", same);
        }
        for (int i = 0; i < ErrorGroups.MAX_GROUPS + 10; i++) {
            groups.add("ExyliaOther", "2.0.0", "enable", new RuntimeException(i + " distinct"));
        }
        // Past the cap, a known group keeps counting.
        groups.add("ExyliaCore", "1.0.0", "runtime", same);
        groups.add("ExyliaDev", "1.0.0", "runtime", same);

        JsonArray drained = groups.drain(Set.of("ExyliaCore", "ExyliaOther"));
        assertEquals(ErrorGroups.MAX_GROUPS, drained.size());
        JsonObject first = drained.get(0).getAsJsonObject();
        assertEquals("ExyliaCore", first.get("plugin").getAsString());
        assertEquals("java.lang.IllegalStateException", first.get("type").getAsString());
        assertEquals("boom", first.get("message").getAsString());
        assertEquals(4, first.get("count").getAsInt());
        assertTrue(first.get("stack").getAsString().contains("MetricsRuntimeTest"));
        assertTrue(groups.isEmpty());
    }

    @Test
    void aDatabaseThatIsNotAnsweringIsNotAnError() {
        ErrorGroups groups = new ErrorGroups();
        groups.add("ExyliaCore", "1.0.0", "runtime", new CompletionException(
                new SQLTransientConnectionException("Connection is not available, request timed out after 5000ms")));
        groups.add("ExyliaCore", "1.0.0", "runtime", new SQLException("Communications link failure", "08S01"));
        assertTrue(groups.isEmpty());

        groups.add("ExyliaCore", "1.0.0", "runtime", new SQLException("You have an error in your SQL syntax", "42000"));
        assertFalse(groups.isEmpty(), "a broken statement is still the plugin's bug");
    }

    @Test
    void devAndLocalJarsAreNotProduction() {
        ClassLoader loader = getClass().getClassLoader();
        assertEquals("dev", MetricsRuntime.branch(loader, "Spigot"));
        assertFalse(MetricsRuntime.production(MetricsRuntime.branch(loader, "Spigot")));
        assertTrue(MetricsRuntime.production(MetricsRuntime.branch(loader)));
        assertNull(MetricsRuntime.branch(loader, "Velocity"));
        // A jar that only sees the class through another loader is a local jar.
        try (URLClassLoader local = new URLClassLoader(new URL[0], loader)) {
            assertNull(MetricsRuntime.branch(local));
            assertFalse(MetricsRuntime.production(MetricsRuntime.branch(local)));
        } catch (java.io.IOException e) {
            fail(e);
        }
    }

    @Test
    void payloadHasTheContractShapeAndFitsTheBodyLimit() {
        ErrorGroups groups = new ErrorGroups();
        for (int i = 0; i < ErrorGroups.MAX_GROUPS; i++) {
            groups.add("ExyliaCore", "2.3.1", "runtime", new RuntimeException(i + "x".repeat(20_000)));
        }
        JsonObject facts = new JsonObject();
        facts.addProperty("software", "Paper");
        facts.addProperty("proxy", (String) null);
        Map<String, String> plugins = new LinkedHashMap<>();
        plugins.put("ExyliaCore", "2.3.1");
        plugins.put("ExyliaLib", "1.160.0");

        byte[] body = MetricsRuntime.encode(MetricsRuntime.payload(
                "3f2b0c1e-6a4d-4c38-9f0e-2a7d5c9b1e44", facts, "1.160.0", plugins,
                groups.drain(plugins.keySet())));

        assertTrue(body.length <= MetricsRuntime.MAX_BODY_BYTES);
        String json = new String(body, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"proxy\":null"));
        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("3f2b0c1e-6a4d-4c38-9f0e-2a7d5c9b1e44", parsed.get("server").getAsString());
        assertEquals("Paper", parsed.get("software").getAsString());
        assertEquals("1.160.0", parsed.get("lib").getAsString());
        assertEquals("ExyliaLib", parsed.getAsJsonArray("plugins").get(1).getAsJsonObject().get("name").getAsString());
        JsonArray errors = parsed.getAsJsonArray("errors");
        assertTrue(errors.size() > 0 && errors.size() < ErrorGroups.MAX_GROUPS);
        JsonObject error = errors.get(0).getAsJsonObject();
        assertEquals(ErrorGroups.MAX_MESSAGE, error.get("message").getAsString().length());
        assertEquals(ErrorGroups.MAX_STACK, error.get("stack").getAsString().length());
        assertTrue(MetricsRuntime.NAME.matcher("ExyliaSurvivalCore").matches());
        assertFalse(MetricsRuntime.NAME.matcher("Exylia").matches());
    }
}
