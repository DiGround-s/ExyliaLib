package net.exylia.lib.web.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What changed between two value trees, leaf by leaf, and which of those changes need a
 * second look before they are written.
 *
 * <p>Paths read as the YAML would: {@code holograms.spawn.click.commands[0]}. A pattern from
 * {@link net.exylia.lib.web.Subject#sensitive} is matched against the path's keys with list
 * indices dropped, {@code *} standing for any one key, and as a prefix: it names a branch,
 * and everything under the branch is covered.
 */
final class Diff {

    /** The click a line is limited to, which says nothing about what the line runs. */
    private static final Pattern CLICK_PREFIX =
            Pattern.compile("^(?:left|right|middle|shift|shift_left|shift_right)\\s*:\\s*", Pattern.CASE_INSENSITIVE);

    /** A line that runs with the server's own authority rather than the player's. */
    private static final Pattern PRIVILEGED = Pattern.compile(
            "^(?:(?:console|console-proxy|console_proxy|op)\\s*:|\\[\\s*(?:console|console-proxy|console_proxy|op)\\s*])",
            Pattern.CASE_INSENSITIVE);

    private Diff() {
    }

    /** One leaf that differs; {@code null} on a side means the leaf is absent there. */
    record Change(String path, @Nullable JsonElement before, @Nullable JsonElement after) {

        /** Whether the new value, or any text inside it, starts a privileged command. */
        boolean privileged() {
            return Diff.privileged(after);
        }
    }

    static List<Change> of(@Nullable JsonElement before, @Nullable JsonElement after) {
        List<Change> out = new ArrayList<>();
        walk("", before, after, out);
        return out;
    }

    private static void walk(String path, @Nullable JsonElement before, @Nullable JsonElement after, List<Change> out) {
        if (before != null && before.isJsonNull()) {
            before = null;
        }
        if (after != null && after.isJsonNull()) {
            after = null;
        }
        if (Objects.equals(before, after)) {
            return;
        }
        // A block added or removed whole is walked as if the other side were empty, so a
        // sensitive branch inside a brand-new entry is still seen leaf by leaf.
        if (isObject(before) && (after == null || isObject(after)) || isObject(after) && before == null) {
            JsonObject left = before == null ? new JsonObject() : before.getAsJsonObject();
            JsonObject right = after == null ? new JsonObject() : after.getAsJsonObject();
            Set<String> keys = new LinkedHashSet<>(left.keySet());
            keys.addAll(right.keySet());
            for (String key : keys) {
                walk(path.isEmpty() ? key : path + "." + key, left.get(key), right.get(key), out);
            }
            return;
        }
        if (isArray(before) && (after == null || isArray(after)) || isArray(after) && before == null) {
            JsonArray left = before == null ? new JsonArray() : before.getAsJsonArray();
            JsonArray right = after == null ? new JsonArray() : after.getAsJsonArray();
            int length = Math.max(left.size(), right.size());
            for (int i = 0; i < length; i++) {
                walk(path + "[" + i + "]", i < left.size() ? left.get(i) : null,
                        i < right.size() ? right.get(i) : null, out);
            }
            return;
        }
        out.add(new Change(path, before, after));
    }

    private static boolean isObject(@Nullable JsonElement element) {
        return element != null && element.isJsonObject();
    }

    private static boolean isArray(@Nullable JsonElement element) {
        return element != null && element.isJsonArray();
    }

    /** The keys of a path, list indices dropped. */
    static List<String> keys(String path) {
        List<String> out = new ArrayList<>();
        for (String part : path.split("\\.")) {
            int bracket = part.indexOf('[');
            String key = bracket < 0 ? part : part.substring(0, bracket);
            if (!key.isEmpty()) {
                out.add(key);
            }
        }
        return out;
    }

    /** Whether a pattern names this path or a branch above it. */
    static boolean matches(String path, String pattern) {
        List<String> keys = keys(path);
        List<String> wanted = keys(pattern);
        if (wanted.isEmpty() || wanted.size() > keys.size()) {
            return false;
        }
        for (int i = 0; i < wanted.size(); i++) {
            String want = wanted.get(i);
            if (!want.equals("*") && !want.equals(keys.get(i))) {
                return false;
            }
        }
        return true;
    }

    static boolean matchesAny(String path, List<String> patterns) {
        for (String pattern : patterns) {
            if (matches(path, pattern)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a value, or any string inside it, starts a command run with server authority. */
    static boolean privileged(@Nullable JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return false;
        }
        if (value.isJsonPrimitive()) {
            if (!value.getAsJsonPrimitive().isString()) {
                return false;
            }
            String line = CLICK_PREFIX.matcher(value.getAsString().trim()).replaceFirst("");
            return PRIVILEGED.matcher(line).find();
        }
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                if (privileged(item)) {
                    return true;
                }
            }
            return false;
        }
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            if (privileged(entry.getValue())) {
                return true;
            }
        }
        return false;
    }
}
