package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.ScreenMarkup;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which screen a plugin's menu id opens in SCREEN style, from {@code <screen routes="...">}.
 *
 * <p>Internal: free to change without notice.
 */
public final class ScreenRoutes {

    /** The state key that holds the chest menu a routed screen was opened for. */
    public static final String MENU = "~menu";

    /** A screen for a menu id, and the state it opens with ({@code %name%} filled from the menu's context). */
    public record Route(ScreenMarkup markup, Map<String, String> state) {

        /** The state to open with, each {@code %name%} replaced by that context value. */
        public Map<String, Object> state(Map<String, ?> context) {
            Map<String, Object> out = new LinkedHashMap<>();
            state.forEach((key, written) -> {
                String value = written;
                for (Map.Entry<String, ?> entry : context.entrySet()) {
                    if (value.indexOf('%') < 0) break;
                    Object raw = entry.getValue() instanceof java.util.function.Supplier<?> reader ? reader.get() : entry.getValue();
                    value = value.replace("%" + entry.getKey() + "%", raw == null ? "" : String.valueOf(raw));
                }
                // A placeholder the context does not answer leaves the key to the screen's own default.
                if (!value.matches(".*%[A-Za-z0-9_.-]+%.*")) out.put(key, value);
            });
            return out;
        }
    }

    private static final Map<String, Map<String, Route>> ROUTES = new ConcurrentHashMap<>();

    private ScreenRoutes() {
    }

    /** Replaces the routes {@code markup} declares for {@code plugin} with those written in {@code routes}. */
    public static void declare(Plugin plugin, ScreenMarkup markup, @Nullable String routes) {
        Map<String, Route> mine = ROUTES.computeIfAbsent(plugin.getName(), name -> new ConcurrentHashMap<>());
        mine.values().removeIf(route -> route.markup() == markup);
        parse(routes).forEach((menu, state) -> mine.put(menu, new Route(markup, state)));
    }

    /** The route of a menu id, written with or without its namespace, or {@code null}. */
    public static @Nullable Route find(Plugin plugin, String menuId) {
        Map<String, Route> mine = ROUTES.get(plugin.getName());
        if (mine == null || mine.isEmpty()) return null;
        Route exact = mine.get(menuId);
        if (exact != null) return exact;
        int colon = menuId.indexOf(':');
        return colon < 0 ? null : mine.get(menuId.substring(colon + 1));
    }

    /** Forgets a disabled plugin's routes. */
    public static void release(Plugin plugin) {
        ROUTES.remove(plugin.getName());
    }

    /**
     * {@code "player/queue_kit:tab=play,category=%category_id% settings:tab=settings"}: routes split by spaces;
     * in each, the parts after a {@code :} holding {@code =} are the state, the rest is the menu id (which may
     * carry its namespace, {@code practice:kits}).
     */
    static Map<String, Map<String, String>> parse(@Nullable String routes) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (routes == null || routes.isBlank()) return out;
        for (String token : routes.trim().split("\\s+")) {
            String[] parts = token.split(":");
            StringBuilder id = new StringBuilder();
            Map<String, String> state = new LinkedHashMap<>();
            for (String part : parts) {
                if (part.contains("=")) {
                    for (String pair : part.split(",")) {
                        int eq = pair.indexOf('=');
                        if (eq > 0) state.put(pair.substring(0, eq), pair.substring(eq + 1));
                    }
                } else {
                    if (!id.isEmpty()) id.append(':');
                    id.append(part);
                }
            }
            if (!id.isEmpty()) out.put(id.toString(), state);
        }
        return out;
    }
}
