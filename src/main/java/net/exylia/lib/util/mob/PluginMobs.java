package net.exylia.lib.util.mob;

import net.exylia.lib.input.FormField;
import net.exylia.lib.input.FormKey;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.editor.EditorForm;
import net.exylia.lib.util.editor.Editors;
import net.exylia.lib.util.editor.ListEditor;
import net.exylia.lib.util.mob.internal.MobEngine;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * One plugin's custom mobs.
 *
 * <pre>{@code
 * PluginMobs mobs = Mobs.of(this);
 * templates.forEach(mobs::register);
 *
 * if (mobs.count("frost_knight") < 6) {
 *     mobs.spawn(mobs.template("frost_knight"), spot);
 * }
 *
 * mobs.onDeath(death -> {
 *     if (death.killer() == null || death.playerShare() < 0.5) return;
 *     Rewards.of(this).give(death.killer(), death.template().rewards());
 * });
 * }</pre>
 *
 * <h2>Threads</h2>
 * The registry, {@link #live}, {@link #count} and {@link #onDeath} are safe
 * from any thread. {@link #spawn} is too: it moves onto the location's thread.
 * {@link #templateOf} and {@link #isMob} read the entity and belong on its
 * thread. Death handlers run on the dying mob's thread, inside the death event.
 *
 * <h2>Lifecycle</h2>
 * Listeners and timers belong to the plugin. When it is disabled, its live
 * mobs are removed and its templates forgotten. Every mob runs one entity timer,
 * once a second, for its interval skills and its hunting; that is also how the
 * runtime learns a mob is gone without a Paper-only event, so {@link #live} and
 * {@link #count} can still include a mob for up to a second after it was
 * unloaded or removed by something else.
 *
 * <h2>Palette reloads</h2>
 * Not applicable: nothing derived from the palette is kept. A name is rendered
 * onto the entity when it spawns and each time its health changes.
 *
 * @since 1.192.0
 */
public final class PluginMobs {

    private final Plugin plugin;
    private final MobEngine engine;

    PluginMobs(@NotNull Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.engine = new MobEngine(plugin);
        engine.start();
    }

    /** The plugin these mobs belong to. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    // ---------------------------------------------------------------- registry

    /**
     * Makes a template spawnable, replacing one with the same id.
     *
     * <p>Mobs already alive keep the template they were spawned from; the new
     * one applies to the next spawn. Call it again for every template on reload.
     *
     * @param template the template
     * @return this
     */
    public @NotNull PluginMobs register(@NotNull MobTemplate template) {
        engine.register(Objects.requireNonNull(template, "template"));
        return this;
    }

    /**
     * Forgets a template. Mobs alive from it stay until they die or unload.
     *
     * @param id the template id
     * @return whether there was one
     */
    public boolean unregister(@NotNull String id) {
        return engine.unregister(id);
    }

    /**
     * A registered template.
     *
     * @param id the template id
     * @return the template, or {@code null}
     */
    public @Nullable MobTemplate template(@NotNull String id) {
        return engine.template(id);
    }

    /** Every registered template, in no particular order. */
    public @NotNull Collection<MobTemplate> templates() {
        return engine.templates();
    }

    // ------------------------------------------------------------------- spawn

    /**
     * Spawns a mob from a template.
     *
     * <p>Runs on the location's thread: inline when the caller already owns
     * it, on its next tick otherwise. The template does not have to be
     * registered, although a summon skill only finds registered ones.
     *
     * <p>The mob is not saved with its chunk and does not despawn with
     * distance; it unloads with its chunk and is not coming back. It carries
     * {@code exylialib:mob} = {@code <plugin>:<template id>}.
     *
     * @param template what to spawn
     * @param location where
     * @return the mob, completed on the location's thread; failed with
     *         {@link IllegalArgumentException} for a type that is not a living
     *         entity, or {@link IllegalStateException} when another plugin
     *         cancelled the spawn or these mobs have been released
     */
    public @NotNull CompletableFuture<LivingEntity> spawn(@NotNull MobTemplate template,
                                                          @NotNull Location location) {
        Objects.requireNonNull(template, "template");
        Location at = Objects.requireNonNull(location, "location").clone();
        CompletableFuture<LivingEntity> spawned = new CompletableFuture<>();
        if (at.getWorld() == null) {
            spawned.completeExceptionally(new IllegalArgumentException("the location has no world"));
            return spawned;
        }
        Runnable work = () -> {
            try {
                spawned.complete(engine.spawnHere(template, at, false));
            } catch (RuntimeException | LinkageError failure) {
                spawned.completeExceptionally(failure);
            }
        };
        TaskScheduler tasks = Tasks.of(plugin);
        if (tasks.isOwnedBy(at)) {
            work.run();
        } else if (tasks.runAtLocation(at, work).isCancelled()) {
            // A plugin on its way down schedules nothing; a caller left waiting
            // on a future nothing will complete is worse than an answer.
            spawned.completeExceptionally(new IllegalStateException(plugin.getName() + " is not running"));
        }
        return spawned;
    }

    // ------------------------------------------------------------------ lookup

    /**
     * Whether an entity is one of this plugin's custom mobs, alive in this run or not.
     *
     * @param entity the entity
     * @return whether it carries this plugin's tag
     */
    public boolean isMob(@NotNull Entity entity) {
        return engine.isMob(entity);
    }

    /**
     * The template a mob was spawned from.
     *
     * @param entity the entity
     * @return its template, or {@code null} for anything that is not this plugin's mob
     */
    public @Nullable MobTemplate templateOf(@NotNull Entity entity) {
        return engine.templateOf(entity);
    }

    /** This plugin's mobs alive right now. */
    public @NotNull List<LivingEntity> live() {
        return engine.live();
    }

    /**
     * How many mobs of a template are alive — what a spawner checks its cap against.
     *
     * @param templateId the template id
     * @return the count, summoned minions included
     */
    public int count(@NotNull String templateId) {
        return engine.count(templateId);
    }

    /**
     * Listens for this plugin's mobs dying.
     *
     * <p>The library has already applied the template's drop switches and
     * added its experience; what the killer is owed is the handler's call.
     * A handler that throws is reported and does not stop the others.
     *
     * @param handler told each death, on the mob's thread
     * @return this
     */
    public @NotNull PluginMobs onDeath(@NotNull Consumer<MobDeath> handler) {
        engine.onDeath(Objects.requireNonNull(handler, "handler"));
        return this;
    }

    // ----------------------------------------------------------------- editors

    /**
     * A screen for editing a template's skills.
     *
     * <pre>{@code
     * mobs.skillsEditor(template.skills())
     *     .title("{primary}&lSKILLS {letters_black}» {highlight}" + template.id())
     *     .onSave(skills -> store.save(template.withSkills(skills)))
     *     .open(player);
     * }</pre>
     *
     * <p>Adding asks what the skill does, then when, then opens a form with
     * only the fields that type reads.
     *
     * @param skills what is being edited; copied, never held
     * @return the editor, ready to open
     */
    public @NotNull ListEditor<MobSkill> skillsEditor(@NotNull List<MobSkill> skills) {
        return Editors.of(plugin).list(new MobSkillDescriptor(plugin), MobSkill.class, skills);
    }

    /**
     * One form with a field per attribute in {@link MobTemplate#ATTRIBUTES},
     * prefilled; a blank field leaves the type's vanilla value.
     *
     * <p>Keys outside that list are kept as they are.
     *
     * @param viewer     who is editing
     * @param attributes the values as they stand
     * @return the edited values, or nothing when the viewer backed out
     */
    public @NotNull CompletionStage<Optional<Map<String, Double>>> attributesEditor(
            @NotNull Player viewer, @NotNull Map<String, Double> attributes) {
        EditorForm form = EditorForm.of(plugin, viewer, "{primary}&lATTRIBUTES");
        for (String key : MobTemplate.ATTRIBUTES) {
            FormKey<BigDecimal> field = FormKey.decimal(key);
            Double current = attributes.get(key);
            form.field(field, FormField.decimal(field, readable(key))
                    .defaultValue(current == null ? null : BigDecimal.valueOf(current))
                    .optional());
        }
        form.hint("Blank keeps the vanilla value.");
        return form.ask(values -> {
            Map<String, Double> edited = new LinkedHashMap<>(attributes);
            for (String key : MobTemplate.ATTRIBUTES) {
                FormKey<BigDecimal> field = FormKey.decimal(key);
                if (values.has(field)) {
                    edited.put(key, values.get(field).doubleValue());
                } else {
                    edited.remove(key);
                }
            }
            return Map.copyOf(edited);
        });
    }

    /**
     * One form with a checkbox per {@link MobFlag}, prefilled.
     *
     * @param viewer who is editing
     * @param flags  the flags as they stand
     * @return the edited flags, or nothing when the viewer backed out
     */
    public @NotNull CompletionStage<Optional<Set<MobFlag>>> flagsEditor(@NotNull Player viewer,
                                                                        @NotNull Set<MobFlag> flags) {
        EditorForm form = EditorForm.of(plugin, viewer, "{primary}&lFLAGS");
        for (MobFlag flag : MobFlag.values()) {
            form.flag(FormKey.flag(flag.name()), flag.description(), flags.contains(flag));
        }
        return form.ask(values -> {
            Set<MobFlag> edited = EnumSet.noneOf(MobFlag.class);
            for (MobFlag flag : MobFlag.values()) {
                if (values.getOr(FormKey.flag(flag.name()), Boolean.FALSE)) edited.add(flag);
            }
            return Set.copyOf(edited);
        });
    }

    private static String readable(String key) {
        String words = key.replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    void stop() {
        engine.stop();
    }
}
