package net.exylia.lib.network;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * One server running the same plugin in the same group, as its last heartbeat
 * described it; see {@link Instances}.
 *
 * @param server    the server's name, the one {@code toServer} and
 *                  {@link PluginNetwork#send(String, String, String)} reach it by
 * @param accepting whether it takes players at all (a lobby that only routes does not)
 * @param tps       its ticks per second when it published, 20 at best
 * @param total     every player it counts, against its total soft cap
 * @param parts     players and soft cap per part of it (an arena, a queue), by id
 * @param seenAt    when this server last heard of it, in epoch millis of this server's clock
 * @since 1.383.0
 */
public record Instance(@NotNull String server, boolean accepting, double tps, @NotNull Load total,
                       @NotNull Map<String, Load> parts, long seenAt) {

    public Instance {
        parts = Map.copyOf(parts);
    }

    /**
     * A head count against a soft cap.
     *
     * @param players  how many are in
     * @param capacity the soft cap; 0 or less for none
     */
    public record Load(int players, int capacity) {

        /** Nobody, no cap. */
        public static final Load EMPTY = new Load(0, 0);

        /** Whether one more player still fits under the cap. */
        public boolean hasRoom() {
            return capacity <= 0 || players < capacity;
        }
    }

    /** The players of one part, or the whole server's for {@code null}; 0 for a part it does not have. */
    public int players(@Nullable String part) {
        if (part == null) return total.players();
        Load load = parts.get(part);
        return load == null ? 0 : load.players();
    }

    /** Whether it takes one more player, into that part when one is named (a part it does not have takes nobody). */
    public boolean takes(@Nullable String part) {
        if (!accepting || !total.hasRoom()) return false;
        if (part == null) return true;
        Load load = parts.get(part);
        return load != null && load.hasRoom();
    }
}
