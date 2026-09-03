package net.exylia.lib.session;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Players who see the server as it is, past every mode's isolation.
 *
 * <pre>{@code
 * // The plugin that puts somebody above the game says so once:
 * Watchers.rule(this, player -> staffMode.isActive(player.getUniqueId()));
 *
 * // And every mode hides through here instead of hiding directly:
 * Watchers.hide(plugin, viewer, target);   // was viewer.hidePlayer(plugin, target)
 * }</pre>
 *
 * <h2>What this is for</h2>
 * Every game mode isolates: a match hides the lobby, a lobby hides the match,
 * an arena hides everything outside it, and each of them does it with Bukkit's
 * own {@code hidePlayer}. That is right for the players and exactly wrong for
 * the one person who is there to look — a moderator on duty ends up on an
 * empty server, hidden from by every mode in turn, which is the one situation
 * where being unable to see anybody defeats the point of being there.
 *
 * <p>{@link Sessions} already lets a mode ask <em>who has this player</em>.
 * This answers the question next to it: <em>may I edit what this player
 * sees</em>. The two together are what let a moderator stand inside somebody
 * else's mode without that mode knowing anything about moderation.
 *
 * <h2>One voice is enough</h2>
 * Unlike {@link net.exylia.lib.chat.ChatRule} and
 * {@link net.exylia.lib.cosmetic.Cosmetics}, which are combined with AND
 * because they decide whether to <em>show</em> something, these are combined
 * with OR: a plugin saying somebody is watching is a statement about that
 * player's role, and no other plugin is in a position to contradict it. One
 * rule per plugin, dropped when that plugin is disabled.
 *
 * <h2>What it does not do</h2>
 * It says nothing about who can see the watcher. A moderator who should also
 * be invisible is vanished, which is a different mechanism —
 * {@link net.exylia.lib.packet.Visibility} — working in the other direction,
 * and the two do not interfere: this one only ever declines to hide, and never
 * reveals somebody the packet layer is hiding.
 *
 * @since 1.96.0
 */
public final class Watchers {

    private static final Map<String, WatcherRule> RULES = new ConcurrentHashMap<>();
    private static final Map<String, Consumer<Player>> LISTENERS = new ConcurrentHashMap<>();

    private Watchers() {
        throw new AssertionError("No instances.");
    }

    /**
     * Registers this plugin's rule, replacing its previous one.
     *
     * @param plugin the plugin that knows who is watching
     * @param rule   the rule
     */
    public static void rule(@NotNull Plugin plugin, @NotNull WatcherRule rule) {
        RULES.put(plugin.getName(), rule);
    }

    /**
     * Drops this plugin's rule, so it stops having a say.
     *
     * @param plugin the plugin
     */
    public static void clear(@NotNull Plugin plugin) {
        RULES.remove(plugin.getName());
        LISTENERS.remove(plugin.getName());
    }

    /**
     * Returns whether anything may be hidden from this player.
     *
     * <p>False on a server where no plugin has registered a rule, so a mode
     * can ask unconditionally and behave exactly as it did before.
     *
     * <p>A rule that throws is read as no: a broken opinion must not quietly
     * switch off the isolation a match depends on.
     *
     * @param viewer the player whose screen is about to be edited
     * @return whether they see past every mode's isolation
     */
    public static boolean watching(@NotNull Player viewer) {
        for (WatcherRule rule : RULES.values()) {
            try {
                if (rule.watching(viewer)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // See above: an opinion nobody can evaluate is not an opinion.
            }
        }
        return false;
    }

    /**
     * Hides {@code target} from {@code viewer}, unless the viewer is watching.
     *
     * <p>The drop-in replacement for {@code viewer.hidePlayer(plugin, target)}
     * in a mode's isolation. A watcher is <em>shown</em> instead, not merely
     * left alone: they may have been hidden from before they started watching,
     * and a moderator who goes on duty expects the empty room to fill up.
     *
     * @param plugin the plugin doing the hiding, as Bukkit requires
     * @param viewer the player whose screen it is
     * @param target the player to hide
     */
    public static void hide(@NotNull Plugin plugin, @NotNull Player viewer, @NotNull Player target) {
        if (watching(viewer)) {
            viewer.showPlayer(plugin, target);
        } else {
            viewer.hidePlayer(plugin, target);
        }
    }

    /**
     * Asks to be told when a player starts or stops watching.
     *
     * <p>What a mode needs, and the reason this is not only a predicate. A
     * mode edits a player's screen at the moment something happens to them —
     * they entered a lobby, somebody claimed them — and never looks again. A
     * moderator who goes on duty a tick after that moment is left with the
     * screen the mode already decided on, which is an empty room.
     *
     * <p>Called on whatever thread announced the change, for the player it
     * changed for. Re-apply your own visibility for that one player and
     * nothing else.
     *
     * @param plugin  the mode listening, so the interest goes away with it
     * @param changed what to do about that player
     */
    public static void onChange(@NotNull Plugin plugin, @NotNull Consumer<Player> changed) {
        LISTENERS.put(plugin.getName(), changed);
    }

    /**
     * Announces that this player started or stopped watching.
     *
     * <p>Called by the plugin whose rule changed its answer, after the change
     * is true — a listener that runs first would read the old answer and
     * decide the same thing twice.
     *
     * @param viewer the player whose role changed
     */
    public static void refresh(@NotNull Player viewer) {
        for (Consumer<Player> listener : LISTENERS.values()) {
            try {
                listener.accept(viewer);
            } catch (Throwable ignored) {
                // One mode failing to redraw is not a reason to skip the rest.
            }
        }
    }

    /** Drops one plugin's rule. Called when the plugin disables. */
    public static void release(@NotNull String pluginName) {
        RULES.remove(pluginName);
        LISTENERS.remove(pluginName);
    }

    /** Drops every rule. Called when the library disables. */
    public static void releaseAll() {
        RULES.clear();
        LISTENERS.clear();
    }
}
