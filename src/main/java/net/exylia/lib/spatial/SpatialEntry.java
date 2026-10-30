package net.exylia.lib.spatial;

import net.exylia.lib.display.DisplayModel;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * One thing a spatial menu lists: a piece, a material, a pattern, an effect. What a {@code source}
 * returns, re-read on every redraw. Equal entries are not redrawn, so return the same values for
 * what did not change.
 *
 * <pre>{@code
 * SpatialEntry.of(trim.id(), trim.icon(), "{letters}&l" + trim.name())
 *         .withSubtitle("{muted}" + trim.rarity())
 *         .withState(owned ? SpatialEntry.State.OWNED : SpatialEntry.State.LOCKED)
 *         .withPrice("{warning}⛁ 1,500");
 * }</pre>
 *
 * @param id       what the plugin knows it by; what {@code onSelect} and {@link SpatialContext#selected} give back
 * @param icon     the item drawn for it; {@code null} when {@code model} draws it
 * @param model    a display drawn instead of the icon (a block, a text glyph); {@code null} for the icon
 * @param name     its name, with colour placeholders
 * @param subtitle a short line under the name; blank for none
 * @param state    what the player has of it, which decides its marker
 * @param price    what it costs, shown in place of the locked line while {@link State#LOCKED}; {@code null} for none
 * @since 1.406.0
 */
public record SpatialEntry(@NotNull String id, @Nullable ItemStack icon, @Nullable DisplayModel model,
                           @NotNull String name, @NotNull String subtitle, @NotNull State state,
                           @Nullable String price) {

    /** What a player has of an entry. */
    public enum State {
        /** Theirs to pick; no marker. */
        AVAILABLE,
        /** Not theirs: dimmed, with the locked line or the price. */
        LOCKED,
        /** Theirs, not in use. */
        OWNED,
        /** Theirs and worn now: the success marker. */
        EQUIPPED,
        /** The one chosen when the menu opens. */
        SELECTED
    }

    public SpatialEntry {
        Objects.requireNonNull(id, "id");
        name = name == null ? "" : name;
        subtitle = subtitle == null ? "" : subtitle;
        state = state == null ? State.AVAILABLE : state;
        icon = icon == null ? null : icon.clone();
    }

    /** An available entry with an icon and a name. */
    public static @NotNull SpatialEntry of(@NotNull String id, @Nullable ItemStack icon, @NotNull String name) {
        return new SpatialEntry(id, icon, null, name, "", State.AVAILABLE, null);
    }

    public @NotNull SpatialEntry withIcon(@Nullable ItemStack icon) {
        return new SpatialEntry(id, icon, model, name, subtitle, state, price);
    }

    public @NotNull SpatialEntry withModel(@Nullable DisplayModel model) {
        return new SpatialEntry(id, icon, model, name, subtitle, state, price);
    }

    public @NotNull SpatialEntry withName(@NotNull String name) {
        return new SpatialEntry(id, icon, model, name, subtitle, state, price);
    }

    public @NotNull SpatialEntry withSubtitle(@NotNull String subtitle) {
        return new SpatialEntry(id, icon, model, name, subtitle, state, price);
    }

    public @NotNull SpatialEntry withState(@NotNull State state) {
        return new SpatialEntry(id, icon, model, name, subtitle, state, price);
    }

    public @NotNull SpatialEntry withPrice(@Nullable String price) {
        return new SpatialEntry(id, icon, model, name, subtitle, state, price);
    }

    public boolean locked() {
        return state == State.LOCKED;
    }
}
