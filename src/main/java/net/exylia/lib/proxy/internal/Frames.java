package net.exylia.lib.proxy.internal;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The strings on the Redis channels, in both directions.
 *
 * <p>A request goes to {@code <prefix>:bridge:proxy} as
 * {@code <server-id>|<carrier uuid or empty>|<module>|<id>|<payload>}; the
 * proxy answers on {@code <prefix>:bridge:<server-id>} as
 * {@code <module>|<id>|<status>|<carrier uuid or empty>|<detail>}, and says
 * what it has to say unasked on the same channel with id 0. The payload and
 * the detail come last and may contain pipes. ExyliaProxyUtils reads and
 * writes exactly this, so a change here is a change there.
 */
@ApiStatus.Internal
public final class Frames {

    /** What every channel of the bridge starts with, after the network's prefix. */
    public static final String INFIX = ":bridge:";

    /** The channel the proxy listens on, after the network's prefix. */
    public static final String PROXY = "proxy";

    private Frames() {
        throw new AssertionError("No instances.");
    }

    /** One answer or push, as received. */
    public record Answer(@NotNull String module, int id, int status, @Nullable UUID carrier,
                         @NotNull String detail) {
    }

    public static @NotNull String request(@NotNull String server, @Nullable UUID carrier,
                                          @NotNull String module, int id, @NotNull String payload) {
        return server + '|' + (carrier == null ? "" : carrier) + '|' + module + '|' + id + '|' + payload;
    }

    /**
     * Reads an answer.
     *
     * @throws IllegalArgumentException if the text is not one; the caller drops it
     */
    public static @NotNull Answer decode(@NotNull String raw) {
        String[] parts = raw.split("\\|", 5);
        if (parts.length != 5 || parts[0].isEmpty()) {
            throw new IllegalArgumentException("expected module|id|status|carrier|detail");
        }
        UUID carrier = parts[3].isEmpty() ? null : UUID.fromString(parts[3]);
        return new Answer(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), carrier, parts[4]);
    }

    public static @NotNull String channelOf(@NotNull String prefix, @NotNull String server) {
        return prefix + INFIX + server;
    }
}
