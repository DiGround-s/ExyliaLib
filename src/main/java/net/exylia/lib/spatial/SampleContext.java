package net.exylia.lib.spatial;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * A menu that is not open, for {@code viewer}: what the web editor reads a catalogue's offers and words with,
 * so its board samples what that player would see on opening it (the first offer's real name and rarity, the
 * server's own words). Nothing is drawn, chosen or drafted; every redraw and close does nothing.
 */
final class SampleContext implements SpatialContext {

    private final Player viewer;
    private final String menu;
    private final Map<String, Object> state = new HashMap<>();
    private final Map<String, String> selected = new HashMap<>();
    /** The state the board is sampled in: a draft waiting (with the sources it changed), a second click armed. */
    boolean dirty;
    java.util.Set<String> changed = java.util.Set.of();
    boolean confirming;

    private final @Nullable SpatialLayout layout;

    SampleContext(Player viewer, String menu) {
        this(viewer, menu, null);
    }

    SampleContext(Player viewer, String menu, @Nullable SpatialLayout layout) {
        this.viewer = viewer;
        this.menu = menu;
        this.layout = layout;
    }

    /** Prose broken as the open menu breaks it, from the layout. */
    @Override
    public @NotNull java.util.List<String> wrap(@NotNull String panel, @Nullable String text, int lines) {
        return layout == null ? SpatialContext.super.wrap(panel, text, lines) : layout.wrap(panel, text == null ? "" : text, lines);
    }

    @Override
    public @NotNull Player viewer() {
        return viewer;
    }

    @Override
    public @NotNull String menu() {
        return menu;
    }

    @Override
    public @Nullable Object get(@NotNull String key) {
        return state.get(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @NotNull T get(@NotNull String key, @NotNull T fallback) {
        Object value = state.get(key);
        return fallback.getClass().isInstance(value) ? (T) value : fallback;
    }

    @Override
    public void set(@NotNull String key, @Nullable Object value) {
        if (value == null) state.remove(key);
        else state.put(key, value);
    }

    @Override
    public int page(@NotNull String source) {
        return 0;
    }

    @Override
    public void page(@NotNull String source, int page) {
    }

    @Override
    public @Nullable String selected(@NotNull String source) {
        return selected.get(source);
    }

    @Override
    public void select(@NotNull String source, @Nullable String entry) {
        if (entry == null) selected.remove(source);
        else selected.put(source, entry);
    }

    @Override
    public boolean dirty() {
        return dirty;
    }

    @Override
    public @NotNull java.util.Set<String> changed() {
        return changed;
    }

    @Override
    public boolean confirming(@NotNull String key) {
        return confirming && (key.equals(MenuSession.SAVE) || key.startsWith(MenuSession.SAVE + ":"));
    }

    @Override
    public void refresh() {
    }

    @Override
    public void replay() {
    }

    @Override
    public void close() {
    }
}
