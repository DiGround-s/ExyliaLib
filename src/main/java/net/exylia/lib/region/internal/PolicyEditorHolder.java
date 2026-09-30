package net.exylia.lib.region.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.item.Appearance;
import net.exylia.lib.item.Item;
import net.exylia.lib.item.Items;
import net.exylia.lib.region.MaterialSet;
import net.exylia.lib.region.PolicyKey;
import net.exylia.lib.region.PolicySet;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.exylia.lib.util.editor.Editors;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * One open policy editor, and the window it is drawn in.
 *
 * <p>State lives on the window, never in a {@code Map<UUID, Session>}. The set
 * of open holders exists only so a plugin being disabled can close its screens.
 *
 * <p>Layout: the rows sit seven to a line, framed, and the last line holds the
 * counter, save and cancel where the list editors keep them.
 */
@ApiStatus.Internal
public final class PolicyEditorHolder implements InventoryHolder {

    private static final Set<PolicyEditorHolder> OPEN = ConcurrentHashMap.newKeySet();

    private static final Appearance PLAIN = Appearance.builder().hideAttributes(true).build();
    private static final Appearance GLOWING = Appearance.builder().hideAttributes(true).glow(true).build();

    private final Plugin plugin;
    private final String title;
    private final PolicyDraft draft;
    private final List<PolicyKey<?>> keys;
    private final Map<PolicyKey<?>, PolicyLooks.Look> looks;
    private final Consumer<PolicySet> onSave;
    private final Runnable onCancel;
    private final UUID viewerId;
    private final int size;

    private Inventory inventory;
    private boolean asking;
    private boolean finished;

    PolicyEditorHolder(Plugin plugin, String title, PolicyDraft draft,
                       Map<PolicyKey<?>, PolicyLooks.Look> looks,
                       Consumer<PolicySet> onSave, Runnable onCancel, UUID viewerId) {
        this.plugin = plugin;
        this.title = title;
        this.draft = draft;
        this.looks = Map.copyOf(looks);
        this.keys = List.copyOf(looks.keySet());
        this.onSave = onSave;
        this.onCancel = onCancel;
        this.viewerId = viewerId;
        this.size = size(keys.size());
    }

    /**
     * Opens an editor on the viewer's own thread.
     *
     * @param looks the rows, in order, with how each looks
     */
    public static void open(@NotNull Plugin plugin, @NotNull String title, @NotNull PolicySet policies,
                            @Nullable PolicySet defaults, @NotNull Map<PolicyKey<?>, String> locked,
                            @NotNull Map<PolicyKey<?>, PolicyLooks.Look> looks,
                            @NotNull Consumer<PolicySet> onSave, @NotNull Runnable onCancel,
                            @NotNull Player viewer) {
        PolicyEditorHolder holder = new PolicyEditorHolder(plugin, title,
                new PolicyDraft(policies, defaults, locked), looks, onSave, onCancel,
                viewer.getUniqueId());
        Tasks.of(plugin).runAtEntity(viewer, () -> holder.show(viewer));
    }

    /** Rows of the window: a frame line, the key lines, the control line. */
    static int size(int keys) {
        return (1 + (keys + 6) / 7 + 1) * 9;
    }

    /** The slot of the n-th row: seven to a line, inside the frame. */
    static int slotOf(int index) {
        return 9 * (1 + index / 7) + 1 + index % 7;
    }

    int infoSlot() {
        return size - 5;
    }

    int saveSlot() {
        return size - 2;
    }

