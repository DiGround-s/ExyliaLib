package net.exylia.lib.util.editor;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A button a plugin puts in a list editor.
 *
 * <pre>{@code
 * Loot.editor(this, table.entries())
 *     .button(EditorButton.preset(() -> Loot.parseAll(config.defaultPool())))
 *     .onSave(store::save)
 *     .open(player);
 * }</pre>
 *
 * <p>The way to extend an editor without forking it. ExyliaCommons had this and
 * exactly one plugin used it — Events, to offer a recommended loot table for an
 * event type — which is precisely the case a generic editor cannot guess and a
 * plugin should not have to write a whole screen for.
 *
 * <h2>Where they go</h2>
 * The editor places them; a caller never names a slot. A screen with buttons
 * gives up its bottom row of entries to hold them, so a page shows 36 rows
 * instead of 45 and the buttons sit under them in the order they were added. A
 * screen with none keeps all 45.
 *
 * <p>Slots were the one thing ExyliaCommons made callers write, and it is how a
 * button ends up on top of the save button on a screen somebody later changed.
 *
 * @param <T> what the editor edits
 * @since 1.58.0
 */
public final class EditorButton<T> {

    /** How many buttons fit: one row, and there is nowhere else for a tenth. */
    public static final int LIMIT = 9;

    private final String icon;
    private final String name;
    private final List<String> lore;
    private final boolean glowing;
    private final Consumer<EditorView<T>> onClick;

    private EditorButton(Builder<T> builder) {
        this.icon = builder.icon;
        this.name = builder.name;
        this.lore = List.copyOf(builder.lore);
        this.glowing = builder.glowing;
        this.onClick = builder.onClick;
    }

    /** What it is drawn as: a material name, a head string or a {@code bytes:} snapshot. */
    public @NotNull String icon() {
        return icon;
    }

    /** Its name, in Exylia text notation. */
    public @NotNull String name() {
        return name;
    }

    /** Its lore lines, in Exylia text notation. */
    public @NotNull List<String> lore() {
        return lore;
    }

    /** Whether it is drawn with the enchantment glint. */
    public boolean isGlowing() {
        return glowing;
    }

    /**
     * Runs the button.
     *
     * @param view the open editor
     */
    public void click(@NotNull EditorView<T> view) {
        onClick.accept(view);
    }

    /**
     * A button drawn as the given icon.
     *
     * @param icon a material name, a head string or a {@code bytes:} snapshot
     * @param <T>  what the editor edits
     * @return the builder
     */
    public static <T> @NotNull Builder<T> of(@NotNull String icon) {
        return new Builder<>(icon);
    }

    /**
     * The button every editor eventually grows: load a recommended list.
     *
     * <p>Named, worded and drawn the way ExyliaCommons drew it, because that is
     * the one an admin has already learned. The preset is asked for when the
     * button is pressed rather than when the editor opens, so a config reloaded
     * in between is the one that answers.
     *
     * <pre>{@code
     * .button(EditorButton.preset(() -> Loot.parseAll(config.defaultPool())))
     * }</pre>
     *
     * @param preset what the list should become
     * @param <T>    what the editor edits
     * @return the button
     */
    public static <T> @NotNull EditorButton<T> preset(@NotNull Supplier<List<T>> preset) {
        Objects.requireNonNull(preset, "preset");
        return EditorButton.<T>of("CHEST_MINECART")
                .name("{highlight}&lLOAD DEFAULTS")
                .lore("{secondary}Information:",
                        " {letters_black}▎ {letters}Replaces everything here with",
                        " {letters_black}▎ {letters}the recommended list.",
                        "",
                        "{warning}➥ Click to load, then save")
                .glowing()
                .onClick(view -> view.replaceAll(preset.get()))
                .build();
    }

    /**
     * Builds an {@link EditorButton}.
     *
     * @param <T> what the editor edits
     */
    public static final class Builder<T> {

        private final String icon;
        private final List<String> lore = new ArrayList<>();
        private String name = "{primary}&lBUTTON";
        private boolean glowing;
        private Consumer<EditorView<T>> onClick = view -> { };

        private Builder(String icon) {
            this.icon = Objects.requireNonNull(icon, "icon");
        }

        /**
         * What it is called.
         *
         * @param name the name, in Exylia text notation
         * @return this builder
         */
        public @NotNull Builder<T> name(@NotNull String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        /**
         * Adds lore lines, in order.
         *
         * @param lines the lines, in Exylia text notation
         * @return this builder
         */
        public @NotNull Builder<T> lore(@NotNull String... lines) {
            this.lore.addAll(List.of(lines));
            return this;
        }

        /**
         * Adds lore lines, in order.
         *
         * @param lines the lines
         * @return this builder
         */
        public @NotNull Builder<T> lore(@NotNull List<String> lines) {
            this.lore.addAll(lines);
            return this;
        }

        /**
         * Draws it with the enchantment glint, for the one button that matters.
         *
         * @return this builder
         */
        public @NotNull Builder<T> glowing() {
            this.glowing = true;
            return this;
        }

        /**
         * What it does.
         *
         * <p>Runs on the viewer's own thread, and the screen redraws after it —
         * so a handler changes the list and stops, rather than reopening
         * anything itself.
         *
         * @param onClick told the open editor
         * @return this builder
         */
        public @NotNull Builder<T> onClick(@NotNull Consumer<EditorView<T>> onClick) {
            this.onClick = Objects.requireNonNull(onClick, "onClick");
            return this;
        }

        /**
         * The finished button.
         *
         * @return the button
         */
        public @NotNull EditorButton<T> build() {
            return new EditorButton<>(this);
        }
    }
}
