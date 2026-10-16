package net.exylia.lib.web.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.exylia.lib.config.ConfigIssue;
import net.exylia.lib.config.internal.RecordJson;
import net.exylia.lib.web.EntryStore;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

/**
 * Entries a plugin stores itself, edited as a map of records under the subject's id:
 * {@code {"mines": {"spawn": {...}, "deep": {...}}}}, so the browser lists them, adds and removes
 * them like any map of records.
 *
 * <p>Each entry is read through the config binder against what it is now (a new one against the
 * store's blank), then through the store's own check; one problem anywhere refuses the subject
 * whole. What is written is only what differs: an entry whose values did not change is not saved
 * again. Every deletion is held for {@code confirm}.
 */
final class EntrySource<R extends Record> implements Source {

    /** An entry id: what the browser may type as a map key. The store may be stricter. */
    static final Pattern ENTRY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final String key;
    private final Class<R> type;
    private final EntryStore<R> store;

    EntrySource(String key, Class<R> type, EntryStore<R> store) {
        try {
            type.getDeclaredConstructor();
        } catch (NoSuchMethodException missing) {
            throw new IllegalArgumentException(type.getName() + " has no no-arg constructor to hold an entry's defaults");
        }
        this.key = key;
        this.type = type;
        this.store = store;
    }

    @Override
    public String name() {
        return "";
    }

    @Override
    public boolean global() {
        return true;
    }

    @Override
    public JsonObject schema(String pluginName) {
        JsonObject map = new JsonObject();
        map.addProperty("type", "map");
        map.add("of", RecordJson.schema(type, pluginName));
        JsonObject field = new JsonObject();
        field.addProperty("key", key);
        field.add("comments", new JsonArray());
        field.add("type", map);
        JsonArray fields = new JsonArray();
        fields.add(field);
        JsonObject out = new JsonObject();
        out.addProperty("type", "section");
        out.add("comments", new JsonArray());
        out.add("fields", fields);
        return out;
    }

    @Override
    public JsonObject values(String pluginName) {
        JsonObject entries = new JsonObject();
        store.entries().forEach((id, value) -> entries.add(id, RecordJson.write(type, pluginName, value)));
        JsonObject out = new JsonObject();
        out.add(key, entries);
        return out;
    }

    @Override
    public Prepared prepare(String pluginName, JsonObject values) {
        JsonElement raw = values.get(key);
        if (raw == null || !raw.isJsonObject()) {
            return Prepared.refused(key + " is not a block of entries");
        }
        Map<String, R> current = store.entries();
        Map<String, R> saves = new LinkedHashMap<>();
        Map<String, Boolean> created = new LinkedHashMap<>();
        JsonObject after = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : raw.getAsJsonObject().entrySet()) {
            String id = entry.getKey();
            if (!ENTRY.matcher(id).matches()) {
                return Prepared.refused("'" + id + "' is not a valid name (letters, digits, - and _, up to 64)");
            }
            if (!entry.getValue().isJsonObject()) {
                return Prepared.refused(id + " is not a block of settings");
            }
            R before = current.get(id);
            boolean isNew = before == null;
            R base = isNew ? blank(id) : before;
            List<ConfigIssue> issues = new ArrayList<>();
            R decoded = RecordJson.read(type, pluginName, base, entry.getValue().getAsJsonObject(), key + "." + id, issues);
            for (ConfigIssue issue : issues) {
                if (issue.type() != ConfigIssue.Type.MISSING_KEY && issue.type() != ConfigIssue.Type.UNKNOWN_KEY) {
                    return Prepared.refused(id + ": " + issue.describe());
                }
            }
            List<String> problems = store.check(id, decoded, isNew);
            if (problems != null && !problems.isEmpty()) {
                return Prepared.refused(id + ": " + problems.get(0));
            }
            JsonObject written = RecordJson.write(type, pluginName, decoded);
            after.add(id, written);
            if (isNew || !written.equals(RecordJson.write(type, pluginName, before))) {
                saves.put(id, decoded);
                created.put(id, isNew);
            }
        }
        List<String> deletes = new ArrayList<>();
        List<String> holds = new ArrayList<>();
        for (String id : current.keySet()) {
            if (!after.has(id)) {
                deletes.add(id);
                holds.add(id);
            }
        }
        JsonObject result = new JsonObject();
        result.add(key, after);
        return new Prepared(null, result, holds, () -> {
            List<CompletableFuture<Void>> writes = new ArrayList<>();
            saves.forEach((id, value) -> writes.add(guard(() -> store.save(id, value, created.get(id)), id)));
            deletes.forEach(id -> writes.add(guard(() -> store.delete(id), id)));
            return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
        });
    }

    private R blank(String id) {
        R value = store.blank(id);
        if (value != null) {
            return value;
        }
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure instanceof InvocationTargetException invocation ? invocation.getCause() : failure;
            throw new IllegalStateException(type.getSimpleName() + " could not build its defaults: " + cause, cause);
        }
    }

    /** One write, with a store that throws instead of answering turned into a failed future named after the entry. */
    private static CompletableFuture<Void> guard(java.util.function.Supplier<CompletableFuture<Void>> write, String id) {
        CompletableFuture<Void> future;
        try {
            future = write.get();
        } catch (RuntimeException failure) {
            future = CompletableFuture.failedFuture(failure);
        }
        if (future == null) {
            return CompletableFuture.completedFuture(null);
        }
        return future.handle((ok, failure) -> {
            if (failure == null) {
                return null;
            }
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            String message = cause.getMessage() == null || cause.getMessage().isBlank() ? cause.getClass().getSimpleName() : cause.getMessage();
            throw new CompletionException(new IllegalStateException(id + ": " + message, cause));
        });
    }
}
