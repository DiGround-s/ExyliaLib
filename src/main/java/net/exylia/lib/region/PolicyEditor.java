package net.exylia.lib.region;

import net.exylia.lib.region.internal.PolicyEditorHolder;
import net.exylia.lib.region.internal.PolicyLooks;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A screen for editing a region's policies: a {@link PolicySet} in, the edited
 * {@link PolicySet} out.
 *
 * <pre>{@code
 * Regions.of(this).policyEditor(arena.policies())
 *         .title("{primary}&lARENA FLAGS")
 *         .keys(CommonRegionPolicies.PVP, CommonRegionPolicies.BUILD, CommonRegionPolicies.BREAK,
 *               CommonRegionPolicies.BREAKABLE_BLOCKS_ONLY, CommonRegionPolicies.BREAKABLE_BLOCKS)
 *         .defaults(ArenaDefaults.POLICIES)
 *         .locked(CommonRegionPolicies.PVP, "The game switches this by itself")
 *         .onSave(edited -> arenas.save(arena.withPolicies(edited)))
 *         .onCancel(() -> ArenaMenu.open(player))
 *         .open(player);
 * }</pre>
 *
 * <p>Every plugin with a region used to draw its own flag screen, each with its
 * own idea of what a click does and what "not set" means. This is the one
 * screen, in the style of the list editors.
 *
 * <h2>Rows</h2>
 * <ul>
 *   <li><b>Boolean</b> keys cycle on click: <i>Default</i> (not declared, so
 *       whatever {@link #defaults} says decides) → <i>Allow</i> → <i>Deny</i> →
 *       <i>Default</i>.</li>
 *   <li><b>{@link MaterialSet}</b> keys open a list editor of materials, added
 *       with the material picker.</li>
 *   <li><b>{@link Integer}</b> keys ask for a whole number, zero or more.</li>
 * </ul>
 * A right click takes any unlocked row back to Default. A locked row shows its
 * reason and never changes.
 *
 * <h2>Nothing is written until save</h2>
 * Like the list editors: save hands {@link #onSave} the edited set, and cancel,
 * closing the window, leaving and the plugin disabling all end in
 * {@link #onCancel}. Keys the editor does not show pass through untouched.
 *
 * <h2>Threads</h2>
 * {@link #open} is safe from any thread; the callbacks run on the viewer's
 * thread, on the tick after the window closed. Works the same on Spigot, Paper
 * and Folia, and the window closes when the owning plugin is disabled.
 *
 * @since 1.202.0
 */
public final class PolicyEditor {

    /** How many rows a screen fits: four rows of seven. */
    public static final int MAX_KEYS = 28;

    private final Plugin plugin;
    private final PolicySet policies;
    private final Map<PolicyKey<?>, String> locked = new LinkedHashMap<>();
    private final Map<PolicyKey<?>, PolicyLooks.Look> looks = new LinkedHashMap<>();

    private List<PolicyKey<?>> keys = PolicyLooks.BOOLEANS;
    private PolicySet defaults;
    private String title = "{primary}&lREGION FLAGS";
    private Consumer<PolicySet> onSave = saved -> { };
    private Runnable onCancel = () -> { };

    PolicyEditor(Plugin plugin, PolicySet policies) {
        this.plugin = plugin;
        this.policies = Objects.requireNonNull(policies, "policies");
    }

    /**
     * The window title, in Exylia text notation.
     *
     * @param title the title
     * @return this editor
     */
    public @NotNull PolicyEditor title(@NotNull String title) {
        this.title = Objects.requireNonNull(title, "title");
        return this;
    }

    /**
     * Which rows to show, in order.
     *
     * <p>Defaults to every boolean key in {@link CommonRegionPolicies}. A key of
     * your own is accepted when it is a {@code Boolean}, {@code Integer} or
     * {@link MaterialSet} key and has been {@link #describe described}.
     *
     * @param keys the rows; at most {@link #MAX_KEYS}
     * @return this editor
     * @throws IllegalArgumentException for an unsupported type, a duplicate, or too many
     */
    public @NotNull PolicyEditor keys(@NotNull PolicyKey<?>... keys) {
        Objects.requireNonNull(keys, "keys");
        List<PolicyKey<?>> rows = new ArrayList<>(keys.length);
        for (PolicyKey<?> key : keys) {
            Objects.requireNonNull(key, "keys contains null");
            if (!PolicyLooks.editable(key)) {
                throw new IllegalArgumentException("Policy " + key + " cannot be edited on screen:"
                        + " only Boolean, Integer and MaterialSet keys can.");
            }
            if (rows.contains(key)) {
                throw new IllegalArgumentException("Policy " + key + " is listed twice.");
            }
            rows.add(key);
        }
        if (rows.isEmpty() || rows.size() > MAX_KEYS) {
            throw new IllegalArgumentException("An editor shows 1 to " + MAX_KEYS
                    + " policies, not " + rows.size() + '.');
        }
        this.keys = List.copyOf(rows);
        return this;
    }

    /**
     * What an undeclared row resolves to, shown on every row as its Default.
     *
     * <p>A key these do not declare falls back to its own
     * {@link PolicyKey#defaultValue()}. Without this, Default reads "set by the
     * plugin": the screen does not guess what the plugin does with a gap.
     *
     * @param defaults the declarations Default stands for
     * @return this editor
     */
    public @NotNull PolicyEditor defaults(@NotNull PolicySet defaults) {
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        return this;
    }

    /**
     * Shows a row but never lets it change.
     *
     * <p>For a policy the plugin drives itself, such as a game that turns PvP on
     * when the countdown ends. The reason is drawn on the row.
     *
     * @param key    the row
     * @param reason one short line saying why
     * @return this editor
     */
    public @NotNull PolicyEditor locked(@NotNull PolicyKey<?> key, @NotNull String reason) {
        locked.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(reason, "reason"));
        return this;
    }

    /**
     * How a row looks: its icon, name and one-line description.
     *
     * <p>Every key in {@link CommonRegionPolicies} has a built-in look; this
     * replaces it, or gives one to a key of your own.
     *
     * <pre>{@code
     * editor.describe(ARENA_SPECTATORS, "ENDER_EYE", "{primary}&lSPECTATORS",
     *         "Players who died can watch the rest.");
     * }</pre>
     *
     * @param key         the row
     * @param icon        a material, head string or {@code bytes:} snapshot
     * @param name        the item name, in Exylia text notation
     * @param description one short line of what it does
     * @return this editor
     */
    public @NotNull PolicyEditor describe(@NotNull PolicyKey<?> key, @NotNull String icon,
                                          @NotNull String name, @NotNull String description) {
        looks.put(Objects.requireNonNull(key, "key"), new PolicyLooks.Look(
                Objects.requireNonNull(icon, "icon"), Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(description, "description")));
        return this;
    }

    /**
     * What to do with the edited policies.
     *
     * <p>Called once, on the viewer's thread, with every declaration the set was
     * opened on plus the edits — the keys not shown included.
     *
     * @param onSave told the edited set
     * @return this editor
     */
    public @NotNull PolicyEditor onSave(@NotNull Consumer<PolicySet> onSave) {
        this.onSave = Objects.requireNonNull(onSave, "onSave");
        return this;
    }

    /**
     * What to do when nothing was kept: cancel, a closed window, the viewer
     * leaving or the plugin disabling. Normally reopening the screen the
     * editor was entered from.
     *
     * @param onCancel told the edit was discarded
     * @return this editor
     */
    public @NotNull PolicyEditor onCancel(@NotNull Runnable onCancel) {
        this.onCancel = Objects.requireNonNull(onCancel, "onCancel");
        return this;
    }

    /**
     * Puts the editor on screen.
     *
     * <p>Safe from any thread: it relocates itself onto the thread that owns the
     * viewer.
     *
     * @param viewer who is editing
     * @throws IllegalArgumentException when a shown key of your own was never described
     */
    public void open(@NotNull Player viewer) {
        Objects.requireNonNull(viewer, "viewer");
        Map<PolicyKey<?>, PolicyLooks.Look> resolved = new LinkedHashMap<>();
        for (PolicyKey<?> key : keys) {
            PolicyLooks.Look look = looks.getOrDefault(key, PolicyLooks.builtIn(key));
            if (look == null) {
                throw new IllegalArgumentException("Policy " + key
                        + " has no built-in look: describe(...) it.");
            }
            resolved.put(key, look);
        }
        PolicyEditorHolder.open(plugin, title, policies, defaults, locked,
                resolved, onSave, onCancel, viewer);
    }
}
