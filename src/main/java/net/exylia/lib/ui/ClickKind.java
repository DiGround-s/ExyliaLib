package net.exylia.lib.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * The kinds of click a menu button can answer to.
 *
 * <p>These are the names existing configuration already uses, so a menu
 * written for ExyliaCommons keeps working:
 *
 * <pre>
 * actions:
 *   - "left: practice:adjust_priority 1"
 *   - "right: practice:adjust_priority -1"
 *   - "shift_left: practice:adjust_priority 10"
 *   - "left,right: practice:open_details"
 * </pre>
 *
 * <p>Belongs to the UI module, not to the action module: {@code left} is a
 * property of a screen, and an action should not have to know what a mouse is.
 *
 * @since 1.22.0
 */
public enum ClickKind {

    LEFT,
    RIGHT,
    MIDDLE,
    SHIFT_LEFT,
    SHIFT_RIGHT,
    DROP,
    CONTROL_DROP,
    SWAP,

    /**
     * Kept so a menu written against an older version still loads.
     *
     * <p>Menus never deliver it: a double-click is collect-to-cursor, which
     * would sweep buttons out of the window, and the click that began it has
     * already run whatever it was bound to. Bind {@code left} instead.
     *
     * @deprecated a binding on this never runs
     */
    @Deprecated
    DOUBLE,

    NUMBER_KEY;

    /** Every kind, for a binding written without a prefix. */
    public static final Set<ClickKind> ANY = Set.copyOf(EnumSet.allOf(ClickKind.class));

    /**
     * Reads a click name as written in configuration.
     *
     * <p>Both {@code shift_left} and {@code shift-left} are accepted, because
     * both appear in menus in the wild.
     *
     * @param name the name
     * @return the kind, or {@code null} when the name is not one
     */
    public static @Nullable ClickKind byName(@NotNull String name) {
        String normalised = name.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalised) {
            case "LEFT" -> LEFT;
            case "RIGHT" -> RIGHT;
            case "MIDDLE" -> MIDDLE;
            case "SHIFT_LEFT" -> SHIFT_LEFT;
            case "SHIFT_RIGHT" -> SHIFT_RIGHT;
            case "DROP" -> DROP;
            case "CONTROL_DROP" -> CONTROL_DROP;
            case "SWAP", "SWAP_OFFHAND" -> SWAP;
            case "DOUBLE", "DOUBLE_CLICK" -> DOUBLE;
            case "NUMBER_KEY" -> NUMBER_KEY;
            case "ANY", "ALL" -> null;
            default -> null;
        };
    }

    /**
     * Translates what the server reports into what configuration calls it.
     *
     * @param click the Bukkit click type
     * @return the matching kind, or {@code null} for clicks menus do not bind,
     *         which includes a double-click
     */
    public static @Nullable ClickKind of(@NotNull org.bukkit.event.inventory.ClickType click) {
        return switch (click) {
            case LEFT -> LEFT;
            case RIGHT -> RIGHT;
            case MIDDLE -> MIDDLE;
            case SHIFT_LEFT -> SHIFT_LEFT;
            case SHIFT_RIGHT -> SHIFT_RIGHT;
            case DROP -> DROP;
            case CONTROL_DROP -> CONTROL_DROP;
            case SWAP_OFFHAND -> SWAP;
            case NUMBER_KEY -> NUMBER_KEY;
            default -> null;
        };
    }

    /** Returns whether this is a shift-click of either button. */
    public boolean isShift() {
        return this == SHIFT_LEFT || this == SHIFT_RIGHT;
    }
}
