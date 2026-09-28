package net.exylia.lib.region.internal;

import net.exylia.lib.util.editor.EditorDescriptor;
import net.exylia.lib.util.editor.Editors;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * A block list row, edited in the ordinary list editor: add asks the material
 * picker, a click swaps a material for another, a right click removes it.
 */
@ApiStatus.Internal
final class MaterialDescriptor implements EditorDescriptor<Material> {

    private final Plugin plugin;

    MaterialDescriptor(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String label(@NotNull Material entry) {
        return "{primary}&l" + entry.name().replace('_', ' ');
    }

    @Override
    public @NotNull String icon(@NotNull Material entry) {
        return entry.name();
    }

    @Override
    public @NotNull List<String> lore(@NotNull Material entry) {
        return List.of("", "{warning}➥ Click to swap", "{error}➥ Right-click to remove", "");
    }

    @Override
    public @NotNull Material create() {
        return Material.STONE;
    }

    @Override
    public @NotNull CompletionStage<Optional<Material>> create(@NotNull Player viewer) {
        return pick(viewer);
    }

    /** The picker already answered: there is nothing left to configure. */
    @Override
    public boolean editsNew() {
        return false;
    }

    /** A material is a value; two rows of it collapse when the list is saved. */
    @Override
    public @NotNull Material copy(@NotNull Material entry) {
        return entry;
    }

    @Override
    public @NotNull CompletionStage<Optional<Material>> edit(@NotNull Player viewer,
                                                             @NotNull Material entry) {
        return pick(viewer);
    }

    @Override
    public @NotNull String typeKey() {
        return "exylia:materials";
    }

    private CompletionStage<Optional<Material>> pick(Player viewer) {
        return Editors.of(plugin).pick().material(viewer)
                .thenApply(name -> name.map(Material::matchMaterial));
    }
}
