package net.exylia.lib.chat.internal;

import net.exylia.lib.chat.ChatRule;
import net.exylia.lib.debug.Debug;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The chat module's working parts: one rule per plugin, and the answer they
 * agree on.
 *
 * <p>Nothing here is remembered between messages. A rule is asked while the
 * message is being delivered and its answer is used once, so a player who
 * joins an event mid-sentence is already inside it for the next line.
 */
public final class ChatRuntime {

    /** One plugin, one rule. */
    private static final Map<String, ChatRule> RULES = new ConcurrentHashMap<>();
    /** Plugins whose rule threw, so a broken rule is reported once. */
    private static final Set<String> BROKEN = ConcurrentHashMap.newKeySet();

    private static volatile Plugin lib;

    private ChatRuntime() {
    }

    /** Remembers the library plugin, for the one line a broken rule logs. */
    public static void init(Plugin plugin) {
        lib = plugin;
    }

    public static void rule(String pluginName, ChatRule rule) {
        RULES.put(pluginName, rule);
        BROKEN.remove(pluginName);
    }

    public static void release(String pluginName) {
        RULES.remove(pluginName);
        BROKEN.remove(pluginName);
    }

    public static void shutdown() {
        RULES.clear();
        BROKEN.clear();
    }

    /** Whether any plugin has a say at all, so an untouched server pays nothing. */
    public static boolean idle() {
        return RULES.isEmpty();
    }

    /**
     * Asks every rule and returns what they agree on.
     *
     * <p>A rule that throws is ignored for that message rather than allowed to
     * take chat down with it: a server whose chat plugin works must keep
     * talking even when somebody's rule does not.
     */
    public static boolean canHear(Player listener, Player speaker) {
        if (listener.equals(speaker)) {
            return true;
        }
        for (Map.Entry<String, ChatRule> entry : RULES.entrySet()) {
            try {
                if (!entry.getValue().canHear(listener, speaker)) {
                    return false;
                }
            } catch (Throwable error) {
                if (BROKEN.add(entry.getKey()) && lib != null) {
                    Debug.of(lib).error("Chat rule of " + entry.getKey()
                            + " threw and is ignored: " + error);
                }
            }
        }
        return true;
    }
}
