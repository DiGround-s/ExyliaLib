package net.exylia.lib.chat.internal;

import net.exylia.lib.chat.ChatRule;
import net.exylia.lib.debug.Debug;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Function;

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
    /** Players no rule is asked about, in either direction. */
    private static final Set<UUID> BYPASS = ConcurrentHashMap.newKeySet();
    /** One tag per plugin, in plugin-name order so every line reads the same way. */
    private static final Map<String, Function<Player, @Nullable Component>> TAGS = new ConcurrentSkipListMap<>();

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
        TAGS.remove(pluginName);
        BROKEN.remove(pluginName);
    }

    public static void tag(String pluginName, Function<Player, @Nullable Component> tag) {
        TAGS.put(pluginName, tag);
        BROKEN.remove(pluginName);
    }

    public static void untag(String pluginName) {
        TAGS.remove(pluginName);
    }

    /**
     * Every plugin's tag for this speaker, each followed by a space, or null when none has one. A tag that
     * throws is left out of that line and reported once, like a broken rule.
     */
    public static @Nullable Component tags(Player speaker) {
        if (TAGS.isEmpty()) {
            return null;
        }
        Component out = null;
        for (Map.Entry<String, Function<Player, @Nullable Component>> entry : TAGS.entrySet()) {
            Component tag;
            try {
                tag = entry.getValue().apply(speaker);
            } catch (Throwable error) {
                if (BROKEN.add(entry.getKey()) && lib != null) {
                    Debug.of(lib).error("Chat tag of " + entry.getKey() + " threw and is left out: " + error);
                }
                continue;
            }
            if (tag == null || tag.equals(Component.empty())) {
                continue;
            }
            out = (out == null ? Component.empty() : out).append(tag).append(Component.space());
        }
        return out;
    }

    public static void shutdown() {
        TAGS.clear();
        RULES.clear();
        BROKEN.clear();
        BYPASS.clear();
    }

    public static void bypass(UUID uuid, boolean bypass) {
        if (bypass) {
            BYPASS.add(uuid);
        } else {
            BYPASS.remove(uuid);
        }
    }

    public static boolean bypassing(UUID uuid) {
        return BYPASS.contains(uuid);
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
        // Asked before the rules rather than added to them: rules are combined
        // with AND, so no rule of its own could ever undo another plugin's no.
        if (!BYPASS.isEmpty()
                && (BYPASS.contains(listener.getUniqueId()) || BYPASS.contains(speaker.getUniqueId()))) {
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
