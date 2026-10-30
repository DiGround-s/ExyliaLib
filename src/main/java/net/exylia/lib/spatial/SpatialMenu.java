package net.exylia.lib.spatial;

import net.exylia.lib.text.Values;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * What a plugin gives a spatial menu: its lists, its actions, what stands in the middle, and what
 * choosing does. Where things stand and how they look is the layout file's.
 *
 * <pre>{@code
 * menus.register("trims", menu -> menu
 *         .source("pieces", ctx -> pieces(ctx.viewer()))
 *         .source("patterns", ctx -> patterns(ctx.viewer(), ctx.selected("pieces")))
 *         .preview(ctx -> SpatialPreview.npc(ctx.viewer(), trying(ctx)))
 *         .onSelect("patterns", (ctx, entry) -> ctx.set("pattern", entry.name()))
 *         .action("apply-set", ctx -> apply(ctx)));
 * }</pre>
 *
 * <p>Sources run on every redraw on the viewer's thread: read memory, never a database. Every
 * callback runs on the viewer's thread.
 *
 * @since 1.406.0
 */
public final class SpatialMenu {

    private final String id;
    final Map<String, Function<SpatialContext, List<SpatialEntry>>> sources = new LinkedHashMap<>();
    final Map<String, Consumer<SpatialContext>> actions = new LinkedHashMap<>();
    final Map<String, BiConsumer<SpatialContext, SpatialEntry>> selects = new LinkedHashMap<>();
    @Nullable Function<SpatialContext, SpatialPreview> preview;
    @Nullable Function<SpatialContext, Values> values;
    @Nullable Consumer<SpatialContext> onOpen;
    @Nullable Consumer<SpatialContext> onClose;

    SpatialMenu(String id) {
        this.id = id;
    }

    public @NotNull String id() {
        return id;
    }

    /** A list a LIST, GRID or the carousel draws, by the name the layout gives as {@code source}. */
    public @NotNull SpatialMenu source(@NotNull String name, @NotNull Function<SpatialContext, List<SpatialEntry>> source) {
        sources.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(source, "source"));
        return this;
    }

    /** What stands in the middle; read again on every redraw. */
    public @NotNull SpatialMenu preview(@Nullable Function<SpatialContext, SpatialPreview> preview) {
        this.preview = preview;
        return this;
    }

    /** What a BUTTON or a panel's footer with this {@code action} does. */
    public @NotNull SpatialMenu action(@NotNull String id, @NotNull Consumer<SpatialContext> action) {
        actions.put(Objects.requireNonNull(id, "id"), Objects.requireNonNull(action, "action"));
        return this;
    }

    /** What choosing an entry of a source does, after the choice is made; the menu redraws afterwards. */
    public @NotNull SpatialMenu onSelect(@NotNull String source, @NotNull BiConsumer<SpatialContext, SpatialEntry> select) {
        selects.put(Objects.requireNonNull(source, "source"), Objects.requireNonNull(select, "select"));
        return this;
    }

    /** Placeholders for TEXT lines and titles, on top of the menu's state; read on every redraw. */
    public @NotNull SpatialMenu values(@Nullable Function<SpatialContext, Values> values) {
        this.values = values;
        return this;
    }

    /** Runs once the menu is drawn, before the first redraw a player can see. */
    public @NotNull SpatialMenu onOpen(@Nullable Consumer<SpatialContext> onOpen) {
        this.onOpen = onOpen;
        return this;
    }

    /** Runs once when it closes, however it closed. */
    public @NotNull SpatialMenu onClose(@Nullable Consumer<SpatialContext> onClose) {
        this.onClose = onClose;
        return this;
    }
}
