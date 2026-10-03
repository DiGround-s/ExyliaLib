package net.exylia.lib.client.internal;

import net.exylia.lib.client.ClientBrand;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Which client integrations are installed, and which one a player is running.
 *
 * <p>Adding a client means adding one {@link ClientLink} and one line in
 * {@link #load}. Nothing else in the module, and nothing at all outside it,
 * knows how many there are.
 *
 * <p>A player's client is looked up once and remembered until they leave.
 * Asking Apollo or Feather on every waypoint would be a map lookup inside
 * another plugin's manager, on a path that runs whenever a game updates its
 * markers.
 */
public final class ClientRegistry {

    /**
     * Replaced whole, never edited in place: {@link #load} can run again while
     * other threads are reading it, when a client plugin enables after this
     * library.
     */
    private static volatile List<ClientLink> links = List.of();
    private static final Map<UUID, ClientLink> BY_PLAYER = new ConcurrentHashMap<>();

    /** Stands in for "checked, and this player runs no modified client". */
    private static final ClientLink NONE = new VanillaLink();

    private ClientRegistry() {
    }

    /**
     * Loads whichever integrations are installed.
     *
     * <p>Called once at startup. A client whose plugin is missing is skipped
     * silently: most servers have neither, and that is not a problem worth a
     * line in the log.
     *
     * @param logger where the outcome is reported
     */
    public static void load(Logger logger) {
        List<ClientLink> found = new ArrayList<>();
        add(found, logger, "com.lunarclient.apollo.Apollo",
                "net.exylia.lib.client.internal.ApolloLink");
        add(found, logger, "net.digitalingot.feather.serverapi.api.FeatherAPI",
                "net.exylia.lib.client.internal.FeatherLink");

        links = List.copyOf(found);
        BY_PLAYER.clear();

        if (!found.isEmpty()) {
            List<String> names = new ArrayList<>(found.size());
            for (ClientLink link : found) {
                names.add(link.brand().display());
            }
            logger.info("Client integrations: " + String.join(", ", names) + ".");
        }
    }

    /**
     * Adds an integration, if its client API is on the classpath.
     *
     * <p>Neither class is named in code. Naming {@code ApolloLink} here would
     * make the JVM verify it, and verifying it means resolving every Apollo
     * type it mentions: a server without Apollo, or with a version that moved
     * a class, would fail to load this one before the check inside it ever
     * ran. Reflection keeps the decision at runtime, where it belongs.
     *
     * @param found     where a usable integration is added
     * @param logger    where a broken integration is reported
     * @param apiClass  a class the client's own plugin provides
     * @param linkClass the integration, with a static {@code create()}
     */
    private static void add(List<ClientLink> found, Logger logger, String apiClass, String linkClass) {
        ClassLoader loader = ClientRegistry.class.getClassLoader();
        try {
            Class.forName(apiClass, false, loader);
        } catch (Throwable absent) {
            // The normal case on most servers, and not worth a line in the log.
            return;
        }
        try {
            java.lang.reflect.Method create = Class.forName(linkClass, true, loader)
                    .getDeclaredMethod("create");
            create.setAccessible(true);
            ClientLink link = (ClientLink) create.invoke(null);
            if (link != null && link.available()) {
                found.add(link);
            }
        } catch (Throwable broken) {
            // The API is here but not the one this was built against: an
            // upgrade on their side, never a reason to stop the server.
            logger.warning("Client integration " + apiClass
                    + " is installed but incompatible, so it was skipped: " + broken);
        }
    }

    /** Returns whether any integration is installed at all. */
    public static boolean anyAvailable() {
        return !links.isEmpty();
    }

    /**
     * Returns the integration for a player's client.
     *
     * <p>Never {@code null}: a vanilla player gets a link that does nothing,
     * so no caller has to check first.
     */
    public static ClientLink of(Player player) {
        List<ClientLink> current = links;
        if (current.isEmpty()) {
            return NONE;
        }
        return BY_PLAYER.computeIfAbsent(player.getUniqueId(), id -> detect(current, player));
    }

    private static ClientLink detect(List<ClientLink> current, Player player) {
        for (ClientLink link : current) {
            try {
                if (link.recognises(player)) {
                    return link;
                }
            } catch (Throwable ignored) {
                // A broken integration must not stop the others from answering.
            }
        }
        return NONE;
    }

    /**
     * Returns which client a player runs.
     *
     * @param player the player
     * @return the brand, {@link ClientBrand#VANILLA} when none was recognised
     */
    public static ClientBrand brandOf(Player player) {
        return of(player).brand();
    }

    /**
     * Forgets what was detected for a player.
     *
     * <p>Called when they leave, and when they are detected too early: a
     * modified client announces itself a moment after joining, so a lookup made
     * before that would otherwise be remembered as vanilla for the whole
     * session.
     */
    public static void forget(UUID playerId) {
        BY_PLAYER.remove(playerId);
        for (ClientLink link : links) {
            link.forget(playerId);
        }
    }

    /** Drops every integration and every detection. Used on shutdown and by tests. */
    public static void clear() {
        links = List.of();
        BY_PLAYER.clear();
    }

    /** Installs links directly. For tests. */
    static void install(List<ClientLink> links) {
        ClientRegistry.links = List.copyOf(links);
        BY_PLAYER.clear();
    }

    /** A player running nothing this library integrates with. */
    private static final class VanillaLink implements ClientLink {

        @Override
        public ClientBrand brand() {
            return ClientBrand.VANILLA;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public boolean recognises(Player player) {
            return true;
        }
    }
}
