package net.exylia.lib.api.staff;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reading and driving ExyliaStaff.
 *
 * <pre>{@code
 * ExyliaAPI.get(StaffService.class).ifPresent(staff -> {
 *     if (staff.isVanished(target.getUniqueId()) && !staff.canSee(viewer, target)) {
 *         event.setCancelled(true);
 *     }
 * });
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaStaff enables. Reach it through {@link ExyliaAPI#get(Class)}, and treat
 * an empty result as "this server has no staff toolkit" rather than as a
 * failure.
 *
 * <h2>Every feature is a module, and a module can be off</h2>
 * Staff mode, vanish, freeze, staff chat and the rest are separate modules the
 * owner switches on and off in {@code config.yml} or at runtime. A query about
 * a module that is off answers as though nobody is in that state — {@code false},
 * {@code 0}, {@link GlobalChatMode#OFF} — and an action on a module that is off
 * does nothing. Nothing here throws because a feature is missing, because a
 * feature being missing is the owner's decision, not an error. Ask
 * {@link #isModuleEnabled(String)} when the difference matters.
 *
 * <h2>Queries are cheap, actions are not</h2>
 * Everything that returns a value is a memory read off the module caches and is
 * safe from a placeholder, a scoreboard line or a combat listener. Everything
 * that acts runs the same flow the staff member's own command runs — permission
 * checks, the messages they see, the hotbar redraw, the session log — so call
 * those on the main thread and no more often than a player could trigger them.
 *
 * @since 1.0.0
 */
public interface StaffService {

    // ── State ──────────────────────────────────────────────────────────────

    /**
     * Whether a player counts as staff at all.
     *
     * <p>The one question every other module is built on: holding the staff
     * node is what makes a player receive alerts, see vanished players and read
     * staff chat. Takes the player rather than an id because it reads a
     * permission, which an offline player does not have.
     *
     * @param player the player
     * @return {@code true} when they hold the staff permission
     */
    boolean isStaff(@NotNull Player player);

    /**
     * Whether a player has a staff session open.
     *
     * @param player the player
     * @return {@code true} when they are in staff mode
     */
    boolean isInStaffMode(@NotNull UUID player);

    /**
     * Whether a player is hidden from the players below their vanish level.
     *
     * @param player the player
     * @return {@code true} when they are vanished
     */
    boolean isVanished(@NotNull UUID player);

    /**
     * Whether a player is frozen and may not act.
     *
     * @param player the player
     * @return {@code true} when they are frozen
     */
    boolean isFrozen(@NotNull UUID player);

    /**
     * Whether a player is flying through blocks inside their staff session.
     *
     * @param player the player
     * @return {@code true} when their staff session is in spectator
     */
    boolean isSpectator(@NotNull UUID player);

    /**
     * Whether a player is seeing ores through stone.
     *
     * @param player the player
     * @return {@code true} when x-ray vision is on for them
     */
    boolean isXrayVisionActive(@NotNull UUID player);

    // ── Vanish ─────────────────────────────────────────────────────────────

    /**
     * A player's vanish rank.
     *
     * <p>A viewer only sees vanished players of their own level or below, which
     * is what keeps an admin hidden from a helper who is otherwise allowed to
     * see vanished staff. Reads permissions, so it needs the player rather than
     * an id.
     *
     * @param player the player
     * @return their level, {@code 0} when they hold no level node
     */
    int vanishLevel(@NotNull Player player);

    /**
     * Whether one player may see another right now.
     *
     * <p>The check anything that lists, targets or renders players wants: it
     * covers not being vanished at all, being allowed to see vanished players,
     * and the level comparison between the two, in that order.
     *
     * @param viewer the player looking
     * @param target the player being looked for
     * @return {@code true} when the target is visible to the viewer
     */
    boolean canSee(@NotNull Player viewer, @NotNull Player target);

    // ── Chat ───────────────────────────────────────────────────────────────

    /**
     * Whether a player's normal chat goes to staff chat instead.
     *
     * @param player the player
     * @return {@code true} when their staff chat toggle is on
     */
    boolean isStaffChatToggled(@NotNull UUID player);

    /**
     * How far a player's chat reaches.
     *
     * @param player the player
     * @return their mode, {@link GlobalChatMode#OFF} when the module is off or
     *         they never raised it
     */
    @NotNull
    GlobalChatMode globalChatMode(@NotNull UUID player);

    /**
     * Whether a player is told about suspicious mining.
     *
     * @param player the player
     * @return {@code true} when they receive mining alerts
     */
    boolean receivesMiningAlerts(@NotNull UUID player);

    // ── Rosters ────────────────────────────────────────────────────────────

    /**
     * Everybody on this server who counts as staff.
     *
     * <p>This server only: staff working on another server of the network are
     * not online here and have no {@link Player} to read a permission from.
     *
     * @return the online staff, in no particular order
     */
    @NotNull
    @Unmodifiable
    List<UUID> onlineStaff();

    /**
     * Everybody on this server with a staff session open.
     *
     * @return the staff currently on duty here
     */
    @NotNull
    @Unmodifiable
    List<UUID> onlineInStaffMode();

    // ── Modules ────────────────────────────────────────────────────────────

    /**
     * Whether one feature of the plugin is running.
     *
     * <p>Ids are the ones the owner writes in {@code config.yml}:
     * {@code staffmode}, {@code vanish}, {@code freeze}, {@code staffchat},
     * {@code globalchat}, {@code mining}, {@code xrayvision} and the rest. An
     * unknown id is simply not enabled.
     *
     * @param moduleId the module id
     * @return {@code true} when that module is enabled right now
     */
    boolean isModuleEnabled(@NotNull String moduleId);

    /**
     * Every module that is running.
     *
     * <p>The set changes at runtime: an owner may switch a module off without
     * restarting, so read this when you need it rather than caching it.
     *
     * @return the enabled module ids
     */
    @NotNull
    @Unmodifiable
    Set<String> enabledModules();

    // ── Actions ────────────────────────────────────────────────────────────
    //
    // Each of these runs the same flow the staff member's own command runs,
    // including the permission checks and the messages they see. When one
    // reports a boolean it is whether the flow ran, not whether it succeeded in
    // some deeper sense: a refusal has already been said to the player.

    /**
     * Whether a player is allowed to open a staff session at all.
     *
     * <p>Ask this before {@link #enterStaffMode(Player)} when you want to hide
     * a button rather than have the player press it and be told no.
     *
     * @param player the player
     * @return {@code true} when they may enter staff mode
     */
    boolean mayEnterStaffMode(@NotNull Player player);

    /**
     * Opens a staff session.
     *
     * @param player the staff member going on duty
     * @return {@code true} when the session started
     */
    boolean enterStaffMode(@NotNull Player player);

    /**
     * Closes a staff session, giving the player their own inventory back.
     *
     * <p>Recorded as an administrative exit, because something other than the
     * player's own command ended it.
     *
     * @param player the staff member going off duty
     */
    void exitStaffMode(@NotNull Player player);

    /**
     * Hides or shows a player.
     *
     * @param player   the staff member
     * @param vanished {@code true} to hide them
     * @param silent   {@code true} to skip the confirmation and the effect the
     *                 player would otherwise get, for a vanish set by something
     *                 other than their own hand
     */
    void setVanished(@NotNull Player player, boolean vanished, boolean silent);

    /**
     * Freezes a player.
     *
     * @param target the player being frozen
     * @param staff  the staff member doing it, who is named in the log and the
     *               broadcast
     */
    void freeze(@NotNull Player target, @NotNull Player staff);

    /**
     * Unfreezes a player.
     *
     * @param target the player being released
     * @param staff  the staff member doing it
     */
    void unfreeze(@NotNull Player target, @NotNull Player staff);

    /**
     * Sends one message to staff chat.
     *
     * @param player  the sender, who has to be allowed to use staff chat
     * @param message what to say
     */
    void sendStaffChat(@NotNull Player player, @NotNull String message);

    /**
     * Sets how far a player's chat reaches.
     *
     * @param player the staff member
     * @param mode   the reach to give them
     */
    void setGlobalChatMode(@NotNull Player player, @NotNull GlobalChatMode mode);
}
