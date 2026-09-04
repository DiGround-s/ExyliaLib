package net.exylia.lib.util.editor.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.ui.ClickPolicy;
import net.exylia.lib.util.editor.Loadout;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One open loadout editor, and the window it is drawn in.
 *
 * <p>State lives on the window, never in a {@code Map<UUID, Session>}: a player
 * with a chest open on top of an editor is looking at the chest, and a map would
 * still say the editor is theirs.
 *
 * <h2>The slots are real</h2>
 * Forty-one of them belong to the viewer while the window is open, which is what
 * makes this an editor rather than a screen of buttons. Everything else in the
 * window is cancelled, and {@link ClickPolicy} — the menu module's own — decides
 * which is which, so the shift-click and double-click cases that duplicate items
 * are refused here exactly as they are in a menu.
 */
@ApiStatus.Internal
public final class LoadoutHolder implements InventoryHolder {

    static final int SIZE = 54;

    private static final int SLOT_LABEL = 5;
    private static final int SLOT_SAVE = 45;
    private static final int SLOT_IMPORT = 46;
    private static final int SLOT_CANCEL = 49;
    private static final int SLOT_CLEAR = 53;

    /** The head ExyliaCommons drew a cancel button with. */
    private static final String CANCEL_HEAD = "basehead-eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6"
            + "Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjIzZmI2NzQyOTcxNmIyMWJjNmU4"
            + "ZTdkNjY5Y2VkZGY2NWIxM2UwNzkwYTVjZTU1YjJlMDc3YjgyZDE5ZTEyNCJ9fX0=";

    /** Which grid slot each loadout position is drawn in. */
    private static final List<Integer> SLOTS = Loadout.editorSlots();

    private static final Set<Integer> INPUT_SLOTS = Set.copyOf(SLOTS);

    private final Plugin plugin;
    private final String title;
    private final List<ItemStack> items;
    private final Consumer<List<ItemStack>> onSave;
    private final Runnable onCancel;
    private final UUID viewerId;

    private Inventory inventory;
    private boolean finished;

    LoadoutHolder(Plugin plugin, String title, List<ItemStack> items,
                  Consumer<List<ItemStack>> onSave, Runnable onCancel, Player viewer) {
        this.plugin = plugin;
        this.title = title;
        this.items = items;
        this.onSave = onSave;
        this.onCancel = onCancel;
        this.viewerId = viewer.getUniqueId();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    Plugin plugin() {
        return plugin;
    }

    String title() {
        return title;
    }

    UUID viewerId() {
        return viewerId;
    }

    @Nullable Player viewer() {
        return Bukkit.getPlayer(viewerId);
    }

    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    /** Draws the frame, the buttons and the loadout it was opened on. */
    void draw() {
        ItemStack filler = Icons.button(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, INPUT_SLOTS.contains(slot) ? null : filler);
        }
        inventory.setItem(SLOT_LABEL, Icons.button(Material.ARMOR_STAND,
                "{primary}&l← ARMOUR & OFFHAND", List.of(
                        "",
                        "{secondary}The five slots to the left:",
                        " {letters_black}▎ {letters}1 {letters_black}» {letters}Helmet",
                        " {letters_black}▎ {letters}2 {letters_black}» {letters}Chestplate",
                        " {letters_black}▎ {letters}3 {letters_black}» {letters}Leggings",
                        " {letters_black}▎ {letters}4 {letters_black}» {letters}Boots",
                        " {letters_black}▎ {letters}5 {letters_black}» {letters}Offhand",
                        "",
                        " {letters_black}▎ {letters_black}The rows below are the inventory,",
                        " {letters_black}▎ {letters_black}the last one being the hotbar.",
                        "")));
        inventory.setItem(SLOT_SAVE, Icons.button(Material.EMERALD,
                "{success}&lSAVE & BACK", List.of(
                        "",
                        " {letters_black}▎ {letters}Keeps the layout as it is now.",
                        "",
                        "{warning}➥ Click to save",
                        "")));
        inventory.setItem(SLOT_IMPORT, Icons.button(Material.SHULKER_BOX,
                "{success}&lIMPORT MY INVENTORY", List.of(
                        "",
                        " {letters_black}▎ {letters}Lays your own armour, offhand,",
                        " {letters_black}▎ {letters}inventory and hotbar into the grid.",
                        " {letters_black}▎ {letters_black}Replaces whatever is there now.",
                        "",
                        "{warning}➥ Click to import",
                        "")));
        inventory.setItem(SLOT_CANCEL, Icons.row(CANCEL_HEAD,
                "{error}&lCANCEL", List.of(
                        "",
                        " {letters_black}▎ {letters}Leaves it exactly as it was.",
                        "",
                        "{error}➥ Click to discard",
                        "")));
        inventory.setItem(SLOT_CLEAR, Icons.button(Material.TNT,
                "{error}&lCLEAR THE GRID", List.of(
                        "",
                        " {letters_black}▎ {letters}Empties every slot.",
                        " {letters_black}▎ {letters_black}Nothing is written until you leave.",
                        "",
                        "{error}➥ Click to clear",
                        "")));
        write(items);
    }

