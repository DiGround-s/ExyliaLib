package net.exylia.lib.nametag.internal;

import net.exylia.lib.nametag.NametagStyle;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each viewer has been told.
 *
 * <p>Three questions, asked on three different paths, which is why they are
 * three indexes rather than one map:
 *
 * <ul>
 *   <li>What does this viewer see this player as? Asked when painting, to
 *       avoid re-sending a team the client already has.</li>
 *   <li>Which teams does this viewer know? Asked to decide between creating a
 *       team and adding to one.</li>
 *   <li>Should this player glow for this viewer? Asked on <em>every</em>
 *       entity metadata packet the server sends, which is the hot path this
 *       whole shape exists for.</li>
 * </ul>
 *
 * <p>The glow index is a set of viewer ids first, so the common case — a viewer
 * with nothing painted — costs one lookup that misses and nothing else.
 */
final class State {

    /** viewer to target to style. */
    private final Map<UUID, Map<UUID, Painted>> painted = new ConcurrentHashMap<>();

    /** viewer to the team names their client has. */
    private final Map<UUID, Set<String>> known = new ConcurrentHashMap<>();

    /** viewer to the players who should glow for them. */
    private final Map<UUID, Set<UUID>> glowing = new ConcurrentHashMap<>();

    /** entity id to player id, for the metadata rewrite. */
    private final Map<Integer, UUID> entities = new ConcurrentHashMap<>();

    /** A style and the plugin that asked for it. */
    record Painted(String plugin, NametagStyle style, String targetName) {
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    /** Records a style, returning what the viewer saw before, or {@code null}. */
    Painted paint(UUID viewer, UUID target, Painted value) {
        return painted.computeIfAbsent(viewer, id -> new ConcurrentHashMap<>())
                .put(target, value);
    }

    Painted paintedOf(UUID viewer, UUID target) {
        Map<UUID, Painted> mine = painted.get(viewer);
        return mine == null ? null : mine.get(target);
    }

    Painted unpaint(UUID viewer, UUID target) {
        Map<UUID, Painted> mine = painted.get(viewer);
        if (mine == null) {
            return null;
        }
        Painted removed = mine.remove(target);
        if (mine.isEmpty()) {
            // A viewer who is shown nothing must leave no entry behind, or a
            // busy server accumulates one empty map per player who ever saw a
            // colour and never releases it.
            painted.remove(viewer, mine);
        }
        return removed;
    }

    Map<UUID, Painted> paintedBy(UUID viewer) {
        Map<UUID, Painted> mine = painted.get(viewer);
        return mine == null ? Map.of() : Map.copyOf(mine);
    }

    Set<UUID> viewers() {
        return Set.copyOf(painted.keySet());
    }

    // ------------------------------------------------------------------
    // Teams the client knows
    // ------------------------------------------------------------------

    boolean knows(UUID viewer, String team) {
        Set<String> teams = known.get(viewer);
        return teams != null && teams.contains(team);
    }

    void learn(UUID viewer, String team) {
        known.computeIfAbsent(viewer, id -> ConcurrentHashMap.newKeySet()).add(team);
    }

    void forgetTeam(UUID viewer, String team) {
        Set<String> teams = known.get(viewer);
        if (teams != null) {
            teams.remove(team);
        }
    }

    Set<String> teamsOf(UUID viewer) {
        Set<String> teams = known.get(viewer);
        return teams == null ? Set.of() : Set.copyOf(teams);
    }

    // ------------------------------------------------------------------
    // Glow
    // ------------------------------------------------------------------

    void glow(UUID viewer, UUID target) {
        glowing.computeIfAbsent(viewer, id -> ConcurrentHashMap.newKeySet()).add(target);
    }

    void unglow(UUID viewer, UUID target) {
        Set<UUID> targets = glowing.get(viewer);
        if (targets != null) {
            targets.remove(target);
            if (targets.isEmpty()) {
                // Kept empty, the fast path below would stop being fast.
                glowing.remove(viewer, targets);
            }
        }
    }

    /** The cheap check that runs on every metadata packet. */
    boolean anyGlowing(UUID viewer) {
        Set<UUID> targets = glowing.get(viewer);
        return targets != null && !targets.isEmpty();
    }

    boolean isGlowing(UUID viewer, UUID target) {
        Set<UUID> targets = glowing.get(viewer);
        return targets != null && targets.contains(target);
    }

    // ------------------------------------------------------------------
    // Entity ids
    // ------------------------------------------------------------------

    void register(int entityId, UUID player) {
        entities.put(entityId, player);
    }

    void unregister(int entityId) {
        entities.remove(entityId);
    }

    UUID playerOf(int entityId) {
        return entities.get(entityId);
    }

    // ------------------------------------------------------------------

    /** Drops everything about a player, as a viewer and as a target. */
    void forget(UUID player) {
        painted.remove(player);
        known.remove(player);
        glowing.remove(player);
        painted.values().removeIf(mine -> {
            mine.remove(player);
            return mine.isEmpty();
        });
        for (Set<UUID> targets : glowing.values()) {
            targets.remove(player);
        }
        entities.values().remove(player);
    }

    void clear() {
        painted.clear();
        known.clear();
        glowing.clear();
        entities.clear();
    }

    /** How many viewers have something painted. For diagnostics and tests. */
    int tracked() {
        return painted.size();
    }
}
