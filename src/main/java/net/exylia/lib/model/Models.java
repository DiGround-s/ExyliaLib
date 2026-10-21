package net.exylia.lib.model;

import net.exylia.lib.model.internal.BbModelReader;
import net.exylia.lib.model.internal.ModelRuntime;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Models made in Blockbench, worn by living entities, drawn with nothing but
 * the blocks and items every client already has: no resource pack, ever.
 *
 * <pre>{@code
 * PluginModels models = Models.of(this);
 * ModelInstance live = models.attach(zombie, "stone_golem", ModelOptions.DEFAULT);
 * live.play(ModelState.ATTACK);
 * }</pre>
 *
 * <p>A model is a {@code .bbmodel} in the plugin's {@code models} folder. Its
 * groups are bones, its cubes are block, item or head displays, its animations
 * play as they do in Blockbench; the carrier underneath is hidden and keeps
 * being the mob, with its AI, its health and its hitbox. See
 * {@code docs/models.md}.
 *
 * @since 1.317.0
 */
public final class Models {

    private static final Map<String, PluginModels> BY_PLUGIN = new ConcurrentHashMap<>();

    private Models() {
        throw new AssertionError("No instances.");
    }

    /**
     * This plugin's view of the module.
     *
     * @param plugin the plugin
     * @return its view, the same instance every time
     */
    public static @NotNull PluginModels of(@NotNull Plugin plugin) {
        // A plugin that wears textured heads keeps the textures in its own database, as ragdolls do.
        net.exylia.lib.ragdoll.internal.RagdollTextures.register(plugin);
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), name -> new PluginModels(plugin));
    }

    /** Whether this server can draw models at all. */
    public static boolean isSupported() {
        return ModelRuntime.isSupported();
    }

    /**
     * Reads a Blockbench project without a server: for tools and tests.
     *
     * @param json     the project's text
     * @param name     the model's name
     * @param problems where whatever could not be read is described
     * @return the model, or {@code null} when the text is not a project
     */
    public static @Nullable Model read(@NotNull String json, @NotNull String name,
                                       @NotNull Consumer<String> problems) {
        List<String> said = new ArrayList<>();
        BbModelReader.Read read = BbModelReader.read(json, name + ".bbmodel", true, problem -> {
            said.add(problem);
            problems.accept(problem);
        });
        return read == null ? null : new Model(name, read.rig(), read.clips(), said);
    }

    /** Takes off one plugin's models and forgets its view. Called by the library when it is disabled. */
    public static void release(@NotNull String pluginName) {
        ModelRuntime.release(pluginName);
        BY_PLUGIN.remove(pluginName);
    }

    /** Takes every model off, on shutdown. */
    public static void releaseAll() {
        ModelRuntime.releaseAll();
        BY_PLUGIN.clear();
    }
}
