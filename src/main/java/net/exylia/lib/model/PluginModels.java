package net.exylia.lib.model;

import net.exylia.lib.model.internal.ModelFolder;
import net.exylia.lib.model.internal.ModelRuntime;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * One plugin's models: the ones in its {@code models} folder, and the ones it
 * put on carriers.
 *
 * <pre>{@code
 * PluginModels models = Models.of(this);
 *
 * // On the mob's thread, as it spawns:
 * ModelInstance live = models.attach(mob, "stone_golem", ModelOptions.DEFAULT.withScale(1.3));
 * }</pre>
 *
 * <p>The folder is {@code plugins/<Plugin>/models/}: every {@code .bbmodel}
 * in it is a model named after its file, read again whenever it changes, so
 * saving in Blockbench is the whole reload.
 *
 * @since 1.317.0
 */
public final class PluginModels {

    private final Plugin plugin;
    private final ModelFolder folder;

    PluginModels(@NotNull Plugin plugin) {
        this.plugin = plugin;
        File models = new File(plugin.getDataFolder(), "models");
        this.folder = new ModelFolder(models, problem -> plugin.getLogger().warning("models/" + problem));
    }

    /** The folder its models are read from; it may not exist yet. */
    public @NotNull File folder() {
        return folder.folder();
    }

    /** Every model in its folder, by name, sorted. */
    public @NotNull Collection<String> names() {
        return new TreeSet<>(folder.models().keySet());
    }

    /** Every model in its folder. */
    public @NotNull Map<String, Model> models() {
        return folder.models();
    }

    /**
     * A model from its folder.
     *
     * @param name the file's name without {@code .bbmodel}, in any case
     * @return the model, or {@code null} when there is none by that name
     */
    public @Nullable Model model(@NotNull String name) {
        return folder.model(name);
    }

    /**
     * Reads the folder again now, rather than when it is next asked.
     *
     * @return how many models it holds
     */
    public int reload() {
        return folder.refresh(true);
    }

    /**
     * Puts a model on a carrier.
     *
     * <p>The carrier is hidden &mdash; body, armour and name &mdash; and the model
     * drawn in its place, following it, turning with it, walking when it
     * walks. A carrier that already wears a model takes this one instead.
     * Call it on the carrier's thread: as it spawns, or in a task on it.
     *
     * @param carrier what wears it
     * @param model   the model's name in this plugin's folder
     * @param options how it is worn
     * @return the live model, or {@code null} when there is no such model or the
     *         server cannot draw models
     */
    public @Nullable ModelInstance attach(@NotNull LivingEntity carrier, @NotNull String model,
                                          @NotNull ModelOptions options) {
        Model found = model(model);
        return found == null ? null : attach(carrier, found, options);
    }

    /**
     * Puts a model already read on a carrier; see {@link #attach(LivingEntity, String, ModelOptions)}.
     *
     * @return the live model, or {@code null} when the server cannot draw models
     */
    public @Nullable ModelInstance attach(@NotNull LivingEntity carrier, @NotNull Model model,
                                          @NotNull ModelOptions options) {
        return ModelRuntime.attach(plugin, carrier, model, options);
    }

    /** The model a carrier wears, whoever put it on, or {@code null}. */
    public @Nullable ModelInstance worn(@NotNull Entity carrier) {
        return ModelRuntime.of(carrier);
    }

    /** How many models it has on, carriers and dying ones. */
    public int active() {
        return ModelRuntime.active(plugin.getName());
    }

    /** Takes off every model it put on. Done for it when the plugin is disabled. */
    public void removeAll() {
        ModelRuntime.release(plugin.getName());
    }

    /** Whether this server can draw models: PacketEvents is installed. */
    public boolean isSupported() {
        return ModelRuntime.isSupported();
    }

    /** What could not be read from one model's files, or nothing when it has no problems or does not exist. */
    public @NotNull List<String> problems(@NotNull String name) {
        Model found = model(name);
        return found == null ? List.of() : found.problems();
    }
}
