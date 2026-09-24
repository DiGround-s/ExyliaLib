package net.exylia.lib.util.mob;

import net.exylia.lib.input.FormField;
import net.exylia.lib.input.FormKey;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.editor.EditorForm;
import net.exylia.lib.util.editor.Editors;
import net.exylia.lib.util.editor.ListEditor;
import net.exylia.lib.util.mob.internal.MobEngine;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * The registry, {@link #live}, {@link #count}, the handlers, {@link #auras}
 * and {@link #effectRadius} are safe from any thread. {@link #spawn} is too: it moves onto the location's thread.
 * {@link #templateOf} and {@link #isMob} read the entity and belong on its
 * thread. Death and hit handlers run on the mob's thread: a death inside the
 * death event, a break or an expiry just before the mob is removed.
 *
 * <h2>Lifecycle</h2>
 * Listeners and timers belong to the plugin. When it is disabled, its live
 * mobs are removed and its templates forgotten. Every mob runs one entity timer,
 * once a second, for its interval skills, hunting, lifetime, leash and look
 * cycles — every other tick instead for a mob with an aura, a {@code <rainbow>}
 * name or {@link MobFlag#WANDERS}, which does the one-second work on every
 * tenth pass; that is also how the
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

    /**
     * Stops telling a death handler. A module that can be turned off and on
     * again removes its handler as it goes off, or it is told every death twice.
     *
     * @param handler the same instance {@link #onDeath} was given
     * @return whether it was listening
     * @since 1.195.0
     */
    public boolean offDeath(@NotNull Consumer<MobDeath> handler) {
        return engine.offDeath(Objects.requireNonNull(handler, "handler"));
    }

    /**
     * Listens for counted hits on this plugin's mobs in hits mode
     * ({@link MobBehaviour#usesHits()}), the breaking one included.
     *
     * <p>A handler that throws is reported and does not stop the others.
     *
     * @param handler told each counted hit, on the mob's thread, after its
     *                {@code DAMAGED} skills and before the break
     * @return this
     * @since 1.195.0
     */
    public @NotNull PluginMobs onHit(@NotNull Consumer<MobHit> handler) {
        engine.onHit(Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /**
     * Stops telling a hit handler.
     *
     * @param handler the same instance {@link #onHit} was given
     * @return whether it was listening
     * @since 1.195.0
     */
    public boolean offHit(@NotNull Consumer<MobHit> handler) {
        return engine.offHit(Objects.requireNonNull(handler, "handler"));
    }

    // ------------------------------------------------------------------- looks

    /**
     * The auras a {@link MobLook#aura()} can name, replacing the ones before.
     *
     * <pre>{@code
     * mobs.auras(Map.of("sparks", List.of(
     *         "[CIRCLE] FIREWORK;radius:1.0;points:3;y:1.4;rotate:%angle%",
     *         "[PARTICLE] END_ROD;count:1;offset:0.5,0.6,0.5;speed:0.01;y:1.4")));
     * }</pre>
     *
     * <p>Each entry is an aura's name and the sequence lines of one frame. A
     * frame is drawn at the mob's feet every other tick; {@code %angle%}
     * turns 20 degrees a frame (a full turn in 18 frames) and {@code %angle2%}
     * and {@code %angle3%} run 120 and 240 degrees ahead of it, so a
     * {@code rotate:%angle%} shape spins around the mob. The map's order counts:
     * a look naming an aura that is not here wears the first one, and CYCLE and
     * RANDOM pick from them all. Mobs alive keep the aura they spawned with,
     * except a CYCLE, which reads the new set on its next second.
     *
     * @param auras frame lines by aura name, in order; copied
     * @return this
     * @since 1.195.0
     */
    public @NotNull PluginMobs auras(@NotNull Map<String, List<String>> auras) {
        engine.auras(Objects.requireNonNull(auras, "auras"));
        return this;
    }

    /**
     * The auras registered, in order.
     *
     * @since 1.195.0
     */
    public @NotNull Map<String, List<String>> auras() {
        return engine.auras();
    }

    /**
     * How far away skill effect lines, effect skills and auras are seen, in
     * blocks; {@value net.exylia.lib.util.sequence.SequenceTarget#DEFAULT_RADIUS} until set.
     *
     * @param blocks the radius
     * @return this
     * @since 1.195.0
     */
    public @NotNull PluginMobs effectRadius(double blocks) {
        engine.effectRadius(blocks);
        return this;
    }

    /**
     * How far away effects are seen, in blocks.
     *
     * @since 1.195.0
     */
    public double effectRadius() {
        return engine.effectRadius();
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

    /**
     * One form for a {@link MobBehaviour}, prefilled: hits, hit cooldown,
     * lifetime and roam.
     *
     * @param viewer    who is editing
     * @param behaviour the behaviour as it stands
     * @return the edited behaviour, or nothing when the viewer backed out
     * @since 1.195.0
     */
    public @NotNull CompletionStage<Optional<MobBehaviour>> behaviourEditor(@NotNull Player viewer,
                                                                            @NotNull MobBehaviour behaviour) {
        FormKey<Long> hits = FormKey.integer("hits");
        FormKey<Duration> cooldown = FormKey.duration("hit_cooldown");
        FormKey<Duration> lifetime = FormKey.duration("lifetime");
        FormKey<BigDecimal> roam = FormKey.decimal("roam");
        EditorForm form = EditorForm.of(plugin, viewer, "{primary}&lBEHAVIOUR")
                .integer(hits, "Hits to break it", behaviour.hits())
                .hint("0 lets its health decide. Above 0 nothing hurts it; each player's hit counts one.")
                .field(cooldown, FormField.duration(cooldown, "Between two hits of one player")
                        .defaultValue(zeroAsBlank(behaviour.hitCooldown())).optional())
                .hint("Hits mode only. 500ms, 1s. 0 for none.")
                .field(lifetime, FormField.duration(lifetime, "Leaves after")
                        .defaultValue(zeroAsBlank(behaviour.lifetime())).optional())
                .hint("5m, 1h. 0 stays until it dies.")
                .decimal(roam, "Roam, in blocks", BigDecimal.valueOf(behaviour.roam()).stripTrailingZeros())
                .hint("0 goes anywhere. Past it, it walks back; 8 blocks further, it is put back.");
        return form.ask(values -> new MobBehaviour(
                (int) Math.max(0, Math.min(Integer.MAX_VALUE, values.getOr(hits, 0L))),
                values.getOr(cooldown, Duration.ZERO),
                values.getOr(lifetime, Duration.ZERO),
                values.getOr(roam, BigDecimal.ZERO).doubleValue()));
    }

    /**
     * One form for a {@link MobLook}, prefilled, with the choices of this
     * type in each hint. A field the type cannot use (a zombie's variant, a
     * cow's body) is not asked and keeps its value.
     *
     * @param viewer who is editing
     * @param type   the template's type, which decides the variants and bodies offered
     * @param look   the look as it stands
     * @return the edited look, or nothing when the viewer backed out
     * @since 1.195.0
     */
    public @NotNull CompletionStage<Optional<MobLook>> lookEditor(@NotNull Player viewer, @NotNull EntityType type,
                                                                  @NotNull MobLook look) {
        FormKey<String> variant = FormKey.text("variant");
        FormKey<String> body = FormKey.text("body");
        FormKey<String> glow = FormKey.text("glow");
        FormKey<String> aura = FormKey.text("aura");
        List<String> variants = MobLook.variants(type);
        List<String> bodies = MobLook.bodies(type);
        EditorForm form = EditorForm.of(plugin, viewer, "{primary}&lLOOK");
        if (!variants.isEmpty()) {
            form.field(variant, optionalText(variant, "Variant", look.variant())).hint(choices(variants));
        }
        if (!bodies.isEmpty()) {
            form.field(body, optionalText(body, "Worn on its back", look.body())).hint(choices(bodies));
        }
        form.field(glow, optionalText(glow, "Outline colour", look.glow()))
                .hint(choices(MobLook.GLOWS) + " Any value makes it glow.");
        Collection<String> auraNames = engine.auras().keySet();
        form.field(aura, optionalText(aura, "Aura", look.aura()))
                .hint(auraNames.isEmpty() ? "No auras are registered. NONE for none." : choices(List.copyOf(auraNames)));
        return form.ask(values -> new MobLook(
                variants.isEmpty() ? look.variant() : values.getOr(variant, ""),
                bodies.isEmpty() ? look.body() : values.getOr(body, ""),
                values.getOr(glow, ""),
                values.getOr(aura, "")));
    }

    /**
     * The fight a template's skills share: first which part, then its screen.
     *
     * <ul>
     *   <li><b>TIMING</b> — one form: the global cooldown, and one period per
     *       rotation group the skills use. A group left out of
     *       {@code groupsInUse} loses its period on save.</li>
     *   <li><b>PHASES</b> — a list editor over the phases: below which share of
     *       health each starts, its name suffix, style and multipliers.</li>
     * </ul>
     *
     * <pre>{@code
     * Set<String> groups = template.skills().stream().map(skill -> skill.cast().group())
     *         .filter(group -> !group.isEmpty()).collect(Collectors.toSet());
     * mobs.fightEditor(player, template.fight(), groups)
     *     .thenAccept(edited -> edited.ifPresent(fight -> store.save(template.withFight(fight))));
     * }</pre>
     *
     * @param viewer      who is editing
     * @param fight       the fight as it stands
     * @param groupsInUse the rotation groups the template's skills name
     * @return the edited fight, or nothing when the viewer backed out
     * @since 1.198.0
     */
    public @NotNull CompletionStage<Optional<MobFight>> fightEditor(@NotNull Player viewer, @NotNull MobFight fight,
                                                                    @NotNull Set<String> groupsInUse) {
        List<String> groups = new java.util.TreeSet<>(groupsInUse).stream().filter(group -> !group.isBlank()).toList();
        return Inputs.of(plugin)
                .choice(viewer, "{primary}&lFIGHT", List.of("TIMING", "PHASES"))
                .label(part -> part.equals("TIMING") ? "{primary}&lTIMING"
                        : "{primary}&lPHASES &8[{info}" + fight.phases().size() + "&8]")
                .icon(part -> part.equals("TIMING") ? Material.CLOCK : Material.BLAZE_POWDER)
                .key(part -> part)
                .open()
                .thenCompose(part -> {
                    if (!part.completed()) return CompletableFuture.completedFuture(Optional.<MobFight>empty());
                    return part.value().equals("TIMING") ? fightTiming(viewer, fight, groups) : phases(viewer, fight);
                });
    }

    private CompletionStage<Optional<MobFight>> fightTiming(Player viewer, MobFight fight, List<String> groups) {
        FormKey<Duration> gcd = FormKey.duration("gcd");
        EditorForm form = EditorForm.of(plugin, viewer, "{primary}&lTIMING")
                .field(gcd, FormField.duration(gcd, "Between two attacks")
                        .defaultValue(zeroAsBlank(fight.globalCooldown())).optional())
                .hint("After any skill but EFFECT and COMMAND, how long before the next. 0 for none.");
        for (int index = 0; index < groups.size(); index++) {
            FormKey<Duration> period = FormKey.duration("group_" + index);
            form.field(period, FormField.duration(period, "Group " + groups.get(index) + ", every")
                            .defaultValue(fight.period(groups.get(index))))
                    .hint("One skill of the group per period, picked by weight. 1s at least.");
        }
        return form.ask(values -> {
            Map<String, Duration> periods = new LinkedHashMap<>();
            for (int index = 0; index < groups.size(); index++) {
                periods.put(groups.get(index), values.getOr(FormKey.duration("group_" + index),
                        fight.period(groups.get(index))));
            }
            return new MobFight(values.getOr(gcd, Duration.ZERO), periods, fight.phases());
        });
    }

    private CompletionStage<Optional<MobFight>> phases(Player viewer, MobFight fight) {
        CompletableFuture<Optional<MobFight>> edited = new CompletableFuture<>();
        Editors.of(plugin).list(new MobPhaseDescriptor(plugin), MobPhase.class, fight.phases())
                .title("{primary}&lPHASES")
                .onSave(phases -> edited.complete(Optional.of(fight.withPhases(phases))))
                .onCancel(() -> edited.complete(Optional.empty()))
                .open(viewer);
        return edited;
    }

    /** Prefilled; blank keeps the value, so the hints say NONE clears it. */
    static FormField<String> optionalText(FormKey<String> key, String label, String current) {
        return FormField.text(key, label).defaultValue(current).optional();
    }

    private static String choices(List<String> names) {
        return String.join(", ", names).toLowerCase(Locale.ROOT) + ", CYCLE or RANDOM. NONE for vanilla.";
    }

    private static @Nullable Duration zeroAsBlank(Duration duration) {
        return duration.isZero() ? null : duration;
    }

    private static String readable(String key) {
        String words = key.replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    void stop() {
        engine.stop();
    }
}
