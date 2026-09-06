package net.exylia.lib.api.betcore;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One square of a board, as the game wants it drawn.
 *
 * <p>A template name and a bag of values, which is exactly the pair a menu row
 * takes: {@code UiEntry.of(cell).template(name).with(key, value)}. That is the
 * whole reason this type exists — it lets a game say <em>what</em> a square is
 * without knowing a single thing about materials, item stacks or inventories.
 *
 * <p>The template name is matched against the board menu's {@code *_template}
 * blocks: {@code "x_marked"} draws with {@code x_marked_template}. A name the
 * file does not declare falls back to the first template it does, which is the
 * library's own behaviour and the right one here — a board with an unstyled
 * square is still playable, an empty slot is not.
 *
 * <p>Values are inserted <b>literally</b>. A square's values are game data, not
 * text the server owner wrote, so a player called {@code <rainbow>} does not
 * recolour the board.
 *
 * @param template the template name, without the {@code _template} suffix
 * @param values   what the template's {@code %placeholders%} stand for
 *
 * @since 1.0.0
 */
public record CellView(@NotNull String template, @NotNull @Unmodifiable Map<String, String> values) {

    /** A square with no values of its own. */
    @NotNull
    public static CellView of(@NotNull String template) {
        return new CellView(template, Map.of());
    }

    /**
     * A square and its values, written in pairs.
     *
     * @param template the template name
     * @param keyValue alternating key and value; an odd count is a mistake
     * @return the view
     * @throws IllegalArgumentException when a key has no value
     */
    @NotNull
    public static CellView of(@NotNull String template, @NotNull String... keyValue) {
        if (keyValue.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "CellView values come in pairs, got " + keyValue.length);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < keyValue.length; i += 2) {
            values.put(keyValue[i], keyValue[i + 1]);
        }
        return new CellView(template, Map.copyOf(values));
    }
}
