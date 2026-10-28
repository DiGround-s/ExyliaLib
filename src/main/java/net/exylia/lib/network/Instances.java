package net.exylia.lib.network;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Several servers running the same plugin as one pool: each publishes its
 * head count on a heartbeat, every one of them reads everybody's, and a player
 * is handed to the best one with a note the arriving server reads once they
 * are there.
 *
 * <pre>{@code
 * PluginInstances instances = Instances.of(this, "randomkits");
 * instances.publish(() -> Instances.status(!routerOnly, new Instance.Load(playing(), 80), perArena()));
 * instances.onArrival((player, note) -> join(player, note));
 *
 * instances.pick(Instances.Mode.FILL_FIRST, "castle").ifPresent(best ->
 *         instances.handover(player, best.server(), "castle"));
 * }</pre>
 *
 * <p>Rides on the plugin's {@link net.exylia.lib.redis.Channels channels} and
 * {@link Network}; without Redis the pool is this server alone.
 *
 * @since 1.383.0
 */
public final class Instances {

    private Instances() {
        throw new AssertionError("No instances.");
    }

    /** Which instance a player is sent to among those with room. */
    public enum Mode {
        /** The emptiest: spreads players evenly. */
        LEAST_PLAYERS,
        /** The fullest still below its cap: keeps games dense and leaves the rest empty. */
        FILL_FIRST
    }

    /** What a heartbeat says about this server; {@link #status} builds one. */
    public record Status(boolean accepting, @NotNull Instance.Load total, @NotNull Map<String, Instance.Load> parts) {

        public Status {
            parts = Map.copyOf(parts);
        }
    }

    /**
     * The plugin's pool in one group. Create it once, from {@code onEnable}, and
     * keep it: it listens from the moment it is made, and its timers and
     * listeners end with the plugin.
     *
     * @param plugin the plugin every instance runs
     * @param group  servers sharing a group form one pool; blank for {@code default}
     * @return the pool
     */
    public static @NotNull PluginInstances of(@NotNull Plugin plugin, @Nullable String group) {
        String name = group == null || group.isBlank() ? "default" : group.trim().toLowerCase(Locale.ROOT);
        return new PluginInstances(plugin, name);
    }

    /** A status, for {@link PluginInstances#publish}. */
    public static @NotNull Status status(boolean accepting, @NotNull Instance.Load total,
                                         @NotNull Map<String, Instance.Load> parts) {
        return new Status(accepting, total, parts);
    }

    /**
     * The best instance among these for one more player, pure.
     *
     * <p>Only instances seen within {@code maxAge} that {@link Instance#takes take}
     * the player count. A tie goes to {@code preferred} (this server, so nobody
     * is moved for nothing), then to the higher TPS, then by name.
     *
     * @param instances what is known
     * @param mode      how to choose
     * @param part      the part the player wants, or {@code null} for any
     * @param preferred the instance a tie goes to, or {@code null}
     * @param now       the current epoch millis
     * @param maxAge    how old a heartbeat may be, in millis
     * @return the instance to send them to, or empty when none takes them
     */
    public static @NotNull Optional<Instance> pick(@NotNull Collection<Instance> instances, @NotNull Mode mode,
                                                   @Nullable String part, @Nullable String preferred,
                                                   long now, long maxAge) {
        Comparator<Instance> load = Comparator.comparingInt(instance -> instance.players(part));
        if (mode == Mode.FILL_FIRST) load = load.reversed();
        Comparator<Instance> order = load
                .thenComparing(instance -> !instance.server().equalsIgnoreCase(String.valueOf(preferred)))
                .thenComparing(Comparator.comparingDouble(Instance::tps).reversed())
                .thenComparing(instance -> instance.server().toLowerCase(Locale.ROOT));
        return instances.stream()
                .filter(instance -> isFresh(instance, now, maxAge))
                .filter(instance -> instance.takes(part))
                .min(order);
    }

    /** Whether a heartbeat is recent enough to trust. */
    public static boolean isFresh(@NotNull Instance instance, long now, long maxAge) {
        return now - instance.seenAt() <= maxAge;
    }

    // ------------------------------------------------------------------ wire

    private static final String FIELD = "\t";
    private static final String LINE = "\n";

    /** A heartbeat: {@code server, accepting, tps, players, cap}, then one line per part. */
    static @NotNull String encode(@NotNull String server, @NotNull Status status, double tps) {
        StringBuilder out = new StringBuilder()
                .append(server).append(FIELD).append(status.accepting() ? 1 : 0).append(FIELD)
                .append(String.format(Locale.ROOT, "%.2f", tps)).append(FIELD)
                .append(status.total().players()).append(FIELD).append(status.total().capacity());
        status.parts().forEach((id, part) -> {
            if (id.contains(FIELD) || id.contains(LINE)) return;
            out.append(LINE).append(id).append(FIELD).append(part.players()).append(FIELD).append(part.capacity());
        });
        return out.toString();
    }

    /** A heartbeat read back, stamped as seen now; {@code null} for one that does not read. */
    static @Nullable Instance decode(@NotNull String payload, long seenAt) {
        try {
            String[] lines = payload.split(LINE);
            String[] head = lines[0].split(FIELD, -1);
            if (head.length < 5 || head[0].isBlank()) return null;
            Map<String, Instance.Load> parts = new LinkedHashMap<>();
            for (int index = 1; index < lines.length; index++) {
                String[] line = lines[index].split(FIELD, -1);
                if (line.length < 3) continue;
                parts.put(line[0], new Instance.Load(Integer.parseInt(line[1]), Integer.parseInt(line[2])));
            }
            return new Instance(head[0], "1".equals(head[1]), Double.parseDouble(head[2]),
                    new Instance.Load(Integer.parseInt(head[3]), Integer.parseInt(head[4])), parts, seenAt);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
