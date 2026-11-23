package net.exylia.lib.web.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.exylia.lib.web.Problem;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * A folder of YAML files, one entry per file, edited as one map {@code {name: entry}}. Each file
 * is a {@link YamlSource} of its own: the same inference, the same comment-keeping write, the same
 * checker run on the text about to be written, once per entry.
 */
final class FolderSource implements Source {

    /** A new entry's name: what becomes a file name. A file already there keeps whatever name it has. */
    static final Pattern ENTRY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final File folder;
    private final String name;
    private volatile BiFunction<String, ConfigurationSection, List<Problem>> check = (entry, yaml) -> List.of();

    FolderSource(File folder, String name) {
        this.folder = folder;
        this.name = name;
    }

    void problems(BiFunction<String, ConfigurationSection, List<Problem>> check) {
        this.check = check;
    }

    File folder() {
        return folder;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public JsonObject schema(String pluginName) {
        JsonObject of = null;
        for (Map.Entry<String, YamlSource> entry : files().entrySet()) {
            JsonObject described = entry.getValue().schema(pluginName);
            of = of == null ? described : YamlSource.merge(of, described);
        }
        if (of == null) {
            of = new JsonObject();
            of.addProperty("type", "section");
            of.add("comments", new JsonArray());
            of.add("fields", new JsonArray());
        }
        JsonObject out = new JsonObject();
        out.addProperty("type", "map");
        out.add("comments", new JsonArray());
        out.add("of", of);
        return out;
    }

    @Override
    public JsonObject values(String pluginName) {
        JsonObject out = new JsonObject();
        files().forEach((entry, source) -> out.add(entry, source.values(pluginName)));
        return out;
    }

    @Override
    public Prepared prepare(String pluginName, JsonObject values) {
        Map<String, YamlSource> current = files();
        JsonObject after = new JsonObject();
        List<Supplier<CompletableFuture<Void>>> writes = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        String reason = null;
        for (Map.Entry<String, JsonElement> item : values.entrySet()) {
            String entry = item.getKey();
            YamlSource source = current.get(entry);
            String message = null;
            if (source == null && !ENTRY.matcher(entry).matches()) {
                message = "'" + entry + "' is not a valid name (letters, digits, - and _, up to 64)";
            } else if (!item.getValue().isJsonObject()) {
                message = entry + " is not a block of settings";
            }
            if (message != null) {
                reason = reason == null ? message : reason;
                problems.add(Problem.at(entry, message));
                continue;
            }
            boolean created = source == null;
            if (created) {
                source = new YamlSource(new File(folder, entry + ".yml"), name + "/" + entry);
            }
            source.problems(yaml -> check.apply(entry, yaml));
            Prepared prepared = source.prepare(pluginName, item.getValue().getAsJsonObject());
            if (prepared.problem() != null) {
                reason = reason == null ? entry + ": " + prepared.problem() : reason;
                for (Problem problem : prepared.problems()) {
                    String path = problem.path() == null || problem.path().isEmpty() ? entry : entry + "." + problem.path();
                    problems.add(Problem.at(path, problem.message()));
                }
                continue;
            }
            after.add(entry, prepared.after());
            if ((created || !prepared.after().equals(source.values(pluginName))) && prepared.write() != null) {
                writes.add(prepared.write());
            }
        }
        if (!problems.isEmpty()) {
            return Prepared.refused(reason, problems);
        }
        List<String> deletes = new ArrayList<>();
        for (Map.Entry<String, YamlSource> entry : current.entrySet()) {
            if (!after.has(entry.getKey())) {
                deletes.add(entry.getKey());
                File file = entry.getValue().disk();
                writes.add(() -> {
                    try {
                        Files.deleteIfExists(file.toPath());
                    } catch (IOException failure) {
                        throw new IllegalStateException(file.getName() + " could not be deleted: " + failure.getMessage(), failure);
                    }
                    return CompletableFuture.completedFuture(null);
                });
            }
        }
        return new Prepared(null, after, deletes, () -> {
            try {
                writes.forEach(Supplier::get);
                return CompletableFuture.completedFuture(null);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        });
    }

    /** Every {@code .yml} directly in the folder, by name, sorted. */
    private Map<String, YamlSource> files() {
        Map<String, YamlSource> out = new LinkedHashMap<>();
        File[] listed = folder.listFiles((dir, file) -> file.endsWith(".yml") && !file.startsWith("."));
        if (listed == null) {
            return out;
        }
        Arrays.sort(listed);
        for (File file : listed) {
            if (file.isFile()) {
                String entry = file.getName().substring(0, file.getName().length() - ".yml".length());
                out.put(entry, new YamlSource(file, name + "/" + entry));
            }
        }
        return out;
    }
}
