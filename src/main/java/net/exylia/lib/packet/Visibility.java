package net.exylia.lib.packet;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Per-viewer hiding of players, for vanish.
 *
 * <p>A plugin registers one {@link VisibilityRule}; every rule from every
 * plugin must agree before a viewer sees a target. When a target's visibility
 * changes the plugin calls {@link #refresh}, and the module despawns them for
 * viewers who lost sight and respawns them for viewers who regained it.
 *
 * <p>Between refreshes, every outbound packet about a hidden target — spawn,
 * metadata, movement, entity sounds, tab-list entries, their names among
 * command suggestions — is dropped on its way to a viewer who may not see
 * them, so a plugin that sends its own packets cannot leak a vanished player.
 *
 * <h2>Limits</h2>
 * The server still knows where the target is: they collide, block arrows,
 * and appear in {@code getNearbyEntities}. Positional sounds and particles
 * carry no entity id and pass through. Pair with
 * {@code target.setCollidable(false)} and {@code setSilent(true)} where it
 * matters.
 *
 * @since 1.75.0
 */
public interface Visibility {

    /**
     * Registers this plugin's rule, replacing its previous one.
     *
     * @param rule the rule
     */
    void rule(@NotNull VisibilityRule rule);

    /**
     * Registers this plugin's rule, like {@link #rule(VisibilityRule)}. With {@code listed}, the players it
     * hides leave the world only and stay on the viewer's tab list: for places where each player plays their
     * own copy (a private farm, an instanced dungeon), not for vanish. A target another plugin's rule also
     * hides drops off the list as before.
     *
     * <p>Needs PacketEvents; without it the hide is Bukkit's and takes the tab entry with it. While hidden,
     * the entry keeps the name, ping and game mode it had when the hide began, and catches up once shown.
     *
     * @param rule   the rule
     * @param listed whether the players this rule hides stay on the tab list
     * @since 1.653.0
     */
    default void rule(@NotNull VisibilityRule rule, boolean listed) {
        rule(rule);
    }

    /**
     * Registers this plugin's ghost rule, replacing its previous one: a target it answers {@code true} for is drawn
     * to that viewer as a faint, see-through figure instead of whole, the way a client draws an invisible teammate.
     * A hidden target stays hidden. Answers are asked again on {@link #refresh}, like the hiding rule's.
     *
     * <p>Needs PacketEvents. Nothing changes on the server: the target is not invisible, only drawn so. To draw
     * them faint the viewer's client is sent a team of its own holding the viewer and their faint players, so
     * while a player is faint to a viewer the other teams that client is told about (another plugin's nametags)
     * do not take them back; they return to them once solid. A faint player's nametag shows plain in grey, and
     * what they wear or hold is drawn as usual. Bedrock clients draw them fully invisible.
     *
     * @param rule {@code true} for a target drawn faint to that viewer
     * @since 1.660.0
     */
    default void ghosts(@NotNull VisibilityRule rule) {
    }

    /**
     * Re-evaluates who may see {@code target} and sends the difference.
     *
     * <p>Call after anything that changes a rule's answer: vanish toggled, a
     * permission granted, a viewer joined. Safe from any thread.
     *
     * @param target the player whose visibility changed
     */
    void refresh(@NotNull Player target);

    /**
     * Re-evaluates one pair only: whether {@code viewer} may see {@code target}.
     *
     * <p>{@link #refresh(Player)} walks every online viewer; a plugin fixing the
     * other direction for each of them should call this instead, or one change
     * costs every online player squared. Safe from any thread.
     *
     * @param viewer the player looking
     * @param target the player looked at
     * @since 1.604.1
     */
    default void refresh(@NotNull Player viewer, @NotNull Player target) {
        refresh(target);
    }

    /**
     * Returns whether {@code viewer} currently sees {@code target}.
     *
     * @param viewer the player looking
     * @param target the player looked at
     * @return {@code false} while the target is hidden from this viewer
     */
    boolean canSee(@NotNull Player viewer, @NotNull Player target);

    /**
     * Keeps a box empty for one viewer while a screen stands in it: every entity in it that is not the
     * screen's own is out of their sight, whoever drew it (the server's mobs, items and players, another
     * plugin's packet-only NPCs and holograms, this library's), and comes back when it leaves the box or
     * the box is lifted. {@code null} lifts it. Each plugin seals one box per viewer; a viewer in several
     * plugins' boxes sees nothing in any of them. Forgotten on quit, lifted when the plugin is disabled.
     *
     * <p>The screen's own pieces are what is spawned to the viewer after the box goes up: seal before
     * drawing. An entity already there, or spawned again later under the id it had (a hologram another
     * plugin redraws, an NPC respawned), is not the screen's, nor is any real player.
     *
     * <p>Nothing is despawned: an entity in the box is moved, for this viewer alone, far beyond what any
     * client draws, and moved back to where it really is on lifting, so another plugin's NPC keeps the
     * skin, equipment, metadata and passengers it was sent with and that plugin is never asked to draw it
     * again. A vehicle the viewer rides is never moved. Since 1.442.0; before, only entities spawned in
     * packets after the box went up were kept out, and displays never.
     *
     * <h4>Limits</h4>
     * An entity another plugin spawns for the first time while the box is up, with a brand-new id, counts
     * as the screen's (only real players are told apart then). The server still has every entity where it
     * is: it collides, and {@code getNearbyEntities} finds it.
     *
     * @param viewer the player
     * @param box    the box, in the viewer's world, or {@code null}
     * @since 1.420.0
     */
    default void seal(@NotNull Player viewer, @org.jetbrains.annotations.Nullable Volume box) {
    }

    /**
     * Keeps one entity in a viewer's sight inside every box sealed for them, whoever sealed it: the NPC
     * a screen dialogue is held with. {@code false} lets the boxes take it again. Forgotten on quit.
     *
     * @param viewer   the player
     * @param entityId the entity, as it was sent to them
     * @param on       whether it is kept
     * @since 1.624.0
     */
    default void spare(@NotNull Player viewer, int entityId, boolean on) {
    }
}
