package net.exylia.lib.input;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * A request for a position in a menu, answered by pointing at the grid rather
 * than by typing a number.
 *
 * <p>Asking "type the position (0-44)" makes somebody count slots on a screen
 * they cannot see while the question is open, and then discover that the number
 * they counted was already taken. This draws the layout instead: one button per
 * position, laid out in rows of {@link #columns(int)}, free ones in the success
 * colour and taken ones in the error colour. A taken position is refused with
 * the same message wherever the answer comes from, so the grid and a typed
 * answer never disagree.
 *
 * <pre>{@code
 * inputs.slot(admin, "Choose a position", 45)
 *       .taken(usedPositions)
 *       .describe(names::get)
 *       .auto()
 *       .open(position -> save(product.withPosition(position)));
 * }</pre>
 *
 * @since 1.154.0
 */
public final class SlotInput extends InputRequest<Integer, SlotInput> {

    /** The width of a chest row, and so the default width of the grid. */
    public static final int ROW = 9;

    /** Answer meaning "the next free position", offered by {@link #auto()}. */
    public static final int AUTO = -1;

    private final int slots;
    private int columns = ROW;
    private Set<Integer> taken = Set.of();
    private IntFunction<String> describe = slot -> null;
    private boolean auto;
    private String takenMessage = "That position is already taken.";
    private String rangeMessage = "Choose a position from the grid.";

    SlotInput(String pluginName, Player player, String prompt, int slots) {
        super(pluginName, player, prompt, SlotInput::number);
        if (slots < 1) {
            throw new InputException("slots must be at least one");
        }
        this.slots = slots;
    }

    /** Sets how many positions one row of the grid holds. */
    public @NotNull SlotInput columns(int columns) {
        if (columns < 1) {
            throw new InputException("columns must be at least one");
        }
        this.columns = columns;
        return this;
    }

    /** Marks the positions something already occupies, which cannot be answered. */
    public @NotNull SlotInput taken(@NotNull Collection<Integer> taken) {
        Inputs.require(taken, "taken");
        Set<Integer> copy = new HashSet<>(taken.size());
        for (Integer slot : taken) {
            if (slot != null && slot >= 0 && slot < slots) {
                copy.add(slot);
            }
        }
        this.taken = Set.copyOf(copy);
        return this;
    }

    /** Names what occupies a position, shown on that button. */
    public @NotNull SlotInput describe(@NotNull IntFunction<String> describe) {
        this.describe = Inputs.require(describe, "describe");
        return this;
    }

    /** Offers {@link #AUTO} as an answer: let the caller place it at the next free position. */
    public @NotNull SlotInput auto() {
        this.auto = true;
        return this;
    }

    /** Replaces the refusal shown when a taken position is answered. */
    public @NotNull SlotInput takenMessage(@NotNull String message) {
        this.takenMessage = Inputs.requireText(message, "taken message");
        return this;
    }

    /** Replaces the refusal shown when a position outside the grid is answered. */
    public @NotNull SlotInput rangeMessage(@NotNull String message) {
        this.rangeMessage = Inputs.requireText(message, "range message");
        return this;
    }

    /**
     * Refuses a position outside the grid or one already taken.
     *
     * <p>Here rather than as {@code validate} rules so the messages a caller
     * sets stay replaceable after the request is built, and so a taken position
     * is refused identically whether it arrived from a button or from chat.
     */
    @Override
    public @NotNull Validation validate(@NotNull Integer slot) {
        if (slot == null) {
            return Validation.error(rangeMessage);
        }
        if (slot == AUTO) {
            return auto ? super.validate(slot) : Validation.error(rangeMessage);
        }
        if (slot < 0 || slot >= slots) {
            return Validation.error(rangeMessage);
        }
        if (taken.contains(slot)) {
            return Validation.error(takenMessage);
        }
        return super.validate(slot);
    }

    /** How many positions the grid draws. */
    @ApiStatus.Internal
    public int slots() {
        return slots;
    }

    /** How many positions one row of the grid holds. */
    @ApiStatus.Internal
    public int columns() {
        return columns;
    }

    /** Whether something already occupies a position. */
    @ApiStatus.Internal
    public boolean isTaken(int slot) {
        return taken.contains(slot);
    }

    /** What occupies a position, or {@code null} when nothing does or nothing named it. */
    @ApiStatus.Internal
    public @Nullable String occupant(int slot) {
        if (!taken.contains(slot)) {
            return null;
        }
        String name = describe.apply(slot);
        return name == null || name.isBlank() ? null : name;
    }

    /** Whether the next-free answer is offered. */
    @ApiStatus.Internal
    public boolean allowsAuto() {
        return auto;
    }

    private static InputParser.Parsed<Integer> number(String raw) {
        InputParser.Parsed<Long> parsed = InputParser.integer().parse(raw);
        if (!parsed.ok()) {
            return InputParser.Parsed.rejected(parsed.error() == null
                    ? "Choose a position from the grid." : parsed.error());
        }
        long value = parsed.value();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            return InputParser.Parsed.rejected("Choose a position from the grid.");
        }
        return InputParser.Parsed.of((int) value);
    }
}
