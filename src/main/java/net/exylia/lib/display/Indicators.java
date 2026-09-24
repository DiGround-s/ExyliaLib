package net.exylia.lib.display;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.display.internal.DisplayRuntime;
import net.exylia.lib.text.Text;
import net.exylia.lib.util.sequence.SequenceTarget;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Numbers that pop out of something and float away: damage, healing, a label.
 *
 * <pre>{@code
 * Indicators indicators = Indicators.of(this);
 *
 * indicators.damage(mob, 7.5, false);   // "7.5" in {error}
 * indicators.damage(mob, 12, true);     // "✦ 12" in bold {warning}
 * indicators.heal(mob, 4);              // "+4 ❤" in {success}
 * }</pre>
 *
 * <h2>Readable in a fight</h2>
 * A sword hitting five times a second is five numbers a second, and five
 * numbers stacked on one head is noise. So two hits of the same kind within
 * {@value #MERGE_MS} ms are one number: the old one is taken down and the sum
 * shown in its place, where it was. No entity carries more than
 * {@value #MAX_PER_ENTITY} at once; the oldest goes first.
 *
 * <p>Each one is a text display that pops in, rises and shrinks away in under a
 * second, lit at 15 and seen by the players within {@link #range(double)}
 * blocks (24 by default) &mdash; a number further away than that is not
 * information. The colours are palette roles, so the server's palette decides
 * them.
 *
 * <p>Call from the entity's own thread, which is where a damage or heal event
 * already runs: its position is read there.
 *
 * @since 1.197.0
 */
public final class Indicators {

    /** Two hits closer together than this are shown as one number. */
    public static final long MERGE_MS = 300L;

    /** The most numbers one entity carries at once. */
    public static final int MAX_PER_ENTITY = 4;

    private static final String DAMAGE = "{error}%amount%";
    private static final String CRIT = "{warning}&l✦ %amount%";
    private static final String HEAL = "{success}+%amount% ❤";

    /** How far the numbers scatter sideways, so a burst does not stack in one column. */
    private static final double JITTER = 0.3;

    /** Pops in, then rises and fades out by shrinking. Shared by every number. */
    static final DisplayMotion FLOAT = DisplayMotion.chain(
            DisplayMotion.builder().life(80).from(0, 0, 0).to(0, 0.12, 0)
                    .scale(0.6, 1.0).ease(DisplayMotion.Easing.OUT).build(),
            DisplayMotion.builder().life(620).from(0, 0.12, 0).to(0, 0.8, 0)
                    .scale(1.0, 0.3).ease(DisplayMotion.Easing.OUT).build());

    private static final Map<String, Indicators> BY_PLUGIN = new ConcurrentHashMap<>();

    /** Draws one number. A seam, so the merging can be tested without packets. */
    @FunctionalInterface
    interface Shower {
        @Nullable DisplayHandle show(@NotNull Component text, @NotNull Location at,
                                     @NotNull List<Player> viewers);
    }

    private final Shower shower;
    private final LongSupplier clock;
    private volatile double range = 24.0;

    /**
     * What each entity has on screen, forgotten a few seconds after its last
     * number: nothing here outlives the numbers it tracks.
     */
    private final Cache<UUID, Stack> stacks = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofSeconds(3))
            .maximumSize(10_000)
            .build();

    Indicators(@NotNull Shower shower, @NotNull LongSupplier clock) {
        this.shower = shower;
        this.clock = clock;
    }

    /**
     * This plugin's indicators.
     *
     * @param plugin the plugin whose effect they are
     * @return its indicators, the same instance every time
     */
    public static @NotNull Indicators of(@NotNull Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), owner -> new Indicators(
                (text, at, viewers) -> DisplayRuntime.show(owner,
                        DisplayModel.text(text).light(15), FLOAT, at, viewers),
                System::currentTimeMillis));
    }

    /**
     * How far away the numbers can be seen, in blocks.
     *
     * @param blocks the radius; 24 by default
     * @return these indicators
     */
    public @NotNull Indicators range(double blocks) {
        this.range = Math.max(0.0, blocks);
        return this;
    }

    /**
     * Damage taken.
     *
     * @param entity what was hurt
     * @param amount how much; nothing is shown for zero or less
     * @param crit   whether it was a critical hit, which is drawn louder; a
     *               merged number stays critical if any of its hits was
     */
    public void damage(@NotNull Entity entity, double amount, boolean crit) {
        if (amount > 0) {
            number(entity, Kind.DAMAGE, amount, crit, null);
        }
    }

    /**
     * Health regained.
     *
     * @param entity what healed
     * @param amount how much; nothing is shown for zero or less
     */
    public void heal(@NotNull Entity entity, double amount) {
        if (amount > 0) {
            number(entity, Kind.HEAL, amount, false, null);
        }
    }

    /**
     * Any line, drawn the same way.
     *
     * <p>Merged by replacement rather than by sum: a second label within the
     * window takes the first one's place, so a counter updating on every hit
     * reads as one number changing.
     *
     * @param entity what it floats out of
     * @param text   what it says, already coloured
     */
    public void text(@NotNull Entity entity, @NotNull Component text) {
        number(entity, Kind.TEXT, 0, false, text);
    }

    private void number(Entity entity, Kind kind, double amount, boolean crit,
                        @Nullable Component label) {
        Location base = entity.getLocation();
        List<Player> viewers = SequenceTarget.at(base).within(range).observers();
        if (viewers.isEmpty()) {
            return;
        }
        double height = entity.getHeight() + 0.35;
        Stack stack = stacks.get(entity.getUniqueId(), id -> new Stack());
        synchronized (stack) {
            long now = clock.getAsLong();
            stack.live.removeIf(shown -> shown.handle != null && !shown.handle.isShowing());
            Shown last = stack.last[kind.ordinal()];
            double total = amount;
            boolean loud = crit;
            double jx;
            double jz;
            if (last != null && now - last.at <= MERGE_MS && stack.live.remove(last)) {
                if (last.handle != null) {
                    last.handle.remove();
                }
                total += last.total;
                loud |= last.crit;
                jx = last.jx;
                jz = last.jz;
            } else {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                jx = random.nextDouble(-JITTER, JITTER);
                jz = random.nextDouble(-JITTER, JITTER);
            }
            Component text = switch (kind) {
                case DAMAGE -> Text.of(loud ? CRIT : DAMAGE).with("%amount%", format(total)).build();
                case HEAL -> Text.of(HEAL).with("%amount%", format(total)).build();
                case TEXT -> label;
            };
            Location at = base.clone().add(jx, height, jz);
            Shown shown = new Shown(shower.show(text, at, viewers), now, total, loud, jx, jz);
            stack.last[kind.ordinal()] = shown;
            stack.live.addLast(shown);
            while (stack.live.size() > MAX_PER_ENTITY) {
                Shown oldest = stack.live.removeFirst();
                if (oldest.handle != null) {
                    oldest.handle.remove();
                }
            }
        }
    }

    /** One decimal, and none when it is whole: "7.5", "12". */
    static @NotNull String format(double amount) {
        double rounded = Math.round(amount * 10.0) / 10.0;
        return rounded == Math.rint(rounded)
                ? Long.toString((long) rounded)
                : String.format(Locale.ROOT, "%.1f", rounded);
    }

    /** How many numbers an entity has on screen right now, for tests. */
    int live(@NotNull UUID entity) {
        Stack stack = stacks.getIfPresent(entity);
        if (stack == null) {
            return 0;
        }
        synchronized (stack) {
            stack.live.removeIf(shown -> shown.handle != null && !shown.handle.isShowing());
            return stack.live.size();
        }
    }

    private enum Kind { DAMAGE, HEAL, TEXT }

    /** What one entity has on screen. */
    private static final class Stack {
        private final Deque<Shown> live = new ArrayDeque<>(MAX_PER_ENTITY + 1);
        private final Shown[] last = new Shown[Kind.values().length];
    }

    /**
     * One number on screen, and what a merge needs from it.
     *
     * <p>A class, not a record: it is found in the stack by identity, and two
     * numbers with the same value are still two numbers.
     */
    private static final class Shown {
        private final @Nullable DisplayHandle handle;
        private final long at;
        private final double total;
        private final boolean crit;
        private final double jx;
        private final double jz;

        private Shown(@Nullable DisplayHandle handle, long at, double total, boolean crit,
                      double jx, double jz) {
            this.handle = handle;
            this.at = at;
            this.total = total;
            this.crit = crit;
            this.jx = jx;
            this.jz = jz;
        }
    }
}
