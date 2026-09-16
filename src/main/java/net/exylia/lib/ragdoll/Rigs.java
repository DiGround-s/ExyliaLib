package net.exylia.lib.ragdoll;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Every prop the server knows, by name.
 *
 * <p>Server-wide rather than per plugin, and deliberately: a top hat is a top
 * hat whether a kill effect is wearing it or an emote is. One file of objects,
 * written once, reachable from every {@code [RAGDOLL]} line on the server.
 *
 * <p>Read from {@code ExyliaLib/rigs.yml}. What ships in it is four examples of
 * the three words rather than a library to pick from: the point is that a server
 * writes its own.
 *
 * @since 1.173.0
 */
public final class Rigs {

    private static final Map<String, RagdollProp> BY_ID = new ConcurrentHashMap<>();

    private Rigs() {
    }

    /** The prop of that name, or {@code null} when nothing declares it. */
    public static @Nullable RagdollProp get(@Nullable String id) {
        return id == null ? null : BY_ID.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /** Every name, for a command's suggestions. */
    public static @NotNull List<String> ids() {
        return List.copyOf(BY_ID.keySet());
    }

    public static int count() {
        return BY_ID.size();
    }

    /**
     * Replaces the whole library with a fresh read of the file.
     *
     * <p>Whole rather than patched, because that is what a reload has to do
     * anyway: a prop can be renamed, deleted or rebuilt between one read and the
     * next, and working out which is more expensive than reading a handful of
     * sections.
     *
     * @param section  the {@code rigs} section of the file
     * @param problems where each thing that could not be read is described
     */
    public static void replace(@Nullable ConfigurationSection section,
                               @NotNull BiConsumer<String, String> problems) {
        Map<String, RagdollProp> fresh = new ConcurrentHashMap<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(key);
                String id = key.trim().toLowerCase(Locale.ROOT);
                if (entry == null) {
                    problems.accept(id, "is not a section");
                    continue;
                }
                RagdollJoint joint = RagdollJoint.of(entry.getString("joint", "head"));
                if (joint == null) {
                    problems.accept(id, "is tied to \"" + entry.getString("joint")
                            + "\", which is not a joint");
                    continue;
                }
                float[] at = offset(entry.getString("at", "0,0,0"), id, problems);
                List<String> lines = entry.getStringList("blocks");
                if (lines.isEmpty()) {
                    problems.accept(id, "is made of no blocks");
                    continue;
                }
                RagdollProp prop = RagdollProp.parse(id, joint, at, lines,
                        problem -> problems.accept(id, problem));
                if (prop.isEmpty()) {
                    problems.accept(id, "read as nothing at all");
                    continue;
                }
                fresh.put(id, prop);
            }
        }
        BY_ID.clear();
        BY_ID.putAll(fresh);
    }

    private static float[] offset(String written, String id, BiConsumer<String, String> problems) {
        String[] parts = written.split(",");
        float[] at = new float[3];
        for (int index = 0; index < 3 && index < parts.length; index++) {
            try {
                at[index] = Float.parseFloat(parts[index].trim());
            } catch (NumberFormatException malformed) {
                problems.accept(id, "\"" + written + "\" is not an x,y,z offset");
                return new float[3];
            }
        }
        return at;
    }
}
