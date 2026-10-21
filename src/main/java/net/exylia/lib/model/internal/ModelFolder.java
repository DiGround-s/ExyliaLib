package net.exylia.lib.model.internal;

import net.exylia.lib.model.Model;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One plugin's {@code models} folder, read when it changes.
 *
 * <p>Every {@code .bbmodel} is a model named after its file. A Bedrock or
 * GeckoLib animation file beside it named after the model &mdash;
 * {@code golem.animation.json}, {@code golem.json} &mdash; adds its animations
 * to it. The folder is looked at again at most once a second, by its files'
 * names, sizes and times, so editing a model in Blockbench and saving it is
 * the whole reload.
 */
@ApiStatus.Internal
public final class ModelFolder {

    private static final long LOOK_EVERY_MS = 1_000;

    private final File folder;
    private final Consumer<String> problems;
    private volatile Map<String, Model> models = Map.of();
    private volatile long signature = Long.MIN_VALUE;
    private volatile long lookedAt;

    public ModelFolder(File folder, Consumer<String> problems) {
        this.folder = folder;
        this.problems = problems;
    }

    public File folder() {
        return folder;
    }

    /** Every model, reading the folder again when it changed. */
    public Map<String, Model> models() {
        refresh(false);
        return models;
    }

    public @Nullable Model model(String name) {
        return models().get(name.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Reads the folder again now.
     *
     * @param force whether to read it even when nothing seems to have changed
     * @return how many models it holds
     */
    public synchronized int refresh(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lookedAt < LOOK_EVERY_MS) {
            return models.size();
        }
        lookedAt = now;
        long current = signature();
        if (!force && current == signature) {
            return models.size();
        }
        signature = current;
        models = Collections.unmodifiableMap(load());
        return models.size();
    }

    private File[] files() {
        File[] files = folder.listFiles((dir, name) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".bbmodel") || lower.endsWith(".json");
        });
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files);
        return files;
    }

    private long signature() {
        long signature = 17;
        for (File file : files()) {
            signature = signature * 31 + file.getName().hashCode();
            signature = signature * 31 + file.lastModified();
            signature = signature * 31 + file.length();
        }
        return signature;
    }

    private Map<String, Model> load() {
        Map<String, Model> loaded = new LinkedHashMap<>();
        File[] files = files();
        for (File file : files) {
            String lower = file.getName().toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".bbmodel")) {
                continue;
            }
            String name = lower.substring(0, lower.length() - ".bbmodel".length());
            List<String> said = new ArrayList<>();
            Consumer<String> report = problem -> {
                said.add(problem);
                problems.accept(problem);
            };
            String text = text(file, report);
            if (text == null) {
                continue;
            }
            BbModelReader.Read read = BbModelReader.read(text, file.getName(), true, report);
            if (read == null) {
                continue;
            }
            Map<String, Clip> clips = new LinkedHashMap<>(read.clips());
            for (File other : files) {
                String otherName = other.getName().toLowerCase(Locale.ROOT);
                if (otherName.endsWith(".json") && (otherName.startsWith(name + ".") || otherName.equals(name + ".json"))) {
                    String json = text(other, report);
                    if (json != null) {
                        clips.putAll(BbModelReader.animations(json, other.getName(), read.rig(), report));
                    }
                }
            }
            loaded.put(name, new Model(name, read.rig(), clips, said));
        }
        return loaded;
    }

    private static @Nullable String text(File file, Consumer<String> report) {
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            report.accept(file.getName() + " could not be read: " + unreadable.getMessage());
            return null;
        }
    }
}
