package net.exylia.lib.web.internal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.exylia.lib.config.ConfigIssue;
import net.exylia.lib.config.internal.RecordJson;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * A subject's record as JSON and back, plus the fingerprint that tells two snapshots apart.
 *
 * <p>The conversion itself is {@link RecordJson}, next to the binder; this is what the runtime
 * asks of it, in the runtime's words.
 */
final class ValueCodec {

    private static final Gson CANONICAL = new GsonBuilder().serializeNulls().create();

    private ValueCodec() {
    }

    /** What one subject decoded to: the record to write, or the issues that stop it. */
    record Decoded<T>(T value, List<ConfigIssue> issues) {

        /** A key left out keeps its value and an unknown one is dropped; anything else refuses. */
        boolean rejected() {
            return !problems().isEmpty();
        }

        List<ConfigIssue> problems() {
            List<ConfigIssue> out = new ArrayList<>();
            for (ConfigIssue issue : issues) {
                if (issue.type() != ConfigIssue.Type.MISSING_KEY && issue.type() != ConfigIssue.Type.UNKNOWN_KEY) {
                    out.add(issue);
                }
            }
            return out;
        }
    }

    /** The record's values, keyed as the file writes them. */
    static JsonObject toJson(Class<?> type, String pluginName, Object record) {
        return RecordJson.write(type, pluginName, record);
    }

    /**
     * A record from the browser's values.
     *
     * @param current what the file holds now: the fallback for a key the change leaves out
     * @param file    the file's name, for the issues
     */
    static <T> Decoded<T> fromJson(Class<?> type, String pluginName, T current, JsonObject values, String file) {
        List<ConfigIssue> issues = new ArrayList<>();
        T value = RecordJson.read(type, pluginName, current, values, file, issues);
        return new Decoded<>(value, issues);
    }

    /** A short, stable fingerprint of some values, so two snapshots can be told apart. */
    static String hash(JsonElement values) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(CANONICAL.toJson(values).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("The JVM has no SHA-256", impossible);
        }
    }
}
