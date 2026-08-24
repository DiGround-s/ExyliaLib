package net.exylia.lib.util.editor;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Screens for editing the things every Exylia plugin configures.
 *
 * <pre>{@code
 * Editors.of(this).list(new WarpDescriptor(), Warp.class, warps)
 *         .title("{primary}&lWARPS")
 *         .onSave(store::save)
 *         .open(player);
 * }</pre>
 *
 * <h2>What this replaces</h2>
 * ExyliaCommons shipped five of these screens — rewards, loot, potion effects,
 * commands, messages — copy-pasted from each other, and one of them addressed
 * rows by slot index, so editing a row after the list had changed edited a
 * different row. There is one screen here, parameterised by an
 * {@link EditorDescriptor}, and the library ships the descriptors for the types
 * it already owns.
 *
 * <h2>Batteries included</h2>
 * A generic engine nobody has a use for is a generic engine nobody uses. The
 * editors for rewards, loot tables, commands, effects, locations and items come
 * with the library; {@link PluginEditors#list} is for the sixth thing, the one
 * only your plugin has.
 *
 * @since 1.56.0
 */
public final class Editors {

    private Editors() {
        throw new AssertionError("No instances.");
    }

    /**
     * The editors owned by one plugin.
     *
     * @param plugin the owner
     * @return its editors
     */
    public static @NotNull PluginEditors of(@NotNull Plugin plugin) {
        return new PluginEditors(Objects.requireNonNull(plugin, "plugin"));
    }
}
