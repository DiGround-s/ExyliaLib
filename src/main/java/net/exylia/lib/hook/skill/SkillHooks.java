package net.exylia.lib.hook.skill;

import net.exylia.lib.hook.skill.internal.SkillHookRuntime;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;

/**
 * Holding skill plugins back while a player plays something that has to be
 * fair: an event, a duel, an arena.
 *
 * <pre>{@code
 * // on the way in
 * SkillHooks.suspend(plugin, player);
 * // on the way out
 * SkillHooks.resume(plugin, player);
 * }</pre>
 *
 * <p>While a player is suspended they earn no skill experience, cannot
 * trigger skill abilities, and the bonuses a skill plugin adds to their hits
 * and to the hits they take are left out. Nothing a skill plugin stores is
 * touched: the plugin's own events are cancelled or trimmed as they fire, so a
 * crash mid-event leaves nothing to restore. Supported: AuraSkills and mcMMO.
 * A server without any of them makes every call here a no-op.
 *
 * <p>Suspensions are counted per owner: a player an event and a duel both
 * suspended stays suspended until both resumed them. They are held in memory
 * only; the player's quit and the owner's disable drop them.
 *
 * <p>Thread-safe; callable from any thread.
 *
 * @since 1.409.0
 */
public final class SkillHooks {

    private SkillHooks() {
    }

    /**
     * Holds skill plugins back for the player until {@code owner} resumes them.
     *
     * @return {@code false} when {@code owner} had already suspended them
     */
    public static boolean suspend(Plugin owner, Player player) {
        return SkillHookRuntime.suspend(owner.getName(), player.getUniqueId());
    }

    /** Ends {@code owner}'s suspension; the player is free once no owner holds them. */
    public static void resume(Plugin owner, Player player) {
        SkillHookRuntime.resume(owner.getName(), player.getUniqueId());
    }

    /** Whether any plugin has the player suspended. */
    public static boolean isSuspended(UUID player) {
        return SkillHookRuntime.isSuspended(player);
    }

    /** The skill plugins on this server that a suspension holds back, for a setup screen or a log line. */
    public static List<String> plugins() {
        return SkillHookRuntime.hooked();
    }
}
