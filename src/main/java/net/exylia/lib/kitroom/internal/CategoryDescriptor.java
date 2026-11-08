package net.exylia.lib.kitroom.internal;

import net.exylia.lib.input.FormKey;
import net.exylia.lib.kitroom.KitRoomCategory;
import net.exylia.lib.text.Lore;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.util.editor.EditorDescriptor;
import net.exylia.lib.util.editor.EditorForm;
import net.exylia.lib.util.editor.Editors;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * How a kit room category draws and edits itself in the staff editor.
 *
 * <p>Editing asks for the name, prefilled, then opens the category's items in
 * the item list editor; cancelling the items keeps the new name.
 */
public final class CategoryDescriptor implements EditorDescriptor<KitRoomCategory> {

    private static final FormKey<String> NAME = FormKey.text("name");

    private final Plugin plugin;

    public CategoryDescriptor(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String label(@NotNull KitRoomCategory category) {
        return Lore.name(Lore.Tone.PRIMARY, Phrases.tr("Category"), category.name());
    }

    @Override
    public @NotNull String icon(@NotNull KitRoomCategory category) {
        return category.icon();
    }

    @Override
    public @NotNull List<String> lore(@NotNull KitRoomCategory category) {
        return Lore.of(Lore.Tone.PRIMARY)
                .subtitle(Phrases.tr("Kit room category"))
                .section("✦", Phrases.tr("Status"))
                .stat("hopper", "info", Phrases.tr("Items"), String.valueOf(category.items().length))
                .lines();
    }

    @Override
    public @NotNull KitRoomCategory create() {
        String id = "category-" + UUID.randomUUID().toString().substring(0, 8);
        return new KitRoomCategory(id, Phrases.tr("New category"), Integer.MAX_VALUE, new ItemStack[0]);
    }

    @Override
    public @NotNull KitRoomCategory copy(@NotNull KitRoomCategory category) {
        KitRoomCategory fresh = create();
        return new KitRoomCategory(fresh.id(), category.name(), category.priority(), category.items().clone());
    }

    @Override
    public @NotNull String typeKey() {
        return "exylia:kitroom-categories";
    }

    @Override
    public @NotNull CompletionStage<Optional<KitRoomCategory>> edit(@NotNull Player viewer,
                                                                    @NotNull KitRoomCategory category) {
        return EditorForm.of(plugin, viewer, Phrases.tr("{primary}&lCATEGORY"))
                .text(NAME, Phrases.tr("Name"), category.name())
                .required()
                .hint(Phrases.tr("What players see on the tab. Colour codes work."))
                .ask(values -> values.getText(NAME).trim())
                .thenCompose(name -> name.isEmpty()
                        ? CompletableFuture.completedFuture(Optional.<KitRoomCategory>empty())
                        : items(viewer, category, name.get()));
    }

    private CompletionStage<Optional<KitRoomCategory>> items(Player viewer, KitRoomCategory category, String name) {
        CompletableFuture<Optional<KitRoomCategory>> done = new CompletableFuture<>();
        Editors.of(plugin).items(category.itemList())
                .title(Phrases.tr("{primary}&lKIT ROOM {letters_black}» {highlight}{0}", name))
                .onSave(items -> done.complete(Optional.of(category.with(name, category.priority(), items))))
                .onCancel(() -> done.complete(Optional.of(category.with(name, category.priority(), category.itemList()))))
                .open(viewer);
        return done;
    }
}
