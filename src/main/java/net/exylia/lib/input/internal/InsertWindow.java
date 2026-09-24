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

    /** The bulk window: five open rows over a row of fillers. */
    private static final int BULK_SIZE = 54;
    private static final int BULK_SLOTS = 45;
    private static final int BULK_CONFIRM = 49;

    private final Plugin plugin;
    private final UUID viewerId;
    private final boolean bulk;
    private final CompletableFuture<List<ItemStack>> answer = new CompletableFuture<>();
    private Inventory inventory;
    private boolean finished;

    private InsertWindow(Plugin plugin, Player viewer, boolean bulk) {
        this.plugin = plugin;
        this.viewerId = viewer.getUniqueId();
        this.bulk = bulk;
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
        return show(new InsertWindow(plugin, viewer, false), viewer, title)
                .thenApply(items -> items.stream().findFirst());
    }

    /**
     * Opens a window with five open rows and answers with everything put in it.
     *
     * <p>For adding many items at once: shift-click them in from the inventory
     * and confirm once. Each answer is a clone, and every item goes back to the
     * player exactly as with the one-slot window.
     *
     * @param plugin who is asking
     * @param viewer who is inserting
     * @param title  the window title
     * @return the items in slot order, empty when they closed it empty-handed
     * @since 1.196.0
     */
    public static @NotNull CompletionStage<List<ItemStack>> openForItems(@NotNull Plugin plugin,
                                                                         @NotNull Player viewer,
                                                                         @NotNull String title) {
        return show(new InsertWindow(plugin, viewer, true), viewer, title);
    }

    private static CompletionStage<List<ItemStack>> show(InsertWindow window, Player viewer,
                                                         String title) {
        Tasks.of(window.plugin).runAtEntity(viewer, () -> {
            Inventory inventory = Bukkit.createInventory(window, window.bulk ? BULK_SIZE : SIZE,
                    Text.from(window.plugin, title).forPlayer(viewer).build());
            window.inventory = inventory;
            window.draw();
            viewer.openInventory(inventory);
        });
        return window.answer;
    }

    private boolean isInput(int slot) {
        return bulk ? slot >= 0 && slot < BULK_SLOTS : slot == SLOT;
    }

    private int confirmSlot() {
        return bulk ? BULK_CONFIRM : SLOT_CONFIRM;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private void draw() {
        ItemStack filler = pane();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, isInput(slot) ? null : filler);
        }
        if (bulk) {
            inventory.setItem(BULK_CONFIRM, button(Material.LIME_DYE, "{success}&lUSE THESE ITEMS",
                    "{letters_black}▎ {letters}Each item above becomes",
                    "{letters_black}▎ {letters}its own entry.",
                    "",
                    "{letters_black}▎ {letters}You get the items back either way.",
                    "",
                    "{warning}➥ Click to confirm"));
            return;
        }
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
        if (event.getSlot() == confirmSlot()) {
            event.setCancelled(true);
            confirm(viewer);
            return;
        }
        if (!isInput(event.getSlot())) {
            event.setCancelled(true);
        }
    }

    /**
     * A drag over the window: allowed into the open slots and nowhere else.
     *
     * @param event the drag
     */
    public void drag(@NotNull InventoryDragEvent event) {
        int top = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < top && !isInput(slot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** What the confirm button does. */
    private void confirm(Player viewer) {
        List<ItemStack> inserted = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = isInput(slot) ? inventory.getItem(slot) : null;
            if (item != null && item.getType() != Material.AIR) {
                // Cloned, not taken: the caller keeps a copy and the player
                // keeps the item they lent.
                inserted.add(item.clone());
            }
        }
        if (inserted.isEmpty()) {
            return;
        }
        complete(inserted);
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
        List<ItemStack> lent = new ArrayList<>();
        if (inventory != null) {
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                ItemStack item = isInput(slot) ? inventory.getItem(slot) : null;
                if (item != null && item.getType() != Material.AIR) {
                    lent.add(item);
                    inventory.setItem(slot, null);
                }
            }
        }
        complete(List.of());
        if (lent.isEmpty() || viewer == null) {
            return;
        }
        Map<Integer, ItemStack> leftOver = viewer.getInventory().addItem(lent.toArray(ItemStack[]::new));
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

    private void complete(List<ItemStack> value) {
        if (finished) {
            return;
        }
        finished = true;
        answer.complete(value);
    }

}
