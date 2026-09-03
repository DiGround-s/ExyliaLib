package net.exylia.lib.cosmetic;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Whether a player is somebody whose cosmetics should be drawn.
 *
 * <pre>{@code
 * // A plugin that lends a player somebody else's body says so once:
 * Cosmetics.rule(this, player -> !staffMode.isActive(player.getUniqueId()));
 *
 * // And every cosmetic plugin asks the same question:
 * if (!Cosmetics.shows(player)) return null;
 * }</pre>
 *
 * <h2>What this is for</h2>
 * A server accumulates cosmetic plugins — armour skins, armour trims, kill
 * effects, hit effects, arrow trails, totem animations — and every one of them
 * draws whatever the player happens to be wearing or doing. That is right
 * almost always and wrong in the same few places: a staff member on duty is
 * wearing issued armour and is not playing, and dressing that armour in their
 * wardrobe skin tells everybody watching that a moderator is a participant.
 *
 * <p>Without something like this the fix is pairwise: every cosmetic plugin
 * learns the name of every mode plugin, and each new plugin on either side is
 * another integration nobody remembers to write. One rule from the mode, one
 * question from the cosmetic, and a plugin written later is covered by both.
 *
 * <h2>Every rule has to agree</h2>
 * Combined with AND, like {@link net.exylia.lib.chat.ChatRule} and
 * {@link net.exylia.lib.packet.VisibilityRule}: cosmetics are shown only while
 * no plugin objects. One rule per plugin — registering again replaces it — and
 * a plugin's rule is dropped when it is disabled.
 *
 * <h2>What it does not do</h2>
 * It does not hide anything by itself. Nothing here reaches into a cosmetic
 * plugin's packets; each one asks {@link #shows} where it decides what to draw
 * and skips its own work. A cosmetic plugin that never asks is unaffected.
 *
 * @since 1.94.0
 */
public final class Cosmetics {

    private static final Map<String, CosmeticRule> RULES = new ConcurrentHashMap<>();
    private static final Map<String, Consumer<Player>> LISTENERS = new ConcurrentHashMap<>();

    private Cosmetics() {
        throw new AssertionError("No instances.");
    }

    /**
     * Registers this plugin's rule, replacing its previous one.
     *
     * @param plugin the owning plugin
     * @param rule   the rule
     */
    public static void rule(@NotNull Plugin plugin, @NotNull CosmeticRule rule) {
        RULES.put(plugin.getName(), rule);
    }

    /**
     * Drops this plugin's rule, so it stops having a say.
     *
     * @param plugin the owning plugin
     */
    public static void clear(@NotNull Plugin plugin) {
        RULES.remove(plugin.getName());
        LISTENERS.remove(plugin.getName());
    }

    /**
     * Returns whether this player's cosmetics should be drawn.
     *
     * <p>True when nobody objects, which is the answer on a server where no
     * plugin has registered a rule — so a cosmetic plugin can ask
     * unconditionally and behave exactly as it did before.
     *
     * <p>A rule that throws is treated as no objection and skipped: a broken
     * opinion must not be the reason a player loses the cosmetics they paid
     * for.
     *
     * @param wearer the player the cosmetic would belong to
     * @return whether to draw them
     */
    public static boolean shows(@NotNull Player wearer) {
        for (CosmeticRule rule : RULES.values()) {
            try {
                if (!rule.shows(wearer)) {
                    return false;
                }
            } catch (Throwable ignored) {
                // See above: an opinion nobody can evaluate is not an objection.
            }
        }
        return true;
    }

    /**
     * Asks to be told when a player's answer changes.
     *
     * <p>Why a predicate is not enough. A cosmetic plugin decides what to draw
     * when something happens to the player — they equipped a piece, they
     * joined — and then caches it and draws from the cache until the next such
     * moment. Somebody who goes on duty a tick later never gets one, and keeps
     * wearing a skin and trailing its particles for the whole session.
     *
     * <p>Re-read your own state for that one player. Called on whatever thread
     * announced the change.
     *
     * @param plugin  the cosmetic plugin listening, so the interest goes away
     *                with it
     * @param changed what to redraw for that player
     */
    public static void onChange(@NotNull Plugin plugin, @NotNull Consumer<Player> changed) {
        LISTENERS.put(plugin.getName(), changed);
    }

    /**
     * Announces that this player's answer changed.
     *
     * <p>Called by the plugin whose rule changed, after the change is true.
     *
     * @param wearer the player whose cosmetics should be reconsidered
     */
    public static void refresh(@NotNull Player wearer) {
        for (Consumer<Player> listener : LISTENERS.values()) {
            try {
                listener.accept(wearer);
            } catch (Throwable ignored) {
                // One plugin failing to redraw is not a reason to skip the rest.
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
