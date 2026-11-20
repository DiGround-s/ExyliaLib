package net.exylia.lib.hotbar;

import net.exylia.lib.overlay.OverlayLock;
import net.exylia.lib.ui.UiRefresh;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A named set of hotbar buttons, shown to a player as one.
 *
 * @param name     what the plugin calls it: letters, digits, {@code _} and {@code -}
 * @param items    the buttons, one per slot
 * @param lock     how much of the inventory is frozen; {@code FULL} (nothing moves) by default
 * @param hideRest whether the slots it does not draw look empty; {@code true} by default
 * @param pickup   whether the player may still pick items up; {@code false} by default
 * @param refresh  when the buttons redraw, for names or lore with placeholders; never by default
 * @param hits     whether a left-click on a mob or player that the held button does not answer
 *                 lands as an ordinary hit; {@code false} (swallowed) by default
 * @since 1.559.0
 */
public record HotbarSet(@NotNull String name, @NotNull List<HotbarItem> items, @NotNull OverlayLock lock,
                        boolean hideRest, boolean pickup, @NotNull UiRefresh refresh, boolean hits) {

    public HotbarSet {
        Objects.requireNonNull(name, "name");
        if (!name.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid hotbar set name: " + name);
        items = List.copyOf(items);
        Set<String> ids = new HashSet<>();
        Set<Integer> slots = new HashSet<>();
        for (HotbarItem item : items) {
            if (!ids.add(item.id())) throw new IllegalArgumentException("Hotbar " + name + " has two items called " + item.id());
            if (!slots.add(item.slot())) throw new IllegalArgumentException("Hotbar " + name + " has two items in slot " + item.slot());
        }
        Objects.requireNonNull(lock, "lock");
        Objects.requireNonNull(refresh, "refresh");
    }

    /** A set whose buttons swallow every hit, as before {@code hits} existed. */
    public HotbarSet(@NotNull String name, @NotNull List<HotbarItem> items, @NotNull OverlayLock lock,
                     boolean hideRest, boolean pickup, @NotNull UiRefresh refresh) {
        this(name, items, lock, hideRest, pickup, refresh, false);
    }

    /** A lobby hotbar: everything frozen, the rest blank, no pickups, never redrawn. */
    public static @NotNull HotbarSet of(@NotNull String name, @NotNull List<HotbarItem> items) {
        return new HotbarSet(name, items, OverlayLock.FULL, true, false, UiRefresh.NEVER, false);
    }

    /**
     * The same set, letting a hit the held button does not answer land: for a game played with
     * the hotbar in hand, where hitting mobs or players is part of it.
     *
     * @since 1.640.0
     */
    public @NotNull HotbarSet withHits(boolean hits) {
        return new HotbarSet(name, items, lock, hideRest, pickup, refresh, hits);
    }

    /** The same set, redrawn as {@code refresh} says. */
    public @NotNull HotbarSet withRefresh(@NotNull UiRefresh refresh) {
        return new HotbarSet(name, items, lock, hideRest, pickup, refresh, hits);
    }

    /** The same set with another lock, blank-rest and pickup policy. */
    public @NotNull HotbarSet withInventory(@NotNull OverlayLock lock, boolean hideRest, boolean pickup) {
        return new HotbarSet(name, items, lock, hideRest, pickup, refresh, hits);
    }
}
