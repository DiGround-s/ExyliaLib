package net.exylia.lib.hotbar;

import net.exylia.lib.item.Item;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * One button of a {@link HotbarSet}.
 *
 * @param id         its name within the set: letters, digits, {@code _} and {@code -}
 * @param slot       the inventory index it is drawn in, as {@code player.getInventory().setItem}
 *                   numbers it ({@code 0-8} the hotbar)
 * @param item       what it looks like
 * @param permission what a player must hold to have it; {@code null} for everybody
 * @param locked     what a player without the permission sees instead, telling them how to get
 *                   it; {@code null} hides the slot from them
 * @param cooldown   time between two presses by one player; {@link Duration#ZERO} for none
 * @param actions    action lines with click prefixes, as an overlay or menu item writes them:
 *                   {@code right: lobby:selector}
 * @param commands   command lines with click prefixes: {@code right: player: spawn}
 * @since 1.559.0
 */
public record HotbarItem(@NotNull String id, int slot, @NotNull Item item, @Nullable String permission,
                         @Nullable Item locked, @NotNull Duration cooldown, @NotNull List<String> actions,
                         @NotNull List<String> commands) {

    public HotbarItem {
        Objects.requireNonNull(id, "id");
        if (!id.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid hotbar item id: " + id);
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(cooldown, "cooldown");
        if (cooldown.isNegative()) throw new IllegalArgumentException("A cooldown cannot be negative: " + cooldown);
        actions = List.copyOf(actions);
        commands = List.copyOf(commands);
        if (permission != null && permission.isBlank()) permission = null;
    }

    /** A button everybody has, with no cooldown and nothing bound yet. */
    public static @NotNull HotbarItem of(@NotNull String id, int slot, @NotNull Item item) {
        return new HotbarItem(id, slot, item, null, null, Duration.ZERO, List.of(), List.of());
    }

    /** The same button with these action lines. */
    public @NotNull HotbarItem withActions(@NotNull List<String> actions) {
        return new HotbarItem(id, slot, item, permission, locked, cooldown, actions, commands);
    }

    /** The same button with these command lines. */
    public @NotNull HotbarItem withCommands(@NotNull List<String> commands) {
        return new HotbarItem(id, slot, item, permission, locked, cooldown, actions, commands);
    }

    /** The same button behind a permission, hidden or shown as {@code locked} without it. */
    public @NotNull HotbarItem withPermission(@Nullable String permission, @Nullable Item locked) {
        return new HotbarItem(id, slot, item, permission, locked, cooldown, actions, commands);
    }

    /** The same button with a cooldown between presses. */
    public @NotNull HotbarItem withCooldown(@NotNull Duration cooldown) {
        return new HotbarItem(id, slot, item, permission, locked, cooldown, actions, commands);
    }
}
