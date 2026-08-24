package net.exylia.lib.util.editor.internal;

import net.exylia.lib.item.Source;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A window with one slot: put the item in, and that item is the answer.
 *
 * <p>What ExyliaCommons did instead was read the player's main hand, which meant
 * closing the screen you were on, finding the item, holding it and reopening —
 * and from inside a menu it could not be done at all.
 *
 * <h2>The item always comes back</h2>
 * The answer is a description of the item, not the item. Whatever is in the slot
 * is returned to the player on every ending: confirming, closing the window,
 * leaving the server, the plugin being disabled. An icon picker that ate a
 * diamond sword would be a theft, not a feature.
 *
 * <h2>Clicks are allowed, but only into the slot</h2>
 * Every other slot is a filler and every click on one is cancelled, so the
 * window behaves like a screen everywhere except the one place it is a container.
 */
@ApiStatus.Internal
public final class InsertWindow implements InventoryHolder {

    private static final int SIZE = 27;

    /** The one slot a player may put something in. */
    public static final int SLOT = 13;

    private static final int SLOT_CONFIRM = 22;

    private final Plugin plugin;
    private final UUID viewerId;
    private final CompletableFuture<Optional<String>> answer = new CompletableFuture<>();
    private Inventory inventory;
    private boolean finished;

    private InsertWindow(Plugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewerId = viewer.getUniqueId();
    }

    /**
     * Opens the window.
     *
     * @param plugin who is asking
     * @param viewer who is inserting
     * @param title  the window title, in Exylia text notation
     * @return the icon source, or nothing when they closed it empty-handed
     */
    public static @NotNull CompletionStage<Optional<String>> open(@NotNull Plugin plugin,
                                                                  @NotNull Player viewer,
                                                                  @NotNull String title) {
        InsertWindow window = new InsertWindow(plugin, viewer);
        Tasks.of(plugin).runAtEntity(viewer, () -> {
            Inventory inventory = Bukkit.createInventory(window, SIZE,
                    Text.from(plugin, title).forPlayer(viewer).legacy());
            window.inventory = inventory;
            window.draw();
            viewer.openInventory(inventory);
        });
        return window.answer;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private void draw() {
        ItemStack filler = Icons.button(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(SLOT, null);
        inventory.setItem(SLOT_CONFIRM, Icons.glowing(Material.LIME_DYE, "{success}&lUSE THIS ITEM",
                List.of("{letters_black}▎ {letters}Read the item above and use",
                        "{letters_black}▎ {letters}it as the icon.",
                        "",
                        "{letters_black}▎ {letters}You get the item back either way.",
                        "",
                        "{warning}➥ Click to confirm")));
    }

    /** Whether a slot may be clicked normally rather than cancelled. */
    boolean isFree(int slot) {
        return slot == SLOT;
    }

    /** What the confirm button does. */
    void confirm(Player viewer) {
        ItemStack inserted = inventory.getItem(SLOT);
        if (inserted == null || inserted.getType() == Material.AIR) {
            return;
        }
        // Described, not taken: the source is what a menu file would write.
        complete(Optional.of(Source.of(inserted).raw()));
        viewer.closeInventory();
    }

    /**
     * Gives back whatever is in the slot and ends the question.
     *
     * <p>Runs for every ending. What does not fit is dropped at the player's
     * feet rather than discarded — the item was theirs before they lent it to
     * this window.
     */
    void release(@Nullable Player viewer) {
        ItemStack inserted = inventory == null ? null : inventory.getItem(SLOT);
        if (inventory != null) {
            inventory.setItem(SLOT, null);
        }
        complete(Optional.empty());
        if (inserted == null || inserted.getType() == Material.AIR || viewer == null) {
            return;
        }
        Map<Integer, ItemStack> leftOver = viewer.getInventory().addItem(inserted);
        for (ItemStack rest : leftOver.values()) {
            viewer.getWorld().dropItemNaturally(viewer.getLocation(), rest);
        }
    }

    private void complete(Optional<String> value) {
        if (finished) {
            return;
        }
        finished = true;
        answer.complete(value);
    }

    boolean isFinished() {
        return finished;
    }

    UUID viewerId() {
        return viewerId;
    }

    Plugin plugin() {
        return plugin;
    }

    static int confirmSlot() {
        return SLOT_CONFIRM;
    }
}
