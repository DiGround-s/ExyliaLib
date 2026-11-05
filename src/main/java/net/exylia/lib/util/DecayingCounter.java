package net.exylia.lib.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.redis.Channel;
import net.exylia.lib.redis.Channels;
import net.exylia.lib.redis.Message;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * A volume per key that fades with a half-life: how much of something happened
 * <em>recently</em>.
 *
 * <pre>{@code
 * DecayingCounter mined = DecayingCounter.local(Duration.ofHours(2));
 * mined.add("diamond_ore", 1);
 * double recent = mined.value("diamond_ore"); // halves every two hours without new adds
 *
 * // the same volume on every server of the network
 * DecayingCounter market = DecayingCounter.shared(this, "jobs-market", Duration.ofHours(2));
 * }</pre>
 *
 * <p>Each key keeps one value and the instant it was last added to; a read
 * decays it to now ({@code value * 2^(-elapsed / halfLife)}). There is no
 * timer: an idle key costs its entry and nothing else, and is dropped once 32
 * half-lives have passed without an add (it is below a billionth of what it
 * was by then). At most 100,000 keys are kept.
 *
 * <h2>Shared</h2>
 * {@link #shared} also sends what this server added to the others every five
 * seconds over a {@link Channels} channel, and adds what they send, decayed by
 * how old it is. Without Redis it is a local counter. A server that starts
 * later only counts from then on, and a batch sent while a server was
 * restarting is lost: a decaying volume forgives both within a few half-lives.
 * Keys containing a line break are counted locally only.
 *
 * <h2>Threads</h2>
 * Every method is safe from any thread and memory only.
 *
 * @since 1.478.0
 */
public final class DecayingCounter {

    private static final int MAX_KEYS = 100_000;
    private static final int FORGET_AFTER_HALF_LIVES = 32;
    private static final long SHARE_TICKS = 100L;

    /** A value as of an instant. */
    private record Entry(double value, long at) {
    }

    private final long halfLifeMillis;
    private final LongSupplier clock;
    private final Cache<String, Entry> entries;
    private final @Nullable ConcurrentMap<String, Entry> outbox;
    private volatile @Nullable Channel channel;
    private volatile @Nullable Channel.Subscription subscription;
    private volatile @Nullable TaskHandle timer;

    DecayingCounter(Duration halfLife, LongSupplier clock, boolean shared) {
        if (halfLife.isNegative() || halfLife.isZero()) {
            throw new IllegalArgumentException("A half-life must be positive, got " + halfLife);
        }
        this.halfLifeMillis = halfLife.toMillis();
        this.clock = clock;
        this.entries = Caffeine.newBuilder()
                .maximumSize(MAX_KEYS)
                .expireAfterWrite(Math.max(1, halfLifeMillis) * FORGET_AFTER_HALF_LIVES, TimeUnit.MILLISECONDS)
                .build();
        this.outbox = shared ? new ConcurrentHashMap<>() : null;
    }

    /**
     * A counter on this server only.
     *
     * @param halfLife how long it takes an untouched value to halve
     */
    public static @NotNull DecayingCounter local(@NotNull Duration halfLife) {
        return new DecayingCounter(Objects.requireNonNull(halfLife, "halfLife"), System::currentTimeMillis, false);
    }

    /**
     * A counter shared by every server that opens one under the same plugin
     * and name. Stops sharing when the plugin disables, or on {@link #close()}.
     *
     * @param plugin   the plugin it belongs to
     * @param name     the channel name, the same on every server
     * @param halfLife how long it takes an untouched value to halve
     */
    public static @NotNull DecayingCounter shared(@NotNull Plugin plugin, @NotNull String name,
                                                  @NotNull Duration halfLife) {
        DecayingCounter counter = new DecayingCounter(Objects.requireNonNull(halfLife, "halfLife"),
                System::currentTimeMillis, true);
        Channel channel = Channels.of(plugin).channel("decaying:" + Objects.requireNonNull(name, "name"));
        counter.channel = channel;
        counter.subscription = channel.subscribe(counter::receive);
        counter.timer = Tasks.of(plugin).runAsyncTimer(SHARE_TICKS, SHARE_TICKS, counter::share);
        return counter;
    }

    /** The half-life it was made with. */
    public @NotNull Duration halfLife() {
        return Duration.ofMillis(halfLifeMillis);
    }

    /**
     * Adds to a key's volume now. A negative amount takes away; the value
     * never goes below zero.
     */
    public void add(@NotNull String key, double amount) {
        Objects.requireNonNull(key, "key");
        if (amount == 0 || !Double.isFinite(amount)) return;
        long now = clock.getAsLong();
        merge(entries.asMap(), key, amount, now);
        ConcurrentMap<String, Entry> pending = outbox;
        if (pending != null && key.indexOf('\n') < 0) merge(pending, key, amount, now);
    }

    /** A key's volume now; {@code 0} for a key never added to. */
    public double value(@NotNull String key) {
        Entry entry = entries.getIfPresent(Objects.requireNonNull(key, "key"));
        return entry == null ? 0 : decay(entry.value(), clock.getAsLong() - entry.at(), halfLifeMillis);
    }

    /** Every key's volume now. A copy: changing it changes nothing. */
    public @NotNull Map<String, Double> values() {
        long now = clock.getAsLong();
        Map<String, Double> values = new HashMap<>();
        entries.asMap().forEach((key, entry) -> values.put(key, decay(entry.value(), now - entry.at(), halfLifeMillis)));
        return values;
    }

    /** Forgets a key. */
    public void reset(@NotNull String key) {
        entries.invalidate(Objects.requireNonNull(key, "key"));
    }

    /** Stops sharing; the local values stay readable. Idempotent. */
    public void close() {
        Channel.Subscription open = subscription;
        if (open != null) open.close();
        TaskHandle running = timer;
        if (running != null) running.cancel();
        subscription = null;
        timer = null;
        channel = null;
    }

    /**
     * {@code value} decayed over {@code elapsedMillis}: halved once per half-life.
     * A negative elapsed time (a clock stepping back) decays nothing.
     */
    public static double decay(double value, long elapsedMillis, long halfLifeMillis) {
        if (elapsedMillis <= 0) return value;
        return value * Math.pow(0.5, (double) elapsedMillis / halfLifeMillis);
    }

    private void merge(ConcurrentMap<String, Entry> map, String key, double amount, long now) {
        map.merge(key, new Entry(Math.max(0, amount), now), (old, added) ->
                new Entry(Math.max(0, decay(old.value(), now - old.at(), halfLifeMillis) + amount), now));
    }

    /** Sends what this server added since the last batch, decayed to now. */
    void share() {
        ConcurrentMap<String, Entry> pending = outbox;
        Channel open = channel;
        if (pending == null || open == null || pending.isEmpty()) return;
        long now = clock.getAsLong();
        StringBuilder payload = new StringBuilder().append(now);
        for (String key : pending.keySet()) {
            Entry entry = pending.remove(key);
            if (entry == null) continue;
            double value = decay(entry.value(), now - entry.at(), halfLifeMillis);
            if (value > 0) payload.append('\n').append(value).append('\t').append(key);
        }
        open.publish(payload.toString());
    }

    /** Adds what another server sent, decayed by the batch's age. */
    void receive(Message message) {
        if (message.local()) return;
        String[] lines = message.payload().split("\n");
        long sent;
        try {
            sent = Long.parseLong(lines[0]);
        } catch (NumberFormatException malformed) {
            return;
        }
        long now = clock.getAsLong();
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            try {
                double value = decay(Double.parseDouble(line.substring(0, tab)), now - sent, halfLifeMillis);
                if (value > 0 && Double.isFinite(value)) merge(entries.asMap(), line.substring(tab + 1), value, now);
            } catch (NumberFormatException malformed) {
                // a line another version wrote differently; skip it
            }
        }
    }
}
