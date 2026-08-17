package net.exylia.lib.ui.internal;

import net.exylia.lib.ui.UiAnimationSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * The order slots appear in when a menu opens.
 *
 * <p>An animation is a list of frames, each frame a group of slots that appear
 * together. Nothing here touches an inventory or a scheduler: the whole thing
 * is arithmetic over a grid, so it can be tested without a server and the
 * result is cached rather than recomputed for every player who opens the menu.
 *
 * <p>All seventeen shapes ExyliaCommons offered are here. Only two appear in
 * deployed files today — {@code center_out} in eighty-one and
 * {@code rows_alternate} in eight — but the rest are what an admin picks from,
 * and a name that silently did nothing would look like a broken menu rather
 * than an unsupported option. They are pure grid arithmetic and cost nothing
 * until asked for.
 *
 * <p>A name that is not one of these is reported through {@code Debug} and the
 * menu appears at once. An animation is decoration; the menu is the point.
 */
final class OpenAnimation {

    /** How many columns a chest row has. */
    private static final int COLUMNS = 9;

    /**
     * Frames worked out once per shape and size.
     *
     * <p>The answer depends on nothing else, and two hundred and forty-seven
     * menus ask the same question. Bounded because the key space is tiny —
     * a handful of names times the six chest sizes — so this can never grow.
     */
    private static final java.util.Map<String, List<List<Integer>>> CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private OpenAnimation() {
    }

    /**
     * Works out the frames of an animation.
     *
     * @param spec what the file asked for
     * @param size how many slots the menu has
     * @return the frames, in order; empty when the menu should simply appear
     */
    static List<List<Integer>> frames(UiAnimationSpec spec, int size) {
        if (spec == null || size <= 0) {
            return List.of();
        }
        return CACHE.computeIfAbsent(spec.type() + '/' + size, ignored -> compute(spec, size));
    }

    /**
     * Returns whether a name is one this knows how to draw.
     *
     * <p>Asked by the loader so a typo is reported when the file is read,
     * rather than silently doing nothing every time a player opens the menu.
     */
    static boolean isKnown(String type) {
        return KNOWN.contains(normalise(type));
    }

    /** Every name understood, in the spelling a file would write. */
    static java.util.Set<String> known() {
        return KNOWN;
    }

    private static final java.util.Set<String> KNOWN = java.util.Set.of(
            "none", "slide_left", "slide_top", "cascade", "center_out", "random",
            "spiral", "spiral_out", "checkerboard", "wave_horizontal", "wave_vertical",
            "corners", "snake", "rows_alternate", "columns_alternate", "explosion",
            "typewriter");

    /** Dashes and camel case are the same thing to an admin. */
    private static String normalise(String type) {
        if (type == null) {
            return "none";
        }
        String lower = type.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (lower) {
            case "centerout" -> "center_out";
            case "rows" -> "rows_alternate";
            case "columns" -> "columns_alternate";
            default -> lower;
        };
    }

    static List<List<Integer>> compute(UiAnimationSpec spec, int size) {
        int rows = Math.max(1, size / COLUMNS);
        return switch (normalise(spec.type())) {
            case "center_out" -> byDistance(size, (row, column) -> {
                double centreRow = (rows - 1) / 2.0;
                double centreColumn = (COLUMNS - 1) / 2.0;
                return (int) Math.round(
                        Math.abs(row - centreRow) + Math.abs(column - centreColumn));
            });
            case "explosion" -> byDistance(size, (row, column) -> {
                // Chebyshev rather than Manhattan: a blast front is a square
                // ring, which is what reads as an explosion on a grid.
                double centreRow = (rows - 1) / 2.0;
                double centreColumn = (COLUMNS - 1) / 2.0;
                return (int) Math.round(Math.max(
                        Math.abs(row - centreRow), Math.abs(column - centreColumn)));
            });
            case "corners" -> byDistance(size, (row, column) -> {
                int[][] corners = {{0, 0}, {0, COLUMNS - 1}, {rows - 1, 0}, {rows - 1, COLUMNS - 1}};
                int nearest = Integer.MAX_VALUE;
                for (int[] corner : corners) {
                    nearest = Math.min(nearest,
                            Math.abs(row - corner[0]) + Math.abs(column - corner[1]));
                }
                return nearest;
            });
            case "cascade" -> byDistance(size, (row, column) -> row + column);
            case "wave_horizontal" -> byDistance(size, (row, column) -> column);
            case "wave_vertical" -> byDistance(size, (row, column) -> row);
            case "slide_left" -> byDistance(size, (row, column) -> column);
            case "slide_top" -> byDistance(size, (row, column) -> row);
            case "checkerboard" -> checkerboard(size);
            case "rows_alternate" -> rowsAlternate(size);
            case "columns_alternate" -> columnsAlternate(size);
            case "snake" -> inGroups(snakeOrder(size), 2);
            case "spiral" -> inGroups(spiralOrder(size), 2);
            case "spiral_out" -> inGroups(reversed(spiralOrder(size)), 2);
            case "typewriter" -> inGroups(allSlots(size), 1);
            case "random" -> inGroups(shuffled(size), 2);
            case "none" -> List.of();
            // Reported by the loader when the file is read. Drawing the menu at
            // once is the right failure: the menu is the point.
            default -> List.of();
        };
    }