    int cancelSlot() {
        return size - 1;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    PolicyDraft draft() {
        return draft;
    }

    boolean isAsking() {
        return asking;
    }

    boolean isFinished() {
        return finished;
    }

    private @Nullable Player viewer() {
        return Bukkit.getPlayer(viewerId);
    }

    private void show(Player viewer) {
        if (finished) {
            return;
        }
        inventory = Bukkit.createInventory(this, size, Text.from(plugin, title).forPlayer(viewer).build());
        draw(viewer);
        OPEN.add(this);
        viewer.openInventory(inventory);
    }

    void draw(Player viewer) {
        ItemStack filler = render(viewer, "BLACK_STAINED_GLASS_PANE", " ", List.of(), false);
        for (int slot = 0; slot < size; slot++) {
            inventory.setItem(slot, filler);
        }
        for (int index = 0; index < keys.size(); index++) {
            PolicyKey<?> key = keys.get(index);
            PolicyLooks.Look look = looks.get(key);
            inventory.setItem(slotOf(index), render(viewer, look.icon(),
                    PolicyLooks.name(key, look, draft), PolicyLooks.lore(key, look, draft), false));
        }
        inventory.setItem(infoSlot(), render(viewer, "PAPER",
                Phrases.tr("{primary}&lFLAGS &8[{info}{0}&8/{info}{1}&8]", draft.declared(keys), keys.size()),
                List.of("",
                        Phrases.tr(" {letters_black}▎ {letters}Flags set here {letters_black}» {info}{0}", draft.declared(keys)),
                        Phrases.tr(" {letters_black}▎ {letters}The rest follow their {muted}Default{letters}."),
                        "",
                        Phrases.tr(" {letters_black}▎ {letters_black}Nothing is written until you save."),
                        ""), false));
        inventory.setItem(saveSlot(), render(viewer, "LIME_DYE", Phrases.tr("{success}&lSAVE"),
                List.of("", Phrases.tr(" {letters_black}▎ {letters}Keep every change made here."), "",
                        Phrases.tr("{warning}➥ Click to save"), ""), true));
        inventory.setItem(cancelSlot(), render(viewer, "RED_DYE", Phrases.tr("{error}&lCANCEL"),
                List.of("", Phrases.tr(" {letters_black}▎ {letters}Discard every change and"),
                        Phrases.tr(" {letters_black}▎ {letters}close this screen."), "",
                        Phrases.tr("{warning}➥ Click to discard"), ""), false));
    }

    private ItemStack render(Player viewer, String icon, String name, List<String> lore, boolean glow) {
        return Items.of(plugin).render(Item.of(icon).name(name).lore(lore)
                .appearance(glow ? GLOWING : PLAIN).build(), viewer);
    }

    /** A click in the window. Every click is cancelled by the listener. */
    void click(@NotNull Player viewer, int slot, boolean right) {
        if (finished || asking) {
            return;
        }
        if (slot == saveSlot()) {
            end(viewer, true);
            return;
        }
        if (slot == cancelSlot()) {
            end(viewer, false);
            return;
        }
        PolicyKey<?> key = keyAt(slot);
        if (key == null || draft.isLocked(key)) {
            return;
        }
        if (right) {
            draft.reset(key);
            draw(viewer);
            return;
        }
        if (key.type() == Boolean.class) {
            @SuppressWarnings("unchecked")
            PolicyKey<Boolean> bool = (PolicyKey<Boolean>) key;
            draft.cycle(bool);
            draw(viewer);
        } else if (key.type() == Integer.class) {
            @SuppressWarnings("unchecked")
            PolicyKey<Integer> number = (PolicyKey<Integer>) key;
            askNumber(viewer, number);
        } else if (key.type() == MaterialSet.class) {
            @SuppressWarnings("unchecked")
            PolicyKey<MaterialSet> list = (PolicyKey<MaterialSet>) key;
            askMaterials(viewer, list);
        }
    }

    @Nullable PolicyKey<?> keyAt(int slot) {
        for (int index = 0; index < keys.size(); index++) {
            if (slotOf(index) == slot) {
                return keys.get(index);
            }
        }
        return null;
    }

    private void askNumber(Player viewer, PolicyKey<Integer> key) {
        stepAside(viewer);
        Inputs.of(plugin).integer(viewer,
                        Phrases.tr("{0} {letters_black}» {letters}a whole number, 0 or more", looks.get(key).name()))
                .range(0L, (long) Integer.MAX_VALUE)
                .open()
                .whenComplete((answer, failure) -> {
                    if (failure != null) {
                        Debug.of(plugin).error("A policy editor could not ask for a number.", failure);
                    } else if (answer != null && answer.completed()) {
                        draft.set(key, answer.value().intValue());
                    }
                    comeBack();
                });
    }

    private void askMaterials(Player viewer, PolicyKey<MaterialSet> key) {
        stepAside(viewer);
        List<Material> current = new ArrayList<>(draft.explicit(key).orElse(MaterialSet.empty()).materials());
        Editors.of(plugin).list(new MaterialDescriptor(plugin), Material.class, current)
                .title(looks.get(key).name())
                .onSave(edited -> {
                    draft.set(key, MaterialSet.of(edited));
                    comeBack();
                })
                .onCancel(this::comeBack)
                .open(viewer);
    }

    /** Closes the window for a question without the close reading as walking away. */
    private void stepAside(Player viewer) {
        asking = true;
        viewer.closeInventory();
    }

    /**
     * Puts the editor back after a question, on the viewer's thread.
     *
     * <p>A viewer who left meanwhile ends it as a cancel; an editor that ended
     * while the question was open (the plugin disabled) stays ended.
     */
    private void comeBack() {
        Player viewer = viewer();
        if (finished) {
            return;
        }
        if (viewer == null || !viewer.isOnline() || !plugin.isEnabled()) {
            cancel();
            return;
        }
        Tasks.of(plugin).runAtEntity(viewer, () -> {
            asking = false;
            show(viewer);
        }, this::cancel);
    }

    /** The window closed under the viewer: walking away, unless it was asking. */
    void closed() {
        if (asking) {
            return;
        }
        cancel();
    }

    private void end(Player viewer, boolean save) {
        if (save) {
            save();
        } else {
            cancel();
        }
        viewer.closeInventory();
    }

    void save() {
        if (!finish()) {
            return;
        }
        PolicySet edited = draft.result();
        later(() -> onSave.accept(edited), "save its policies");
    }

    void cancel() {
        if (!finish()) {
            return;
        }
        later(onCancel, "cancel");
    }

    private boolean finish() {
        if (finished) {
            return false;
        }
        finished = true;
        OPEN.remove(this);
        return true;
    }

    /**
     * Runs a caller's callback on the tick after, guarded.
     *
     * <p>The callback usually opens the screen the editor came from, and opening
     * a window while the server still handles the one closing is how a client
     * ends up looking at a window the server does not think it has.
     */
    private void later(Runnable callback, String what) {
        Runnable guarded = () -> {
            try {
                callback.run();
            } catch (RuntimeException broken) {
                Debug.of(plugin).error("A policy editor could not " + what + ".", broken);
            }
        };
        Player viewer = viewer();
        if (viewer == null || !plugin.isEnabled()) {
            // Nobody to schedule around, or a plugin that can no longer schedule:
            // whoever is ending this is already on the thread that owns the work.
            guarded.run();
            return;
        }
        Tasks.of(plugin).runAtEntity(viewer, guarded, guarded);
    }

    /** The policy editor a window belongs to, if it is one. */
    static @Nullable PolicyEditorHolder of(@Nullable Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        return inventory.getHolder(false) instanceof PolicyEditorHolder holder ? holder : null;
    }

    /** Ends, without saving, every editor of a plugin being disabled. */
    public static void release(@NotNull String pluginName) {
        Objects.requireNonNull(pluginName, "pluginName");
        for (PolicyEditorHolder holder : List.copyOf(OPEN)) {
            if (holder.plugin.getName().equals(pluginName)) {
                holder.endNow();
            }
        }
    }

    /** Ends every editor. Called on shutdown. */
    public static void releaseAll() {
        for (PolicyEditorHolder holder : List.copyOf(OPEN)) {
            holder.endNow();
        }
        OPEN.clear();
    }

    /** How many editors are open. For tests. */
    static int open() {
        return OPEN.size();
    }

    private void endNow() {
        boolean wasAsking = asking;
        cancel();
        Player viewer = viewer();
        if (!wasAsking && viewer != null && viewer.isOnline()
                && of(viewer.getOpenInventory().getTopInventory()) == this) {
            viewer.closeInventory();
        }
    }
}
