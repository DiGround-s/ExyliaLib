package net.exylia.lib.ragdoll;

import net.exylia.lib.ragdoll.internal.RagdollImport;
import net.exylia.lib.ragdoll.internal.RagdollTemplates;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Every {@code .json} and, since 1.401.0, {@code .bbmodel} file in
 * {@code plugins/<Plugin>/animations/}, read the first time a line of that
 * plugin asks for one and again whenever a file there changes, so a reload of
 * the plugin's own effects picks up a new animation without anything else being
 * told. Each is played exactly as the tool that made it plays it: see
 * {@link RagdollImport} for the formats. A Blockbench project plays the frames
 * its own Bedrock export would.
 *
 * <h2>What an animation is called</h2>
 * A file of one animation is called by its file name, without
 * {@code .animation.json}, {@code .json} or {@code .bbmodel}, and by its own
 * name. A Blockbench file or project of several is
 * called by each animation's own name, in full ({@code animation.player.wave})
 * or by its last part ({@code wave}). Names ignore case.
 *
 * @since 1.262.0
 */
public final class RagdollAnimations {

    private static final Map<String, Loaded> LOADED = new ConcurrentHashMap<>();

    /** What the folder reads: animation files and Blockbench projects. */
    private static final String[] SUFFIXES = {".json", ".bbmodel"};

    /** One plugin's animations, what each file offers, and the files they were read from. */
    private record Loaded(long signature, Map<String, RagdollAnimation> animations, Map<String, List<Clip>> files) {
    }

    /**
     * One animation a file offers, for a plugin to list.
     *
     * @param name      what {@code anim:} calls it: its own name, in lower case
     * @param answersTo every name {@code anim:} finds it by, in lower case
     * @param loops     whether it loops in its file
     * @param length    how long it lasts; a loop's whole length, entry included
     * @param loopFrom  where a loop goes back to; {@link Duration#ZERO} when it does not loop
     * @since 1.401.0
     */
    public record Clip(@NotNull String name, @NotNull List<String> answersTo, boolean loops,
                       @NotNull Duration length, @NotNull Duration loopFrom) {
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
        Loaded loaded = loaded(owner);
        return loaded == null ? null : loaded.animations().get(name.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * What every file in a plugin's {@code animations} folder offers, read again when
     * the folder changed, as {@link #find} does.
     *
     * <pre>{@code
     * RagdollAnimations.files("ExyliaDisplays").forEach((file, clips) -> ...);
     * }</pre>
     *
     * @param owner the plugin's name
     * @return each file's name, in order, with its clips in the file's order; empty
     *         without a folder
     * @since 1.401.0
     */
    public static @NotNull Map<String, List<Clip>> files(@NotNull String owner) {
        Loaded loaded = loaded(owner);
        return loaded == null ? Map.of() : loaded.files();
    }

    /**
     * What one file's text offers, read without a server.
     *
     * @param text     the file's text
     * @param file     its name, which names a file of one animation
     * @param problems where whatever could not be read is described
     * @return its clips, in the file's order
     * @since 1.401.0
     */
    public static @NotNull List<Clip> clips(@NotNull String text, @NotNull String file,
                                            @NotNull Consumer<String> problems) {
        return clips(RagdollImport.read(text, file, problems));
    }

    /**
     * Writes Blockbench template projects into a folder, each only when it is missing:
     * {@code player_classic.bbmodel} (4-pixel arms) and {@code player_slim.bbmodel}
     * (3-pixel arms), a player rig whose bones are named the way {@code anim:} reads
     * them ({@code body}, {@code torso}, {@code head}, {@code right_arm},
     * {@code left_arm}, {@code right_leg}, {@code left_leg} and a {@code _bend} bone
     * in every one but the head), pivoted where the vanilla model turns, with one
     * example clip, {@code wave}. Open one in Blockbench, animate, save, and the clips
     * play as they are.
     *
     * <pre>{@code
     * RagdollAnimations.writeTemplates(new File(getDataFolder(), "animations"));
     * }</pre>
     *
     * @param folder where to write them, made when it does not exist
     * @return the files written now; empty when both were there
     * @throws IOException when a file cannot be written
     * @since 1.401.0
     */
    public static @NotNull List<File> writeTemplates(@NotNull File folder) throws IOException {
        List<File> written = new ArrayList<>();
        for (boolean slim : new boolean[]{false, true}) {
            File file = new File(folder, slim ? "player_slim.bbmodel" : "player_classic.bbmodel");
            if (file.exists()) {
                continue;
            }
            Files.createDirectories(folder.toPath());
            Files.writeString(file.toPath(), RagdollTemplates.project(slim), StandardCharsets.UTF_8);
            written.add(file);
        }
        return written;
    }

    /** A plugin's folder, read again when it changed since the last look. */
    private static @Nullable Loaded loaded(String owner) {
        File folder = folder(owner);
        long signature = folder == null ? 0 : signature(folder);
        Loaded loaded = LOADED.get(owner);
        if (folder != null && (loaded == null || loaded.signature() != signature)) {
            loaded = load(folder, signature, Logger.getLogger(owner));
            LOADED.put(owner, loaded);
        }
        return loaded;
    }

    /** Each distinct animation once, under the first name it answers to. */
    private static List<Clip> clips(Map<String, RagdollAnimation> read) {
        Map<RagdollAnimation, List<String>> names = new IdentityHashMap<>();
        List<RagdollAnimation> order = new ArrayList<>();
        for (Map.Entry<String, RagdollAnimation> entry : read.entrySet()) {
            names.computeIfAbsent(entry.getValue(), animation -> {
                order.add(animation);
                return new ArrayList<>();
            }).add(entry.getKey());
        }
        List<Clip> clips = new ArrayList<>(order.size());
        for (RagdollAnimation animation : order) {
            List<String> all = names.get(animation);
            boolean loops = animation.loopFromMillis() >= 0;
            clips.add(new Clip(all.get(0), List.copyOf(all), loops, Duration.ofMillis(animation.durationMillis()),
                    Duration.ofMillis(Math.max(0, animation.loopFromMillis()))));
        }
        return List.copyOf(clips);
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
            return new Loaded(loaded == null ? 0 : loaded.signature(), animations,
                    loaded == null ? Map.of() : loaded.files());
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
        File[] files = folder.listFiles(RagdollAnimations::readable);
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

    private static boolean readable(File dir, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static Loaded load(File folder, long signature, Logger logger) {
        Map<String, RagdollAnimation> animations = new ConcurrentHashMap<>();
        Map<String, List<Clip>> offered = new LinkedHashMap<>();
        File[] files = folder.listFiles(RagdollAnimations::readable);
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                try {
                    String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                    Map<String, RagdollAnimation> read = RagdollImport.read(text, file.getName(),
                            problem -> logger.warning("animations/" + problem));
                    animations.putAll(read);
                    offered.put(file.getName(), clips(read));
                } catch (IOException unreadable) {
                    logger.warning("animations/" + file.getName() + " could not be read: "
                            + unreadable.getMessage());
                }
            }
        }
        return new Loaded(signature, animations, Collections.unmodifiableMap(offered));
    }
}
