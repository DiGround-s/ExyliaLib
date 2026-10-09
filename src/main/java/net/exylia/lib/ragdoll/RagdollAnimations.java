package net.exylia.lib.ragdoll;

import net.exylia.lib.ragdoll.internal.RagdollImport;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Animations made in Blockbench or Emotecraft, read from a plugin's
 * {@code animations} folder.
 *
 * <pre>{@code
 * - '[RAGDOLL] {victim};anim:wave;follow:1'
 * }</pre>
 *
 * <h2>Where they come from</h2>
 * Every {@code .json} file in {@code plugins/<Plugin>/animations/}, read the
 * first time a line of that plugin asks for one and again whenever a file there
 * changes, so a reload of the plugin's own effects picks up a new animation
 * without anything else being told. Each is played exactly as the tool that
 * made it plays it: see {@link RagdollImport} for the formats.
 *
 * <h2>What an animation is called</h2>
 * A file of one animation is called by its file name, without
 * {@code .animation.json} or {@code .json}. A Blockbench file of several is
 * called by each animation's own name, in full ({@code animation.player.wave})
 * or by its last part ({@code wave}). Names ignore case.
 *
 * @since 1.262.0
 */
public final class RagdollAnimations {

    private static final Map<String, Loaded> LOADED = new ConcurrentHashMap<>();

    /** One plugin's animations, and the files they were read from. */
    private record Loaded(long signature, Map<String, RagdollAnimation> animations) {
    }

    private RagdollAnimations() {
    }

    /**
     * An animation a plugin has in its {@code animations} folder.
     *
     * @param owner the plugin's name
     * @param name  the animation's name, in any case
     * @return the animation, or {@code null} when there is none by that name
     */
    public static @Nullable RagdollAnimation find(@NotNull String owner, @NotNull String name) {
        File folder = folder(owner);
        long signature = folder == null ? 0 : signature(folder);
        Loaded loaded = LOADED.get(owner);
        if (folder != null && (loaded == null || loaded.signature() != signature)) {
            loaded = new Loaded(signature, load(folder, Logger.getLogger(owner)));
            LOADED.put(owner, loaded);
        }
        return loaded == null ? null : loaded.animations().get(name.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Every animation in one file's text, read without a server.
     *
     * @param json     the file's text
     * @param file     its name, which names a file of one animation
     * @param problems where whatever could not be read is described
     * @return each animation by every name it answers to, in lower case
     */
    public static @NotNull Map<String, RagdollAnimation> read(@NotNull String json, @NotNull String file,
                                                              @NotNull Consumer<String> problems) {
        return RagdollImport.read(json, file, problems);
    }

    /** Puts an animation where {@link #find} looks, for whoever has no folder to read: a test. */
    @ApiStatus.Internal
    public static void register(@NotNull String owner, @NotNull String name, @NotNull RagdollAnimation animation) {
        LOADED.compute(owner, (ignored, loaded) -> {
            Map<String, RagdollAnimation> animations = new ConcurrentHashMap<>();
            if (loaded != null) {
                animations.putAll(loaded.animations());
            }
            animations.put(name.toLowerCase(Locale.ROOT), animation);
            return new Loaded(loaded == null ? 0 : loaded.signature(), animations);
        });
    }

    private static @Nullable File folder(String owner) {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(owner);
            return plugin == null ? null : new File(plugin.getDataFolder(), "animations");
        } catch (RuntimeException | LinkageError noServer) {
            return null;
        }
    }

    /** What the folder looks like: which files, how big and when each last changed. */
    private static long signature(File folder) {
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));
        if (files == null) {
            return 0;
        }
        Arrays.sort(files);
        long signature = 17;
        for (File file : files) {
            signature = signature * 31 + file.getName().hashCode();
            signature = signature * 31 + file.lastModified();
            signature = signature * 31 + file.length();
        }
        return signature;
    }

    private static Map<String, RagdollAnimation> load(File folder, Logger logger) {
        Map<String, RagdollAnimation> animations = new ConcurrentHashMap<>();
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));
        if (files == null) {
            return animations;
        }
        Arrays.sort(files);
        for (File file : files) {
            try {
                String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                animations.putAll(RagdollImport.read(text, file.getName(),
                        problem -> logger.warning("animations/" + problem)));
            } catch (IOException unreadable) {
                logger.warning("animations/" + file.getName() + " could not be read: " + unreadable.getMessage());
            }
        }
        return animations;
    }
}