    /** Groups every slot by some measure of how far along it is. */
    private static List<List<Integer>> byDistance(int size, Distance distance) {
        // Sorted, so nearer groups come first without a second pass.
        TreeMap<Integer, List<Integer>> groups = new TreeMap<>();
        for (int slot = 0; slot < size; slot++) {
            int row = slot / COLUMNS;
            int column = slot % COLUMNS;
            groups.computeIfAbsent(distance.of(row, column), ignored -> new ArrayList<>())
                    .add(slot);
        }
        return List.copyOf(groups.values());
    }

    /** How far along a slot is, for animations that are a sweep of some kind. */
    @FunctionalInterface
    private interface Distance {
        int of(int row, int column);
    }

    /** The light squares appear, then the dark ones. */
    private static List<List<Integer>> checkerboard(int size) {
        List<Integer> light = new ArrayList<>();
        List<Integer> dark = new ArrayList<>();
        for (int slot = 0; slot < size; slot++) {
            ((slot / COLUMNS + slot % COLUMNS) % 2 == 0 ? light : dark).add(slot);
        }
        List<List<Integer>> frames = new ArrayList<>(inGroups(light, 4));
        frames.addAll(inGroups(dark, 4));
        return List.copyOf(frames);
    }

    /** Columns appear one at a time, from the left and the right alternately. */
    private static List<List<Integer>> columnsAlternate(int size) {
        List<List<Integer>> frames = new ArrayList<>(COLUMNS);
        int left = 0;
        int right = COLUMNS - 1;
        while (left <= right) {
            frames.add(columnOf(left, size));
            if (left != right) {
                frames.add(columnOf(right, size));
            }
            left++;
            right--;
        }
        return List.copyOf(frames);
    }

    private static List<Integer> columnOf(int column, int size) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = column; slot < size; slot += COLUMNS) {
            slots.add(slot);
        }
        return slots;
    }

    /** Left to right, then right to left, the way a snake doubles back. */
    private static List<Integer> snakeOrder(int size) {
        int rows = Math.max(1, size / COLUMNS);
        List<Integer> order = new ArrayList<>(size);
        for (int row = 0; row < rows; row++) {
            for (int step = 0; step < COLUMNS; step++) {
                int column = row % 2 == 0 ? step : COLUMNS - 1 - step;
                int slot = row * COLUMNS + column;
                if (slot < size) {
                    order.add(slot);
                }
            }
        }
        return order;
    }

    /** Round the outside, then round what is left, inwards. */
    private static List<Integer> spiralOrder(int size) {
        int rows = Math.max(1, size / COLUMNS);
        List<Integer> order = new ArrayList<>(size);
        int top = 0;
        int bottom = rows - 1;
        int left = 0;
        int right = COLUMNS - 1;
        while (top <= bottom && left <= right) {
            for (int column = left; column <= right; column++) {
                add(order, top, column, size);
            }
            for (int row = top + 1; row <= bottom; row++) {
                add(order, row, right, size);
            }
            if (top < bottom) {
                for (int column = right - 1; column >= left; column--) {
                    add(order, bottom, column, size);
                }
            }
            if (left < right) {
                for (int row = bottom - 1; row > top; row--) {
                    add(order, row, left, size);
                }
            }
            top++;
            bottom--;
            left++;
            right--;
        }
        return order;
    }

    /**
     * Adds a slot to the spiral.
     *
     * <p>No check for a slot already added: the walls close in after every
     * lap and each side starts one past the last one's corner, so a slot is
     * reached once by construction. A {@code contains} guard here would be a
     * linear scan inside a loop that can never find anything.
     */
    private static void add(List<Integer> order, int row, int column, int size) {
        int slot = row * COLUMNS + column;
        if (slot < size) {
            order.add(slot);
        }
    }

    private static List<Integer> allSlots(int size) {
        List<Integer> slots = new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++) {
            slots.add(slot);
        }
        return slots;
    }

    /**
     * Every slot, in an arbitrary order.
     *
     * <p>Seeded by size rather than by clock, so the same menu animates the
     * same way for everyone who opens it and the result stays cacheable.
     */
    private static List<Integer> shuffled(int size) {
        List<Integer> slots = allSlots(size);
        java.util.Collections.shuffle(slots, new java.util.Random(size));
        return slots;
    }

    private static List<Integer> reversed(List<Integer> slots) {
        List<Integer> copy = new ArrayList<>(slots);
        java.util.Collections.reverse(copy);
        return copy;
    }

    /** Cuts an order into frames of a few slots each. */
    private static List<List<Integer>> inGroups(List<Integer> order, int perFrame) {
        List<List<Integer>> frames = new ArrayList<>();
        for (int index = 0; index < order.size(); index += perFrame) {
            frames.add(List.copyOf(order.subList(index, Math.min(index + perFrame, order.size()))));
        }
        return List.copyOf(frames);
    }

    /** Rows appear one at a time, from the top and the bottom alternately. */
    private static List<List<Integer>> rowsAlternate(int size) {
        int rows = Math.max(1, size / COLUMNS);
        List<List<Integer>> frames = new ArrayList<>(rows);
        int top = 0;
        int bottom = rows - 1;
        while (top <= bottom) {
            frames.add(rowOf(top, size));
            if (top != bottom) {
                frames.add(rowOf(bottom, size));
            }
            top++;
            bottom--;
        }
        return frames;
    }

    private static List<Integer> rowOf(int row, int size) {
        List<Integer> slots = new ArrayList<>(COLUMNS);
        for (int column = 0; column < COLUMNS; column++) {
            int slot = row * COLUMNS + column;
            if (slot < size) {
                slots.add(slot);
            }
        }
        return slots;
    }
}