    /**
     * A click in or under the window.
     *
     * @param viewer who clicked
     * @param inTop  whether it landed in the editor rather than their own bags
     * @param shift  whether it was a shift-click
     * @param twice  whether it was a double-click
     * @param slot   the slot, as the client numbered it
     * @return whether to cancel the click
     */
    boolean click(@NotNull Player viewer, boolean inTop, boolean shift, boolean twice, int slot) {
        if (finished) {
            return true;
        }
        ClickPolicy.Decision decision =
                ClickPolicy.decide(true, inTop, shift, twice, slot, INPUT_SLOTS);
        if (decision == ClickPolicy.Decision.ALLOW) {
            return false;
        }
        if (decision == ClickPolicy.Decision.BUTTON) {
            press(viewer, slot);
        }
        return true;
    }

    /** A drag over the window: allowed into the grid and nowhere else. */
    boolean refuseDrag(@NotNull Iterable<Integer> touched) {
        return finished || ClickPolicy.refuseDrag(SIZE, touched, INPUT_SLOTS);
    }

    private void press(Player viewer, int slot) {
        switch (slot) {
            case SLOT_SAVE -> {
                save();
                viewer.closeInventory();
            }
            case SLOT_CANCEL -> {
                cancel();
                viewer.closeInventory();
            }
            case SLOT_IMPORT -> write(Loadout.capture(viewer));
            case SLOT_CLEAR -> write(List.of());
            default -> { }
        }
    }

    /** Writes a loadout into the grid, emptying whatever the positions held. */
    private void write(List<ItemStack> loadout) {
        for (int index = 0; index < SLOTS.size(); index++) {
            inventory.setItem(SLOTS.get(index), Loadout.at(loadout, index));
        }
    }

    /** What is in the grid now, in position order. */
    private List<ItemStack> read() {
        List<ItemStack> saved = new ArrayList<>(SLOTS.size());
        for (int slot : SLOTS) {
            saved.add(inventory.getItem(slot));
        }
        return Loadout.trim(saved);
    }

    /** Keeps the layout. The save button, and every ordinary close. */
    void save() {
        if (!finish()) {
            return;
        }
        List<ItemStack> saved = read();
        later(() -> onSave.accept(saved), "save a loadout");
    }

    /** Throws it away. The cancel button, and the owning plugin disabling. */
    void cancel() {
        if (!finish()) {
            return;
        }
        later(onCancel, "close a loadout editor");
    }

    boolean isFinished() {
        return finished;
    }

    private boolean finish() {
        if (finished) {
            return false;
        }
        finished = true;
        return true;
    }

    /**
     * Runs a caller's callback on the next tick, and not before.
     *
     * <p>Every ending happens inside a click or a close, and what a callback
     * almost always does is open the screen the editor was entered from. Opening
     * an inventory while the server is still handling the one that is closing is
     * how a client ends up looking at a window the server does not think it has
     * — so the callback waits a tick, and no caller has to know that.
     *
     * <p>It is also the one place control leaves the module, so the one place
     * that has to survive the code it hands control to.
     */
    private void later(Runnable callback, String what) {
        Runnable guarded = () -> {
            try {
                callback.run();
            } catch (RuntimeException broken) {
                Debug.of(plugin).error("A loadout editor could not " + what + ".", broken);
            }
        };
        Player viewer = viewer();
        if (viewer == null) {
            // Nobody left to schedule around: whoever is ending this is already
            // on the thread that owns the work.
            guarded.run();
            return;
        }
        // Told twice on purpose: a viewer who left mid-save still gets their
        // layout written, which is the half of this that is not about a screen.
        Tasks.of(EditorRuntime.scheduler(plugin)).runAtEntity(viewer, guarded, guarded);
    }
}
