package net.exylia.lib.input.internal;

import net.exylia.lib.input.ChoiceInput;

import java.util.ArrayList;
import java.util.List;

/**
 * Reading a choice's options by position.
 *
 * <p>A transport that draws one button per option has to say in the button
 * which option it is, and get back an option from what comes back. Position is
 * the only thing that survives the trip: a choice's own key is a plugin's
 * string — {@code MATERIAL}, an id with a colon in it — and a dialog action is
 * a namespaced resource location, which accepts neither.
 *
 * <p>Its own class rather than three methods on the dialog transport, because
 * that one cannot be loaded without PacketEvents on the classpath and this is
 * the part worth testing.
 */
final class ChoiceOptions {

    private ChoiceOptions() {
        throw new AssertionError("No instances.");
    }

    /** The label of every option, in the order they are offered. */
    static List<String> labels(ChoiceInput<?> choice) {
        List<?> options = choice.choices();
        List<String> labels = new ArrayList<>(options.size());
        for (Object option : options) {
            labels.add(label(choice, option));
        }
        return labels;
    }

    /**
     * The key of the option at a position, as the raw answer for it.
     *
     * @param choice   the request
     * @param position the position, as it came back from the client
     * @return the key, or {@code null} when the position is not one of the
     *         options — a dialog left open across a reload answers with a
     *         position the request no longer has, and that is not an answer
     */
    static String keyAt(ChoiceInput<?> choice, String position) {
        int index;
        try {
            index = Integer.parseInt(position);
        } catch (NumberFormatException notANumber) {
            return null;
        }
        List<?> options = choice.choices();
        if (index < 0 || index >= options.size()) {
            return null;
        }
        return key(choice, options.get(index));
    }

    @SuppressWarnings("unchecked")
    private static <T> String label(ChoiceInput<?> untyped, Object option) {
        return ((ChoiceInput<T>) untyped).labelOf((T) option);
    }

    @SuppressWarnings("unchecked")
    private static <T> String key(ChoiceInput<?> untyped, Object option) {
        return ((ChoiceInput<T>) untyped).keyOf((T) option);
    }
}
