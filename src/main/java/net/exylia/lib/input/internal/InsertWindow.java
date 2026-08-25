package net.exylia.lib.input.internal;

import net.exylia.lib.item.Source;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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
    private final CompletableFuture<Optional<ItemStack>> answer = new CompletableFuture<>();
    private Inventory inventory;
    private boolean finished;

    private InsertWindow(Plugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewerId = viewer.getUniqueId();
    }

    /**
     * Opens the window and answers with a stored icon source.
     *
     * @param plugin who is asking
     * @param viewer who is inserting
     * @param title  the window title, in Exylia text notation
     * @return the icon source, or nothing when they closed it empty-handed
     */
    public static @NotNull CompletionStage<Optional<String>> open(@NotNull Plugin plugin,
                                                                  @NotNull Player viewer,
                                                                  @NotNull String title) {
        return openForItem(plugin, viewer, title)
                .thenApply(item -> item.map(stack -> Source.of(stack).raw()));
    }

    /**
     * Opens the window and answers with the item itself.
     *
     * <p>For a list of real items — a kit, a shop's stock — where the stack size
     * and every detail of the object matter. The answer is a clone: the item the
     * player lent goes back to them, and what the caller keeps cannot be changed
     * out from under it by the next thing they do with their inventory.
     *
     * @param plugin who is asking
     * @param viewer who is inserting
     * @param title  the window title
     * @return the item, or nothing
     */
    public static @NotNull CompletionStage<Optional<ItemStack>> openForItem(@NotNull Plugin plugin,
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
        ItemStack filler = pane();
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(SLOT, null);
        inventory.setItem(SLOT_CONFIRM, button(Material.LIME_DYE, "{success}&lUSE THIS ITEM",
                "{letters_black}▎ {letters}Read the item above and use",
                "{letters_black}▎ {letters}it as the icon.",
                "",
                "{letters_black}▎ {letters}You get the item back either way.",
                "",
                "{warning}➥ Click to confirm"));
    }

    /**
     * A click in the window.
     *
     * <p>The one slot behaves like a real container slot &mdash; that is the
     * whole point of the window &mdash; and every other slot in it is a screen.
     * A click in the player's own inventory is left alone so items can be
     * shift-moved in; the only empty slot up here is the one we want.
     *
     * @param viewer who clicked
     * @param event  the click
     */
    public void click(@NotNull Player viewer, @NotNull InventoryClickEvent event) {
        if (finished) {
            event.setCancelled(true);
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        if (event.getSlot() == SLOT_CONFIRM) {
            event.setCancelled(true);
            confirm(viewer);
            return;
        }
        if (event.getSlot() != SLOT) {
            event.setCancelled(true);
        }
    }

    /**
     * A drag over the window: allowed into the one slot and nowhere else.
     *
     * @param event the drag
     */
    public static void drag(@NotNull InventoryDragEvent event) {
        int top = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < top && slot != SLOT) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** What the confirm button does. */
    private void confirm(Player viewer) {
        ItemStack inserted = inventory.getItem(SLOT);
        if (inserted == null || inserted.getType() == Material.AIR) {
            return;
        }
        // Cloned, not taken: the caller keeps a copy and the player keeps the
        // item they lent.
        complete(Optional.of(inserted.clone()));
        viewer.closeInventory();
    }

    /**
     * Gives back whatever is in the slot and ends the question.
     *
     * <p>Runs for every ending. What does not fit is dropped at the player's
     * feet rather than discarded — the item was theirs before they lent it to
     * this window.
     */
    public void release(@Nullable Player viewer) {
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

    private static ItemStack pane() {
        return button(Material.GRAY_STAINED_GLASS_PANE, " ");
    }

    /**
     * Builds one of the window's own items.
     *
     * <p>Italics are switched off explicitly, because vanilla italicises any
     * name or lore a plugin sets and the palette's intent would otherwise be
     * rendered in a style nobody asked for.
     */
    private static ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(plain(name));
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>(lore.length);
            for (String line : lore) {
                lines.add(plain(line));
            }
            meta.lore(lines);
        }
        item.setItemMeta(meta);
        return item;
    }

    private static Component plain(String text) {
        return Text.of(text).build()
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private void complete(Optional<ItemStack> value) {
        if (finished) {
            return;
        }
        finished = true;
        answer.complete(value);
    }

}
