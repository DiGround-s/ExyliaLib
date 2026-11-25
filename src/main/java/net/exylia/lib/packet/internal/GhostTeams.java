package net.exylia.lib.packet.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who each viewer sees as a faint figure, and the client-only team that draws them so.
 *
 * <p>A client draws an invisible player see-through only when it and that player are in the same team with
 * "see friendly invisibles" on. Each viewer is sent one such team, {@link #TEAM}, holding themselves and every
 * player they see faint; the invisibility bit itself is set on its way to them. A name can be in one team per
 * client, so while it is faint the teams other plugins (TAB's nametags) put it in are stripped from what they
 * send this viewer and remembered, and it goes back to the last of them once it is solid again.
 *
 * <p>Decides what to send and filters, but sends nothing: {@link PacketHooks} does. Thread-safe.
 */
final class GhostTeams {

    /** The client-only team; no other plugin's team packet for it is ever rewritten. */
    static final String TEAM = "exy_ghosts";

    /** What to tell a viewer's client about one team. */
    record Op(Kind kind, String team, List<String> names) {
    }

    enum Kind { CREATE, ADD, REMOVE, DISBAND }

    /** viewer -> names drawn faint to them. */
    private static final Map<UUID, Set<String>> FAINT = new ConcurrentHashMap<>();
    /** viewer -> entity ids drawn faint to them, for the invisibility bit. */
    private static final Map<UUID, Set<Integer>> FAINT_IDS = new ConcurrentHashMap<>();
    /** viewer -> their own name, held in the team while anybody is faint to them. */
    private static final Map<UUID, String> SELF = new ConcurrentHashMap<>();
    /** viewer -> name -> the team another plugin last put that name in, as sent to this viewer. */
    private static final Map<UUID, Map<String, String>> REAL = new ConcurrentHashMap<>();

    private GhostTeams() {
    }

    /** Whether anybody is drawn faint to anybody: the packet filter's quick way out. */
    static boolean any() {
        return !FAINT_IDS.isEmpty();
    }

    /** Whether {@code entityId} is drawn faint to this viewer. */
    static boolean faint(UUID viewer, int entityId) {
        Set<Integer> ids = FAINT_IDS.get(viewer);
        return ids != null && ids.contains(entityId);
    }

    /**
     * Turns one target faint or solid for one viewer.
     *
     * @return what the viewer's client must be told, empty when nothing changed
     */
    static synchronized List<Op> set(UUID viewer, String viewerName, String name, int entityId, boolean on) {
        Set<String> names = FAINT.computeIfAbsent(viewer, ignored -> ConcurrentHashMap.newKeySet());
        Set<Integer> ids = FAINT_IDS.computeIfAbsent(viewer, ignored -> ConcurrentHashMap.newKeySet());
        List<Op> out = new ArrayList<>();
        if (on) {
            ids.add(entityId);
            if (!names.add(name)) return finish(viewer, out);
            if (names.size() == 1) {
                SELF.put(viewer, viewerName);
                out.add(new Op(Kind.CREATE, TEAM, List.of(viewerName, name)));
            } else {
                out.add(new Op(Kind.ADD, TEAM, List.of(name)));
            }
            return finish(viewer, out);
        }
        ids.remove(entityId);
        if (!names.remove(name)) return finish(viewer, out);
        Map<String, String> real = REAL.getOrDefault(viewer, Map.of());
        if (names.isEmpty()) {
            // Disbanding drops the viewer too; then both go back where the other plugin had them.
            out.add(new Op(Kind.DISBAND, TEAM, List.of()));
            String self = SELF.remove(viewer);
            back(out, real, name);
            if (self != null) back(out, real, self);
        } else {
            out.add(new Op(Kind.REMOVE, TEAM, List.of(name)));
            back(out, real, name);
        }
        return finish(viewer, out);
    }

    private static void back(List<Op> out, Map<String, String> real, String name) {
        String team = real.get(name);
        if (team != null) out.add(new Op(Kind.ADD, team, List.of(name)));
    }

    private static List<Op> finish(UUID viewer, List<Op> out) {
        Set<Integer> ids = FAINT_IDS.get(viewer);
        if (ids != null && ids.isEmpty()) FAINT_IDS.remove(viewer, ids);
        Set<String> names = FAINT.get(viewer);
        if (names != null && names.isEmpty()) FAINT.remove(viewer, names);
        return out;
    }

    /**
     * Another plugin's team packet on its way to a viewer: remembers where it puts each name and drops the names
     * this viewer's ghost team holds, which would otherwise leave it.
     *
     * @param mode the packet's mode, 0 create, 1 remove, 2 update, 3 add names, 4 remove names
     * @return the names to send on, or {@code names} itself when nothing was dropped
     */
    static List<String> filter(UUID viewer, String team, int mode, List<String> names) {
        if (TEAM.equals(team)) return names;
        if (mode == 1) {
            Map<String, String> real = REAL.get(viewer);
            if (real != null) real.values().removeIf(team::equals);
            return names;
        }
        if (mode != 0 && mode != 3 && mode != 4) return names;
        Map<String, String> real = REAL.computeIfAbsent(viewer, ignored -> new ConcurrentHashMap<>());
        Set<String> faint = FAINT.getOrDefault(viewer, Set.of());
        String self = SELF.get(viewer);
        List<String> kept = null;
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (mode == 4) real.remove(name, team);
            else real.put(name, team);
            if (faint.contains(name) || name.equals(self)) {
                if (kept == null) kept = new ArrayList<>(names.subList(0, i));
            } else if (kept != null) {
                kept.add(name);
            }
        }
        return kept == null ? names : kept;
    }

    /** A target who left: drops them from every viewer's ghost team. Returns what each viewer must be told. */
    static Map<UUID, List<Op>> leave(String name, int entityId) {
        Map<UUID, List<Op>> out = new java.util.HashMap<>();
        for (Map.Entry<UUID, Set<String>> each : FAINT.entrySet()) {
            if (!each.getValue().contains(name)) continue;
            String self = SELF.getOrDefault(each.getKey(), "");
            List<Op> ops = set(each.getKey(), self, name, entityId, false);
            if (!ops.isEmpty()) out.put(each.getKey(), ops);
        }
        return out;
    }

    /** A viewer who left: forgets everything about them. */
    static void forget(UUID viewer) {
        FAINT.remove(viewer);
        FAINT_IDS.remove(viewer);
        SELF.remove(viewer);
        REAL.remove(viewer);
    }

    static void clear() {
        FAINT.clear();
        FAINT_IDS.clear();
        SELF.clear();
        REAL.clear();
    }
}
