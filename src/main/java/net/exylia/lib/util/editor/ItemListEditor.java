package net.exylia.lib.util.editor;

import net.exylia.lib.item.Source;
import net.exylia.lib.input.internal.InsertWindow;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * How a real item draws and edits itself in a list.
 *
 * <p>Handed to the list editor by {@link PluginEditors#items}. Both adding and
 * editing open the one-slot window: an item is not something you type, it is
 * something you put down.
 *
 * <p>What is kept is the whole {@link ItemStack} — its stack size, its model,
 * its enchantments — because the lists this edits are kits and shop stock, where
 * all of that is the point.
 *
 * @since 1.56.0
 */
final class ItemListEditor implements EditorDescriptor<ItemStack> {

    /** The clipboard bucket item lists share. */
    static final String TYPE_KEY = "exylia:items";

    private final Plugin plugin;

    ItemListEditor(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public @NotNull String label(@NotNull ItemStack entry) {
        return "{primary}&l" + entry.getType().name().replace('_', ' ');
    }

    @Override
    public @NotNull String icon(@NotNull ItemStack entry) {
        return Source.of(entry).raw();
    }

    @Override
    public @NotNull List<String> lore(@NotNull ItemStack entry) {
        return List.of("{secondary}Stack:",
                " {letters_black}▎ {letters}Amount {letters_black}» {info}" + entry.getAmount());
    }

    @Override
    public @NotNull ItemStack create() {
        throw new IllegalStateException("an item is always inserted, never created blank");
    }

    @Override
    public @NotNull CompletionStage<Optional<ItemStack>> create(@NotNull Player viewer) {
        return InsertWindow.openForItem(plugin, viewer, "{primary}&lINSERT AN ITEM");
    }

    @Override
    public @NotNull ItemStack copy(@NotNull ItemStack entry) {
        return entry.clone();
    }

    @Override
    public @NotNull String typeKey() {
        return TYPE_KEY;
    }

    /** Editing an item means putting a different one down. */
    @Override
    public @NotNull CompletionStage<Optional<ItemStack>> edit(@NotNull Player viewer,
                                                              @NotNull ItemStack entry) {
        return InsertWindow.openForItem(plugin, viewer, "{primary}&lREPLACE THE ITEM");
    }
}
