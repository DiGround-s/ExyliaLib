package net.exylia.lib.util.sequence;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.exylia.lib.text.Values;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Where a sequence happens, who caused it, and who gets to see it.
 *
 * <pre>{@code
 * SequenceTarget target = SequenceTarget.at(victim.getLocation())
 *         .by(killer)
 *         .on(victim)
 *         .visibleTo((observer, source) -> settings.showsEffects(observer));
 * }</pre>
 *
 * <h2>Location, not player</h2>
 * A sequence is anchored to a place. A kill effect plays at the victim's feet
 * even though the killer is the one who owns it, and a projectile trail has no
 * player at its position at all. Anchoring to a player instead would make the
 * common case wrong in a way nobody notices until the effect is visibly in the
 * wrong spot.
 *
 * <h2>Who sees it</h2>
 * Observers are the players within {@link #radius()} blocks, narrowed by an
 * optional predicate. The predicate is what a "show effects: off" toggle plugs
 * into &mdash; every consumer of this module implements one, so the seam is
 * here rather than copied three times.
 *
 * <p>The radius is a value rather than a constant: ExyliaCommons hardcoded 32
 * blocks, which is right for a kill effect and wasteful for a footstep.
 *
 * @since 1.30.0
 */
public final class SequenceTarget {

    /** What ExyliaCommons hardcoded, kept as the default so effects look the same. */
    public static final double DEFAULT_RADIUS = 32.0;

    private final Location location;
    private final Player source;
    private final Entity target;
    private final BiPredicate<Player, Player> visibility;
    private final double radius;

    /** What {@code %name%} stands for in this play; never {@code null}. */
    private final Map<String, String> values;

    /** The items {@code {item:name}} stands for in this play; never {@code null}. */
    private final Map<String, ItemStack> items;

    /** The body {@code [RAGDOLL]} wears instead of a player's, or {@code null}. */
    private final net.exylia.lib.ragdoll.RagdollModel wearing;

    /** The entity the steps after a hold move with, or {@code null} to stay put. */
    private final Entity follows;

    /** How large everything is drawn about the location; 1 is as written. */
    private final double scale;

    /**
     * The last answer {@link #observers()} gave, and when.
     *
     * <p>One target is asked once per frame of every line it plays, and a kill
     * effect is twenty lines of several frames each: without this, one death
     * walks the world's player list a hundred times inside the same tick and
     * gets the same answer every time. Held for one tick, because that is how
     * long "who is nearby" can be true for.
     *
     * <p>Every other field is final and every builder method returns a new
     * target, so this is a memo of a pure function rather than state: two
     * targets never share it, and a moved target ({@link #movedTo}) starts
     * without one.
     */
    private volatile List<Player> memo;
    private volatile long memoAt;

    /** One tick, in milliseconds. */
    private static final long TICK_MS = 50L;

    private SequenceTarget(Location location, Player source, Entity target,
                           BiPredicate<Player, Player> visibility, double radius) {
        this(location, source, target, visibility, radius, Map.of(), Map.of(), null, null, 1.0);
    }

    private SequenceTarget(Location location, Player source, Entity target,
                           BiPredicate<Player, Player> visibility, double radius,
                           Map<String, String> values, Map<String, ItemStack> items,
                           net.exylia.lib.ragdoll.RagdollModel wearing, Entity follows, double scale) {
        this.scale = scale;
        this.wearing = wearing;
        this.follows = follows;
        this.location = location;
        this.source = source;
        this.target = target;
        this.visibility = visibility;
        this.radius = radius;
        this.values = values;
        this.items = items;
    }

    /**
     * A sequence at a place, seen by everyone within the default radius.
     *
     * @param location where it happens
     * @return the target
     */
    public static @NotNull SequenceTarget at(@NotNull Location location) {
        return new SequenceTarget(location.clone(), null, null, null, DEFAULT_RADIUS);
    }

    /**
     * A sequence at a player's feet, caused by that same player.
     *
     * <p>The shorthand for a self effect &mdash; a rank cosmetic, a join
     * flourish.
     *
     * @param player whose feet, and whose effect
     * @return the target
     */
    public static @NotNull SequenceTarget of(@NotNull Player player) {
        return new SequenceTarget(player.getLocation(), player, player, null, DEFAULT_RADIUS);
    }

    /**
     * Who caused this.
     *
     * <p>Used by {@code [TITLE]} and {@code [ACTION_BAR]}, which have nobody to
     * show to without one, and by {@code {player}} in {@code [COMMAND]}.
     *
     * @param player the player, or {@code null} for none
     * @return a new target
     */
    public @NotNull SequenceTarget by(@Nullable Player player) {
        return new SequenceTarget(location, player, target, visibility, radius, values, items, wearing, follows, scale);
    }

    /**
     * What this happened to.
     *
     * <p>Used by {@code [POTION]}, which needs something living to apply to.
     *
     * @param entity the entity, or {@code null} for none
     * @return a new target
     */
    public @NotNull SequenceTarget on(@Nullable Entity entity) {
        return new SequenceTarget(location, source, entity, visibility, radius, values, items, wearing, follows, scale);
    }

    /**
     * Narrows who perceives this, on top of the radius.
     *
     * <p>The predicate receives the candidate observer and the source player,
     * and answers whether that observer should see it. It is asked once per
     * observer per step, so it must be cheap &mdash; a map lookup, not a
     * database call.
     *
     * @param predicate the test, or {@code null} to let the radius decide alone
     * @return a new target
     */
    public @NotNull SequenceTarget visibleTo(@Nullable BiPredicate<Player, Player> predicate) {
        return new SequenceTarget(location, source, target, predicate, radius, values, items, wearing, follows, scale);
    }

    /**
     * Shows this to one player and nobody else.
     *
     * <p>What a menu preview wants. Commons achieved it by passing a predicate
     * matching a single UUID, which every consumer wrote out by hand.
     *
     * @param viewer the only player who sees it
     * @return a new target
     */
    public @NotNull SequenceTarget onlyTo(@NotNull Player viewer) {
        return visibleTo((observer, ignored) -> observer.equals(viewer));
    }

    /**
     * How far away this can be seen, in blocks.
     *
     * @param blocks the radius; values below zero are treated as zero
     * @return a new target
     */
    public @NotNull SequenceTarget within(double blocks) {
        return new SequenceTarget(location, source, target, visibility, Math.max(0.0, blocks), values, items, wearing, follows, scale);
    }

    /**
     * The same target moved to another place.
     *
     * <p>What a projectile trail re-uses on every tick: the source, the
     * visibility rule and the radius are unchanged, only the position moves.
     *
     * @param destination the new position
     * @return a new target
     */
    public @NotNull SequenceTarget movedTo(@NotNull Location destination) {
        return new SequenceTarget(destination.clone(), source, target, visibility, radius, values, items, wearing, follows, scale);
    }

    /**
     * The values {@code %name%} stands for in this play.
     *
     * <pre>{@code
     * sequences.play(reveal, SequenceTarget.at(crate.getLocation())
     *         .by(opener)
     *         .values(Values.of("tier", tier.name()).putText("player", opener.getName()))
     *         .item("reward", prize));
     * }</pre>
     *
     * <p>Substituted before the line is parsed, so colours and
     * {@code %papi%} placeholders in the value still resolve, in the text of
     * {@code [TITLE]}, {@code [ACTION_BAR]}, {@code [MESSAGE]} and
     * {@code [DISPLAY] ...;as:text}, and in {@code color:}, {@code fade:} and
     * {@code glow:}. A value somebody typed goes in with
     * {@link Values#putText}, which keeps it inert. The values are copied, so
     * the bag can be reused.
     *
     * @param values the values; merged over any given before
     * @return a new target
     * @since 1.272.0
     */
    public @NotNull SequenceTarget values(@NotNull Values values) {
        Map<String, String> merged = new LinkedHashMap<>(this.values);
        for (Map.Entry<String, Object> value : values.map().entrySet()) {
            merged.put(value.getKey(), String.valueOf(value.getValue()));
        }
        return new SequenceTarget(location, source, target, visibility, radius,
                Map.copyOf(merged), items, wearing, follows, scale);
    }

    /**
     * An item a line can draw by name, decided when the sequence plays.
     *
     * <p>{@code [DISPLAY] {item:reward}} shows it; a line naming an item this
     * play was not given draws nothing rather than a placeholder.
     *
     * @param name the name the line uses, without braces
     * @param item the item; kept as given, so do not change it afterwards
     * @return a new target
     * @since 1.272.0
     */
    public @NotNull SequenceTarget item(@NotNull String name, @NotNull ItemStack item) {
        Map<String, ItemStack> merged = new LinkedHashMap<>(items);
        merged.put(name.toLowerCase(Locale.ROOT), item);
        return new SequenceTarget(location, source, target, visibility, radius, values,
                Map.copyOf(merged), wearing, follows, scale);
    }

    /**
     * The values of this play, as {@link #values(Values)} gave them.
     *
     * @return the values by name, without percent signs; never {@code null}
     * @since 1.272.0
     */
    public @NotNull Map<String, String> values() {
        return values;
    }

    /**
     * The item a line names, or {@code null} when this play has none by that name.
     *
     * @param name the name, without braces
     * @return the item
     * @since 1.272.0
     */
    public @Nullable ItemStack item(@NotNull String name) {
        return items.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * A line with this play's values in it.
     *
     * @param line the line as written; {@code null} becomes empty
     * @return the line with every {@code %name%} this play knows replaced
     * @since 1.272.0
     */
    public @NotNull String fill(@Nullable String line) {
        if (line == null) {
            return "";
        }
        if (values.isEmpty() || line.indexOf('%') < 0) {
            return line;
        }
        String filled = line;
        for (Map.Entry<String, String> value : values.entrySet()) {
            filled = filled.replace("%" + value.getKey() + "%", value.getValue());
        }
        return filled;
    }

    /**
     * The body every {@code [RAGDOLL]} line of this play wears, instead of the
     * victim's or the killer's.
     *
     * <pre>{@code
     * sequences.play(dance, SequenceTarget.at(statue).wearing(RagdollModel.ofTexture(value)));
     * }</pre>
     *
     * <p>For a body that belongs to no connected player: a placed NPC, a
     * statue. The model's skin, head, size, outline and what it carries are
     * the starting point; a line's own {@code detail:}, {@code glow:},
     * {@code light:} and {@code hold:}/{@code offhand:}/{@code hat:} still
     * apply over it, and its {@code size:} multiplies the model's.
     *
     * @param model the body, or {@code null} to go back to the players'
     * @return a new target
     * @since 1.302.0
     */
    public @NotNull SequenceTarget wearing(@Nullable net.exylia.lib.ragdoll.RagdollModel model) {
        return new SequenceTarget(location, source, target, visibility, radius, values, items, model, follows, scale);
    }

    /**
     * The body {@code [RAGDOLL]} lines wear in this play, or {@code null} for the players'.
     *
     * @since 1.302.0
     */
    public @Nullable net.exylia.lib.ragdoll.RagdollModel wearing() {
        return wearing;
    }

    /**
     * Keeps the steps after a {@code [DELAY]} where an entity is now, rather
     * than where the sequence started.
     *
     * <pre>{@code
     * sequences.play(buff, SequenceTarget.at(player.getLocation()).by(player).follow(player));
     * }</pre>
     *
     * <p>What an effect that ends on someone wants: a buff's closing sound
     * five seconds in plays at the player's feet, not where they stood when it
     * began. Each resume after a hold runs on the entity's own thread and
     * re-anchors there; if the entity is gone by then, the rest of the
     * sequence is skipped. Steps before the first hold, and the frames of a
     * shape already drawing, keep the position they started with.
     *
     * @param entity what to move with, or {@code null} to stay where it started
     * @return a new target
     * @since 1.364.0
     */
    public @NotNull SequenceTarget follow(@Nullable Entity entity) {
        return new SequenceTarget(location, source, target, visibility, radius, values, items, wearing, entity, scale);
    }

    /**
     * The entity the steps after a hold move with, or {@code null} when they stay put.
     *
     * @since 1.364.0
     */
    public @Nullable Entity follows() {
        return follows;
    }

    /**
     * Plays the whole sequence as a miniature (or a giant) about its location.
     *
     * <pre>{@code
     * sequences.play(effect, SequenceTarget.at(pedestalTop).by(viewer).onlyTo(viewer).scale(0.35));
     * }</pre>
     *
     * <p>Everything is drawn {@code factor} times its written size, round the location: every offset
     * and movement (shape radii and heights, {@code from:}/{@code to:}, {@code y:}, gravity, pull and
     * orbit), display sizes, {@code [RAGDOLL]} and {@code [NPC]} bodies and their flights, and particle
     * spreads and speeds (a {@code count:0} particle keeps its offsets, which are then a direction or a
     * colour). Timing is untouched. Sounds, fireworks, cameras, shakes and lids are drawn as written.
     *
     * <p>A scaled play is a model on a table, with no ground of its own under it: nothing it draws as a
     * display falls below the location's height (debris lands on it instead of falling through), and a
     * scaled {@code [NPC]} body carries no name tag.
     *
     * @param factor how large, above zero; 1 is as written
     * @return a new target
     * @throws IllegalArgumentException if {@code factor} is not above zero
     * @since 1.413.0
     */
    public @NotNull SequenceTarget scale(double factor) {
        if (!(factor > 0) || Double.isInfinite(factor)) {
            throw new IllegalArgumentException("A sequence's scale must be above zero, not " + factor);
        }
        return new SequenceTarget(location, source, target, visibility, radius, values, items, wearing, follows,
                factor);
    }

    /**
     * How large this play is drawn; 1 is as written.
     *
     * @since 1.413.0
     */
    public double scale() {
        return scale;
    }

    /**
     * Whether this play is drawn at another size than written.
     *
     * @since 1.413.0
     */
    public boolean isScaled() {
        return scale != 1.0;
    }

    /** Where this happens. */
    public @NotNull Location location() {
        return location;
    }

    /** Who caused it, or {@code null}. */
    public @Nullable Player source() {
        return source;
    }

    /** What it happened to, or {@code null}. */
    public @Nullable Entity target() {
        return target;
    }

    /** How far away it can be seen, in blocks. */
    public double radius() {
        return radius;
    }

    /**
     * The players who should perceive this, right now.
     *
     * <p>Squared distance, so no square root per player per step. A world that
     * has gone away answers with nobody rather than throwing, because a
     * sequence outliving its world is a normal shutdown, not a bug.
     *
     * @return the observers, possibly empty
     */
    public @NotNull List<Player> observers() {
        World world = location.getWorld();
        if (world == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<Player> remembered = memo;
        if (remembered != null && now - memoAt < TICK_MS) {
            return remembered;
        }
        double limit = radius * radius;
        List<Player> observers = new ArrayList<>();
        for (Player online : world.getPlayers()) {
            // Comparing worlds first: distanceSquared throws when they differ,
            // and getPlayers() can return a player who changed world in between.
            if (!world.equals(online.getWorld())) {
                continue;
            }
            if (online.getLocation().distanceSquared(location) > limit) {
                continue;
            }
            if (visibility != null && !visibility.test(online, source)) {
                continue;
            }
            observers.add(online);
        }
        memo = observers;
        memoAt = now;
        return observers;
    }

    @Override
    public String toString() {
        return "SequenceTarget[" + location.getWorld() + " "
                + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ()
                + ", r=" + radius + ']';
    }
}
