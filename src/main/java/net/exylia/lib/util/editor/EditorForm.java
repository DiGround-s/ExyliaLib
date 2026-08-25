package net.exylia.lib.util.editor;

import net.exylia.lib.input.FormField;
import net.exylia.lib.input.FormInput;
import net.exylia.lib.input.FormKey;
import net.exylia.lib.input.FormValues;
import net.exylia.lib.input.Inputs;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One window that asks for every field of an element at once.
 *
 * <p>This is what {@link EditorDescriptor#edit} normally returns. Where
 * ExyliaCommons drew a menu with an icon per field and asked one question per
 * click, this is a single dialog with every value already filled in — one trip
 * instead of seven, and nothing is retyped from memory.
 *
 * <pre>{@code
 * private static final FormKey<String> NAME = FormKey.text("name");
 * private static final FormKey<Long> WEIGHT = FormKey.integer("weight");
 *
 * return EditorForm.of(plugin, viewer, "{primary}&lEDIT ENTRY")
 *         .text(NAME, "Display name", entry.name(), 3)
 *         .integer(WEIGHT, "Weight", entry.weight())
 *         .ask(values -> entry.toBuilder()
 *                 .name(values.getText(NAME))
 *                 .weight(values.getLong(WEIGHT))
 *                 .build());
 * }</pre>
 *
 * <h2>Prefilled, always</h2>
 * Every field here takes the value it is editing. That is the difference between
 * correcting a display name and retyping thirty characters of colour tokens from
 * memory, and it is why the {@code current} argument is not optional.
 *
 * <h2>Where there is no dialog</h2>
 * A client too old for dialogs, or a Bedrock player, is asked the same fields
 * through whichever transport can — a Floodgate form, or one chat line per
 * field. The input module decides; nothing here knows which one answered.
 *
 * @since 1.56.0
 */
public final class EditorForm {

    /** How tall a text field is when the caller does not say. */
    private static final int ONE_LINE = 1;

    private final FormInput form;
    private final List<Consumer<FormInput>> pending = new ArrayList<>();
    private FormField<?> last;

    private EditorForm(FormInput form) {
        this.form = form;
    }

    /**
     * A form headed by a title.
     *
     * @param plugin who is asking
     * @param viewer who is being asked
     * @param title  the window title, in Exylia text notation
     * @return the form
     */
    public static @NotNull EditorForm of(@NotNull Plugin plugin, @NotNull Player viewer,
                                         @NotNull String title) {
        return new EditorForm(Inputs.of(plugin).form(viewer, title));
    }

    /**
     * A single-line text field, prefilled.
     *
     * @param key     where the answer is read from
     * @param label   what the field is called
     * @param current the value being edited; {@code null} for a new element
     * @return this form
     */
    public @NotNull EditorForm text(@NotNull FormKey<String> key, @NotNull String label,
                                    String current) {
        return text(key, label, current, ONE_LINE);
    }

    /**
     * A text field several lines tall, prefilled.
     *
     * <p>For values that are long or carry markup: a display name full of colour
     * tokens, a lore line, a command with placeholders. A one-line box shows
     * about twenty characters, which is editing blind.
     *
     * @param key     where the answer is read from
     * @param label   what the field is called
     * @param current the value being edited
     * @param lines   how many lines tall
     * @return this form
     */
    public @NotNull EditorForm text(@NotNull FormKey<String> key, @NotNull String label,
                                    String current, int lines) {
        FormField<String> field = FormField.text(key, label)
                .defaultValue(current == null ? "" : current)
                .lines(lines);
        if (current == null || current.isEmpty()) {
            field.optional();
        }
        return add(key, field);
    }

    /**
     * A whole-number field, prefilled.
     *
     * @param key     where the answer is read from
     * @param label   what the field is called
     * @param current the value being edited
     * @return this form
     */
    public @NotNull EditorForm integer(@NotNull FormKey<Long> key, @NotNull String label,
                                       long current) {
        return add(key, FormField.integer(key, label).defaultValue(current));
    }

    /**
     * A decimal field, prefilled.
     *
     * @param key     where the answer is read from
     * @param label   what the field is called
     * @param current the value being edited
     * @return this form
     */
    public @NotNull EditorForm decimal(@NotNull FormKey<java.math.BigDecimal> key,
                                       @NotNull String label,
                                       @NotNull java.math.BigDecimal current) {
        return add(key, FormField.decimal(key, label).defaultValue(current));
    }

    /**
     * A yes-or-no field, prefilled.
     *
     * @param key     where the answer is read from
     * @param label   what the field is called
     * @param current the value being edited
     * @return this form
     */
    public @NotNull EditorForm flag(@NotNull FormKey<Boolean> key, @NotNull String label,
                                    boolean current) {
        return add(key, FormField.flag(key, label).defaultValue(current));
    }

    /**
     * Adds a field the shortcuts above do not cover.
     *
     * @param key   where the answer is read from
     * @param field the field, already carrying its own default
     * @param <T>   the field type
     * @return this form
     */
    public @NotNull <T> EditorForm field(@NotNull FormKey<T> key, @NotNull FormField<T> field) {
        return add(key, field);
    }

    /**
     * Says what a valid answer to the field just added looks like.
     *
     * <p>A label names the field; a hint answers the question the label leaves
     * open. {@code Command the console runs} does not say whether the player is
     * {@code %player%} or {@code %player_name%}, and a wrong guess is only found
     * later, in a reward that silently does nothing.
     *
     * <pre>{@code
     * .text(COMMAND, "Command the console runs", entry.command(), 3)
     * .hint("%player_name% is the player. No leading slash.")
     * }</pre>
     *
     * <p>Where the client draws it is the transport's business: a Bedrock form
     * has a real placeholder, a dialog gets a muted line under the label, and
     * chat sends it as its own line.
     *
     * @param hint the note, or {@code null} to remove one
     * @return this form
     * @throws IllegalStateException if no field has been added yet
     * @since 1.60.0
     */
    public @NotNull EditorForm hint(String hint) {
        if (last == null) {
            throw new IllegalStateException("hint() describes the field before it; add a field first");
        }
        last.hint(hint);
        return this;
    }

    private <T> EditorForm add(FormKey<T> key, FormField<T> field) {
        last = field;
        pending.add(input -> input.field(key, field));
        return this;
    }

    /**
     * Shows the form and turns the answers into an element.
     *
     * <p>Anything other than a completed submission — cancelled, timed out,
     * replaced by another question — answers with nothing, and the editor simply
     * redraws its list. The distinction between those endings belongs to a
     * caller that asked directly, not to an editor row.
     *
     * @param build turns the submitted values into the edited element
     * @param <T>   the element type
     * @return the element, or nothing
     */
    public @NotNull <T> CompletionStage<Optional<T>> ask(
            @NotNull Function<FormValues, T> build) {
        for (Consumer<FormInput> field : pending) {
            field.accept(form);
        }
        return form.open().thenApply(result ->
                result.completed() ? Optional.ofNullable(build.apply(result.value())) : Optional.empty());
    }
}
