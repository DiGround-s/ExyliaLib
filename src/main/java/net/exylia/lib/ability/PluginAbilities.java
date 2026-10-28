package net.exylia.lib.ability;

import net.exylia.lib.ability.internal.BuiltinConditions;
import net.exylia.lib.ability.internal.BuiltinEffects;
import net.exylia.lib.ability.internal.ChainGuard;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.CooldownScope;
import net.exylia.lib.util.Cooldowns;
import net.exylia.lib.util.editor.Editors;
import net.exylia.lib.util.editor.ListEditor;
import net.exylia.lib.util.sequence.Sequence;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * One plugin's abilities: compiling them, knowing who holds what, and firing
 * them.
 *
 * <pre>{@code
 * PluginAbilities abilities = Abilities.of(this);
 *
 * // Load: compile once, never on a proc.
 * Ability bleed = abilities.compile(section, problem -> getLogger().warning(problem));
 *
 * // Who holds what. Cached per player; asked again on join, armour change,
 * // held-slot change, swap, drop, pick-up, item break, respawn and inventory close.
 * abilities.holdings(player -> enchants.heldBy(player));
 * abilities.refresh(player);            // after anything the library cannot see
 *
 * // The plugin's own vocabulary, next to the built-in one.
 * abilities.effect("bleed", BleedEffect::new);
 * abilities.condition("in_mine", args -> ctx -> mines.contains(ctx.location()));
 * abilities.trigger("MINE_DROP");
 * abilities.fire(player, "MINE_DROP", ctx -> ctx.with(MY_DROPS, drops));
 *
 * // Rules and observation.
 * abilities.gate((ctx, ability) -> rules.factor(ctx, ability));
 * abilities.onOutcome(outcome -> metrics.record(outcome));
 * }</pre>
 *
 * <h2>Order of an attempt</h2>
 * Gate, then conditions, then cooldown, then the chance roll, then the effects.
 * The cooldown starts only when the roll succeeds, so a miss never locks the
 * ability. A chance of 100 or more never rolls; 0 or less never procs.
 *
 * <h2>Threads</h2>
 * Triggers run on the event's thread, which is the holder's region on Folia;
 * {@link #fire} and {@link #refresh} must be called there too. Effects reach
 * entities owned by another region through that entity's scheduler.
 *
 * <h2>Hot path</h2>
 * Holdings are cached per player as an array per trigger, so a trigger nobody
 * holds costs one map read. Formulas, conditions and effects are compiled.
 *
 * <h2>Lifecycle and reload</h2>
 * Every cache and INTERVAL timer is keyed to the plugin and released on quit
 * and on disable; PASSIVE effects are reverted then. On reload, compile again
 * and call {@link #refreshAll()}: old holdings unequip, new ones equip, and
 * effects already running over time finish with their old numbers. Nothing here
 * derives from the palette, so there is no {@code invalidateAll}.
 *
 * @since 1.385.0
 */
public final class PluginAbilities {

    private static final Set<String> ABILITY_KEYS = Set.of("trigger", "chance", "cooldown", "conditions",
            "effects", "chain", "every");
    /** A hit leaving the holder at or below this share of their health fires LOW_HEALTH. */
    static final double LOW_HEALTH = 0.3;

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final String owner;
    private final Map<String, AbilityEffect.Factory> effects = new ConcurrentHashMap<>();
    private final Map<String, AbilityCondition.Factory> conditions = new ConcurrentHashMap<>();
    private final Set<String> triggers = ConcurrentHashMap.newKeySet();
    private final Map<String, AbilityScale.Factory> scales = new ConcurrentHashMap<>();
    /** The last ability compiled under each id: what a projectile's stamp resolves to. */
    private final Map<String, Ability> compiled = new ConcurrentHashMap<>();
    private final DamageShaping shaping = new DamageShaping();
    private final NamespacedKey shotKey;
    private volatile boolean projectileLevels;
    private final Map<UUID, Holding> cache = new ConcurrentHashMap<>();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    private final List<Consumer<AbilityOutcome>> outcomes = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new ArrayList<>(2);

    private volatile Function<Player, List<Held>> holdings = player -> List.of();
    private volatile @Nullable AbilityGate gate;
    private volatile Function<String, @Nullable Sequence> sequences = name -> null;
    /** The roll, 0 inclusive to 1 exclusive; a seam for tests. */
    private volatile DoubleSupplier dice = () -> ThreadLocalRandom.current().nextDouble();

    private PluginAbilities(Plugin plugin, boolean listen) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
        this.owner = plugin.getName().toLowerCase(Locale.ROOT);
        this.shotKey = new NamespacedKey(plugin, "ability-levels");
        triggers.addAll(Triggers.BUILT_IN);
        BuiltinEffects.register(this);
        DamageShaping.register(this);
        BuiltinConditions.register(this);
        if (listen) listen();
    }

    static PluginAbilities create(Plugin plugin) {
        return new PluginAbilities(plugin, true);
    }

    /** Without listeners, for tests that drive {@link #fire} themselves. */
    static PluginAbilities detached(Plugin plugin) {
        return new PluginAbilities(plugin, false);
    }

    private void listen() {
        AbilityListener listener = new AbilityListener(this);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
        try {
            // Paper only; loaded by name so Spigot never resolves the class.
            Class.forName("com.destroystokyo.paper.event.player.PlayerArmorChangeEvent");
            Listener armour = (Listener) Class.forName("net.exylia.lib.ability.PaperArmourListener")
                    .getDeclaredConstructor(PluginAbilities.class).newInstance(this);
            Bukkit.getPluginManager().registerEvents(armour, plugin);
            listeners.add(armour);
        } catch (ReflectiveOperationException | LinkageError spigot) {
            // Spigot: inventory close and the other refresh points cover armour.
        }
    }

    /** The owner. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /** The owner's scheduler. */
    public @NotNull TaskScheduler tasks() {
        return tasks;
    }

    // ------------------------------------------------------------------ vocabulary

    /**
     * Registers an effect, next to the built-in ones; replaces one of the same name.
     *
     * @param name    the key under {@code effects:}, lower case
     * @param factory builds it from its arguments at load
     * @return this
     */
    public @NotNull PluginAbilities effect(@NotNull String name, @NotNull AbilityEffect.Factory factory) {
        effects.put(name.toLowerCase(Locale.ROOT), factory);
        return this;
    }

    /**
     * Registers a condition, next to the built-in ones; replaces one of the same name.
     *
     * @param name    the key under {@code conditions:}, lower case
     * @param factory builds it from its arguments at load
     * @return this
     */
    public @NotNull PluginAbilities condition(@NotNull String name, @NotNull AbilityCondition.Factory factory) {
        conditions.put(name.toLowerCase(Locale.ROOT), factory);
        return this;
    }

    /**
     * Registers what {@code bonus-damage: { by: <name> }} scales by, next to
     * the built-in {@code missing-health} and {@code health-gap}; replaces one
     * of the same name. Register before compiling.
     *
     * <pre>{@code
     * abilities.scale("combo", args -> {
     *     long window = args.duration("window", Duration.ofSeconds(3)).toMillis();
     *     return ctx -> combos.hit(ctx.holder(), window);
     * });
     * }</pre>
     *
     * @param name    the value of {@code by}, lower case
     * @param factory builds it from the effect's line at load
     * @return this
     * @since 1.387.0
     */
    public @NotNull PluginAbilities scale(@NotNull String name, @NotNull AbilityScale.Factory factory) {
        scales.put(name.toLowerCase(Locale.ROOT), factory);
        return this;
    }

    @Nullable AbilityScale.Factory scaleFactory(String name) {
        return scales.get(name);
    }

    DamageShaping shaping() {
        return shaping;
    }

    /**
     * Declares a trigger the plugin fires itself with {@link #fire}. Register
     * before compiling: an ability on an unknown trigger does not compile.
     *
     * @param name the trigger, stored upper case
     * @return this
     */
    public @NotNull PluginAbilities trigger(@NotNull String name) {
        triggers.add(name.toUpperCase(Locale.ROOT));
        return this;
    }

    /** Every trigger an ability may name, built-in first. */
    public @NotNull Set<String> triggers() {
        return Set.copyOf(triggers);
    }

    /** Every effect name, built-in and registered. */
    public @NotNull Set<String> effects() {
        return Set.copyOf(effects.keySet());
    }

    /** Every condition name, built-in and registered, {@code not} and {@code any} included. */
    public @NotNull Set<String> conditions() {
        Set<String> names = new LinkedHashSet<>(conditions.keySet());
        names.add("not");
        names.add("any");
        return names;
    }

    /**
     * Where {@code sequence: <name>} finds a named sequence: the plugin's own
     * file. Asked on every play, so it reads the plugin's current map.
     *
     * @param lookup the sequence for a name, or {@code null}
     * @return this
     */
    public @NotNull PluginAbilities sequences(@NotNull Function<String, @Nullable Sequence> lookup) {
        this.sequences = lookup;
        return this;
    }

    /** A named sequence through {@link #sequences(Function)}, or {@code null}. */
    public @Nullable Sequence sequence(@NotNull String name) {
        return sequences.apply(name);
    }

    // ------------------------------------------------------------------ compiling

    /**
     * Compiles an ability from a section; its id is the section's name.
     *
     * @param section  the ability
     * @param problems told each problem, once, in English
     * @return the ability, or {@code null} when it cannot be used (no or unknown trigger)
     */
    public @Nullable Ability compile(@NotNull ConfigurationSection section, @NotNull Consumer<String> problems) {
        return compile(section.getName(), plain(section), problems);
    }

    /**
     * Compiles an ability from a map, as a YAML list of abilities gives them.
     *
     * <p>Unknown keys, effects and conditions are reported and kept in
     * {@link Ability#source()}, never dropped. An unreadable formula is
     * reported and the key's default is used.
     *
     * @param id       its id; also its cooldown's key, so stable across reloads
     * @param source   what the file wrote
     * @param problems told each problem, once, in English
     * @return the ability, or {@code null} when it cannot be used (no or unknown trigger)
     */
    public @Nullable Ability compile(@NotNull String id, @NotNull Map<?, ?> source,
                                     @NotNull Consumer<String> problems) {
        Map<String, Object> copy = Ability.deepCopy(stringKeys(source));
        Consumer<String> said = problem -> problems.accept("ability " + id + ": " + problem);
        AbilityArgs top = new AbilityArgs("ability", copy, said);

        String trigger = top.string("trigger", "").toUpperCase(Locale.ROOT);
        if (trigger.isEmpty()) {
            said.accept("has no trigger; it is not loaded");
            return null;
        }
        if (!triggers.contains(trigger)) {
            said.accept("unknown trigger " + trigger + "; it is not loaded");
            return null;
        }
        var chance = top.formula("chance", "100");
        var cooldown = top.seconds("cooldown", 0);
        boolean chain = top.flag("chain", false);
        int every = (int) Math.max(1, Math.round(top.seconds("every", 1).applyOr(1, 1)));
        List<AbilityCondition> compiledConditions = compileConditions(top.raw("conditions"), said);
        List<AbilityEffect> compiledEffects = compileEffects(top.raw("effects"), said);
        for (String key : copy.keySet()) {
            if (!ABILITY_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                said.accept("unknown key '" + key + "' (kept, ignored)");
            }
        }
        Ability ability = new Ability(id, trigger, chance, cooldown, compiledConditions, compiledEffects, chain,
                every, copy, owner);
        compiled.put(id, ability);
        return ability;
    }

    List<AbilityEffect> compileEffects(@Nullable Object raw, Consumer<String> problems) {
        List<AbilityEffect> out = new ArrayList<>();
        for (Map.Entry<String, Object> line : entries(raw, problems, "effects")) {
            AbilityEffect.Factory factory = effects.get(line.getKey());
            if (factory == null) {
                problems.accept("unknown effect '" + line.getKey() + "' (kept, skipped)");
                continue;
            }
            AbilityArgs args = new AbilityArgs(line.getKey(), line.getValue(), problems);
            try {
                out.add(factory.create(args));
            } catch (RuntimeException unusable) {
                args.problem(unusable.getMessage() + " (kept, skipped)");
                continue;
            }
            args.unread().forEach(key -> args.problem("unknown key '" + key + "' (kept, ignored)"));
        }
        return out;
    }

    List<AbilityCondition> compileConditions(@Nullable Object raw, Consumer<String> problems) {
        List<AbilityCondition> out = new ArrayList<>();
        for (Map.Entry<String, Object> line : entries(raw, problems, "conditions")) {
            AbilityCondition condition = compileCondition(line.getKey(), line.getValue(), problems);
            if (condition != null) out.add(condition);
        }
        return out;
    }

    private @Nullable AbilityCondition compileCondition(String name, @Nullable Object value, Consumer<String> problems) {
        if (name.equals("not")) {
            List<AbilityCondition> inner = value instanceof String single
                    ? optional(compileCondition(single.trim().toLowerCase(Locale.ROOT), null, problems))
                    : compileConditions(value instanceof Map<?, ?> ? List.of(value) : value, problems);
            return inner.isEmpty() ? null : context -> !all(inner, context);
        }
        if (name.equals("any")) {
            List<AbilityCondition> inner = compileConditions(value, problems);
            return inner.isEmpty() ? null : context -> {
                for (AbilityCondition condition : inner) {
                    if (condition.test(context)) return true;
                }
                return false;
            };
        }
        AbilityCondition.Factory factory = conditions.get(name);
        if (factory == null) {
            problems.accept("unknown condition '" + name + "' (kept, ignored: it holds)");
            return null;
        }
        AbilityArgs args = new AbilityArgs(name, value, problems);
        try {
            AbilityCondition condition = factory.create(args);
            args.unread().forEach(key -> args.problem("unknown key '" + key + "' (kept, ignored)"));
            return condition;
        } catch (RuntimeException unusable) {
            args.problem(unusable.getMessage() + " (kept, ignored: it holds)");
            return null;
        }
    }

    /** The keys an effect reads, asked of its factory with nothing written; for the editor. */
    @NotNull Set<String> effectKeys(@NotNull String name) {
        AbilityEffect.Factory factory = effects.get(name);
        if (factory == null) return Set.of();
        AbilityArgs probe = new AbilityArgs(name, Map.of(), problem -> { });
        try {
            factory.create(probe);
        } catch (RuntimeException ignored) {
            // The keys read before it gave up are still the keys it reads.
        }
        return probe.keys();
    }

    private static List<AbilityCondition> optional(@Nullable AbilityCondition condition) {
        return condition == null ? List.of() : List.of(condition);
    }

    private static boolean all(List<AbilityCondition> conditions, AbilityContext context) {
        for (AbilityCondition condition : conditions) {
            if (!condition.test(context)) return false;
        }
        return true;
    }

    /** {@code - name: value} lines, a map with several keys counting as several lines. */
    private static List<Map.Entry<String, Object>> entries(@Nullable Object raw, Consumer<String> problems,
                                                           String what) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            problems.accept("'" + what + "' is not a list; it is ignored");
            return List.of();
        }
        List<Map.Entry<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                map.forEach((key, value) -> out.add(Map.entry(String.valueOf(key).trim().toLowerCase(Locale.ROOT),
                        value == null ? "" : value)));
            } else if (item != null) {
                out.add(Map.entry(String.valueOf(item).trim().toLowerCase(Locale.ROOT), ""));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ holdings

    /**
     * Says who holds what. Asked on the player's thread whenever their holdings
     * may have changed; cached until then.
     *
     * @param holdings a player's held abilities; must be quick and touch no database
     * @return this
     */
    public @NotNull PluginAbilities holdings(@NotNull Function<Player, List<Held>> holdings) {
        this.holdings = holdings;
        return this;
    }

    /**
     * Asks the holdings function again for one player, now; EQUIP, UNEQUIP and
     * PASSIVE fire for what changed. Call it on the player's thread after
     * anything the library cannot see.
     *
     * @param player whose holdings
     */
    public void refresh(@NotNull Player player) {
        refresh(player, false);
    }

    /**
     * {@link #refresh(Player)} for everybody online, each on their own thread.
     * What a reload calls after compiling again.
     */
    public void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            tasks.runAtEntity(player, () -> refresh(player));
        }
    }

    /** What a player holds, as cached; empty when nothing is. */
    public @NotNull List<Held> held(@NotNull Player player) {
        Holding holding = cache.get(player.getUniqueId());
        return holding == null ? List.of() : holding.all;
    }

    /** Refreshes next tick, once however many events ask in between. */
    void refreshSoon(Player player) {
        if (pending.add(player.getUniqueId())) {
            tasks.runAtEntityLater(player, 1, () -> {
                pending.remove(player.getUniqueId());
                refresh(player);
            });
        }
    }

    /**
     * @param fresh whether what was held before is gone already (death, milk),
     *              so everything held now equips again
     */
    void refresh(Player player, boolean fresh) {
        List<Held> now;
        try {
            now = List.copyOf(holdings.apply(player));
        } catch (RuntimeException failure) {
            reportOnce("holdings", "The holdings function failed for " + player.getName() + "; kept the last answer.",
                    failure);
            return;
        }
        UUID id = player.getUniqueId();
        Holding previous = cache.get(id);
        List<Held> before = previous == null || fresh ? List.of() : previous.all;
        Holding next = new Holding(now);
        if (previous != null) {
            next.interval = previous.interval;
            next.ticks = previous.ticks;
        }
        cache.put(id, next);

        List<Held> removed = new ArrayList<>(before);
        now.forEach(removed::remove);
        List<Held> added = new ArrayList<>(now);
        before.forEach(added::remove);
        for (Held held : removed) unequip(player, held);
        for (Held held : added) equip(player, held);

        boolean wantsTimer = next.byTrigger.containsKey(Triggers.INTERVAL);
        if (wantsTimer && next.interval == null) {
            next.interval = tasks.runAtEntityTimer(player, 20, 20, () -> interval(player));
        } else if (!wantsTimer && next.interval != null) {
            next.interval.cancel();
            next.interval = null;
        }
    }

    /** Forgets a player who left: timers stop, PASSIVE effects revert. */
    void forget(Player player) {
        pending.remove(player.getUniqueId());
        Holding holding = cache.remove(player.getUniqueId());
        if (holding == null) return;
        if (holding.interval != null) holding.interval.cancel();
        for (Held held : holding.all) {
            if (held.ability().trigger().equals(Triggers.PASSIVE)) revert(player, held);
        }
    }

    private void equip(Player player, Held held) {
        String trigger = held.ability().trigger();
        if (trigger.equals(Triggers.EQUIP) || trigger.equals(Triggers.PASSIVE)) {
            AbilityContext context = new AbilityContext(this, player, trigger);
            try {
                attempt(context, held);
            } finally {
                context.finish();
            }
        }
    }

    private void unequip(Player player, Held held) {
        String trigger = held.ability().trigger();
        if (trigger.equals(Triggers.UNEQUIP)) {
            AbilityContext context = new AbilityContext(this, player, trigger);
            try {
                attempt(context, held);
            } finally {
                context.finish();
            }
        } else if (trigger.equals(Triggers.PASSIVE)) {
            revert(player, held);
        }
    }

    private void revert(Player player, Held held) {
        AbilityContext context = new AbilityContext(this, player, Triggers.PASSIVE);
        context.bind(held, 1);
        for (AbilityEffect effect : held.ability().effects()) {
            try {
                effect.revert(context);
            } catch (RuntimeException failure) {
                reportOnce("revert:" + held.ability().id(), "Reverting " + held.ability().id() + " failed.", failure);
            }
        }
    }

    private void interval(Player player) {
        Holding holding = cache.get(player.getUniqueId());
        if (holding == null) return;
        Held[] held = holding.byTrigger.get(Triggers.INTERVAL);
        int tick = ++holding.ticks;
        if (held == null) return;
        AbilityContext context = new AbilityContext(this, player, Triggers.INTERVAL);
        try {
            for (Held one : held) {
                if (tick % one.ability().every() == 0) attempt(context, one);
            }
        } finally {
            context.finish();
        }
    }

    // ------------------------------------------------------------------ firing

    /**
     * Fires a trigger for one holder: every ability they hold on it is attempted.
     *
     * <p>Call on the holder's thread. ATTACK and HIT_TAKEN do nothing while an
     * ability's own damage is being dealt, unless that ability chains.
     *
     * @param holder  who holds the abilities
     * @param trigger the trigger, built-in or {@linkplain #trigger(String) declared}
     * @param setup   fills in the context (victim, block, typed values), or {@code null}
     * @return how many abilities went off
     */
    public int fire(@NotNull Player holder, @NotNull String trigger, @Nullable Consumer<AbilityContext> setup) {
        String name = trigger.toUpperCase(Locale.ROOT);
        Holding holding = cache.get(holder.getUniqueId());
        if (holding == null) return 0;
        return fire(holder, name, holding.byTrigger.get(name), setup);
    }

    /** Fires with these holdings instead of the cached ones: a projectile's stamped levels. */
    int fire(Player holder, String name, Held @Nullable [] held, @Nullable Consumer<AbilityContext> setup) {
        if ((name.equals(Triggers.ATTACK) || name.equals(Triggers.HIT_TAKEN)) && ChainGuard.blocksDamageTriggers()) {
            return 0;
        }
        if (held == null || held.length == 0) return 0;

        AbilityContext context = new AbilityContext(this, holder, name);
        if (setup != null) setup.accept(context);
        int procs = 0;
        try {
            for (Held one : held) {
                if (attempt(context, one)) procs++;
            }
        } finally {
            context.finish();
            if (!shaping.deferring()) shaping.apply(context.event());
        }
        return procs;
    }

    // ------------------------------------------------------------------ projectiles

    /**
     * Whether PROJECTILE_HIT reads the levels a shot left the bow with: on
     * BOW_SHOOT the shooter's PROJECTILE_HIT holdings are stamped on the
     * projectile, and when it lands those fire, whatever the shooter holds
     * by then. A projectile with no stamp (thrown by hand, shot before this
     * was on) falls back to the shooter's current holdings. Off by default:
     * the hit reads the current holdings, as before.
     *
     * @param enabled whether shots carry their levels
     * @return this
     * @since 1.387.0
     */
    public @NotNull PluginAbilities projectileLevels(boolean enabled) {
        this.projectileLevels = enabled;
        return this;
    }

    /** Whether shots carry their levels; see {@link #projectileLevels(boolean)}. */
    public boolean projectileLevels() {
        return projectileLevels;
    }

    /**
     * Stamps the shooter's current PROJECTILE_HIT holdings on a projectile,
     * as BOW_SHOOT does when {@link #projectileLevels(boolean)} is on; for a
     * plugin that launches projectiles of its own. On the projectile's thread.
     *
     * @param projectile the shot
     * @param shooter    whose holdings
     * @since 1.387.0
     */
    public void stamp(@NotNull Projectile projectile, @NotNull Player shooter) {
        Holding holding = cache.get(shooter.getUniqueId());
        Held[] held = holding == null ? null : holding.byTrigger.get(Triggers.PROJECTILE_HIT);
        projectile.getPersistentDataContainer().set(shotKey, PersistentDataType.STRING, encodeShot(held));
    }

    /**
     * The holdings a projectile was {@linkplain #stamp stamped} with, each
     * resolved to the ability last compiled under its id; ids no longer
     * compiled are left out.
     *
     * @param projectile the shot
     * @return the holdings, or {@code null} when it carries no stamp
     * @since 1.387.0
     */
    public @Nullable List<Held> carried(@NotNull Projectile projectile) {
        String stamp = projectile.getPersistentDataContainer().get(shotKey, PersistentDataType.STRING);
        return stamp == null ? null : decodeShot(stamp, compiled::get);
    }

    /** {@code id\tlevel} lines; pure, for tests. */
    static String encodeShot(Held @Nullable [] held) {
        if (held == null) return "";
        StringBuilder out = new StringBuilder();
        for (Held one : held) {
            if (!out.isEmpty()) out.append('\n');
            out.append(one.ability().id()).append('\t').append(one.level());
        }
        return out.toString();
    }

    static List<Held> decodeShot(String stamp, Function<String, @Nullable Ability> abilities) {
        List<Held> out = new ArrayList<>();
        if (stamp.isEmpty()) return out;
        for (String line : stamp.split("\n")) {
            int tab = line.lastIndexOf('\t');
            if (tab <= 0) continue;
            Ability ability = abilities.apply(line.substring(0, tab));
            if (ability == null) continue;
            try {
                out.add(new Held(ability, Integer.parseInt(line.substring(tab + 1)), null));
            } catch (NumberFormatException unreadable) {
                // A stamp written by something else: skipped.
            }
        }
        return out;
    }

    /** Whether anybody holds an ability on this trigger: the listener's first, cheapest question. */
    boolean holds(@NotNull Player holder, @NotNull String trigger) {
        Holding holding = cache.get(holder.getUniqueId());
        return holding != null && holding.byTrigger.containsKey(trigger);
    }

    private boolean attempt(AbilityContext context, Held held) {
        Ability ability = held.ability();
        context.bind(held, 1);
        AbilityGate rules = gate;
        if (rules != null) {
            double factor = rules.factor(context, ability);
            if (!(factor > 0)) return outcome(context, held, AbilityOutcome.Result.GATED, null);
            context.bind(held, factor);
        }
        for (AbilityCondition condition : ability.conditions()) {
            boolean holds;
            try {
                holds = condition.test(context);
            } catch (RuntimeException failure) {
                reportOnce("condition:" + ability.id(), "A condition of " + ability.id()
                        + " failed; it is treated as holding.", failure);
                holds = true;
            }
            if (!holds) return outcome(context, held, AbilityOutcome.Result.CONDITIONS, null);
        }
        CooldownScope scope = CooldownScope.player(context.holder().getUniqueId());
        if (Cooldowns.isActive(scope, ability.cooldownKey())) {
            return outcome(context, held, AbilityOutcome.Result.COOLDOWN, null);
        }
        double chance = ability.chance(held.level());
        if (chance < 100 && (chance <= 0 || dice.getAsDouble() * 100 >= chance)) {
            return outcome(context, held, AbilityOutcome.Result.CHANCE, null);
        }
        Duration cooldown = ability.cooldown(held.level());
        if (!cooldown.isZero()) Cooldowns.start(scope, ability.cooldownKey(), cooldown);

        AbilityReport report = new AbilityReport();
        ChainGuard.run(ability, () -> {
            for (AbilityEffect effect : ability.effects()) {
                try {
                    effect.apply(context, report);
                } catch (RuntimeException failure) {
                    reportOnce("effect:" + ability.id() + ":" + effect.getClass().getName(),
                            "An effect of " + ability.id() + " failed; the others still ran.", failure);
                }
            }
        });
        outcome(context, held, AbilityOutcome.Result.PROC, report);
        return true;
    }

    private boolean outcome(AbilityContext context, Held held, AbilityOutcome.Result result,
                            @Nullable AbilityReport report) {
        if (!outcomes.isEmpty()) {
            AbilityOutcome outcome = new AbilityOutcome(held.ability().id(), context.trigger(), held.level(),
                    context.holder().getUniqueId(), result,
                    report == null ? 0 : report.damage(), report == null ? 0 : report.healing(),
                    report == null ? 0 : report.targets(), report == null ? 0 : report.drops());
            for (Consumer<AbilityOutcome> listener : outcomes) {
                try {
                    listener.accept(outcome);
                } catch (RuntimeException failure) {
                    reportOnce("outcome", "An outcome listener failed.", failure);
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ rules and observation

    /**
     * The plugin's rules, asked first on every attempt; replaces the last one.
     *
     * @param gate the factor for an attempt, or {@code null} for none
     * @return this
     */
    public @NotNull PluginAbilities gate(@Nullable AbilityGate gate) {
        this.gate = gate;
        return this;
    }

    /**
     * Told what every attempt came to, on the holder's thread. Keep it cheap:
     * count in memory, flush elsewhere.
     *
     * @param listener the observer
     * @return this
     */
    public @NotNull PluginAbilities onOutcome(@NotNull Consumer<AbilityOutcome> listener) {
        outcomes.add(listener);
        return this;
    }

    // ------------------------------------------------------------------ editor

    /**
     * An in-game editor over a list of abilities: trigger, chance, cooldown,
     * conditions and effects, each effect a form with the keys it reads.
     *
     * <pre>{@code
     * abilities.editor(enchant.abilities())
     *          .title("{primary}&lABILITIES {letters_black}» {highlight}Bleed")
     *          .onSave(edited -> enchants.save(id, edited.stream().map(Ability::source).toList()))
     *          .open(player);
     * }</pre>
     *
     * <p>Nothing is written until the viewer saves; the plugin writes each
     * ability's {@link Ability#source()} back to its file.
     *
     * @param abilities what to edit; copied, never held
     * @return the editor, ready to open
     */
    public @NotNull ListEditor<Ability> editor(@NotNull List<Ability> abilities) {
        return Editors.of(plugin).list(new AbilityDescriptor(this), Ability.class, abilities);
    }

    // ------------------------------------------------------------------ lifecycle

    void release() {
        for (Listener listener : listeners) {
            org.bukkit.event.HandlerList.unregisterAll(listener);
        }
        listeners.clear();
        for (Map.Entry<UUID, Holding> entry : cache.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) continue;
            try {
                forget(player);
            } catch (RuntimeException | LinkageError offThread) {
                // Folia: the player belongs to another region; their effects end with them.
            }
        }
        cache.clear();
        pending.clear();
        outcomes.clear();
        shaping.clear();
    }

    void reportOnce(String key, String message, @Nullable Throwable failure) {
        if (reported.add(key)) {
            plugin.getLogger().log(Level.WARNING, "[abilities] " + message, failure);
        }
    }

    void dice(DoubleSupplier dice) {
        this.dice = dice;
    }

    String owner() {
        return owner;
    }

    private static Map<String, Object> stringKeys(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    private static Map<String, Object> plain(ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            out.put(key, plainValue(value));
        }
        return out;
    }

    private static Object plainValue(Object value) {
        if (value instanceof ConfigurationSection inner) return plain(inner);
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(item -> out.add(plainValue(item)));
            return out;
        }
        return value;
    }

    /** One player's holdings: all of them, and by trigger for the hot path. */
    private static final class Holding {
        final List<Held> all;
        final Map<String, Held[]> byTrigger;
        @Nullable TaskHandle interval;
        int ticks;

        Holding(List<Held> all) {
            this.all = all;
            Map<String, List<Held>> grouped = new HashMap<>();
            for (Held held : all) {
                grouped.computeIfAbsent(held.ability().trigger(), key -> new ArrayList<>()).add(held);
            }
            Map<String, Held[]> arrays = new HashMap<>(grouped.size() * 2);
            grouped.forEach((trigger, list) -> arrays.put(trigger, list.toArray(Held[]::new)));
            this.byTrigger = arrays;
        }
    }
}
