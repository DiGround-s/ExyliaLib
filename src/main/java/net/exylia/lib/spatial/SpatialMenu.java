package net.exylia.lib.spatial;

import net.exylia.lib.text.Values;
import org.bukkit.entity.Player;
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
    @Nullable String permission;
    @Nullable Function<Player, String> guard;
    @Nullable Function<SpatialContext, Map<String, ?>> saved;
    @Nullable Function<SpatialContext, String> save;
    @Nullable Consumer<SpatialContext> discard;
    @Nullable Consumer<SpatialContext> onRevert;

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

    /**
     * Placeholders for every word the menu draws, on top of the menu's state; read on every redraw:
     * TEXT lines, titles, hints, button and footer labels, and the theme's lines (locked, owned,
     * equipped, page, empty, arrows, the leave item's name and lore). Writing {@code %key%} in the
     * layout and giving {@code key} here is how a plugin translates them per viewer.
     */
    public @NotNull SpatialMenu values(@Nullable Function<SpatialContext, Values> values) {
        this.values = values;
        return this;
    }

    /**
     * The permission a player needs to open it, from a command or a placed spot alike; {@code null} for
     * none. Without it they hear the theme's {@code error-sound} and nothing opens. Edit mode is not
     * checked: it is the plugin's admin command that decides who edits.
     *
     * @since 1.408.0
     */
    public @NotNull SpatialMenu permission(@Nullable String permission) {
        this.permission = permission == null || permission.isBlank() ? null : permission;
        return this;
    }

    /**
     * Asked on the player's thread before it opens, after the permission: {@code null} lets them in;
     * anything else refuses, with the theme's {@code error-sound}, and is sent to them as a message
     * (colours and palette tokens allowed) unless it is blank. Their own reason, from the plugin's lang
     * files: {@code player -> busy(player) ? lines.busy() : null}.
     *
     * @since 1.408.0
     */
    public @NotNull SpatialMenu canOpen(@Nullable Function<Player, String> guard) {
        this.guard = guard;
        return this;
    }

    /**
     * What is saved now, by key: what a draft is compared with. Read on the viewer's thread whenever the
     * menu asks (every redraw), so read memory. A key that names a source is that source's selection:
     * {@code Map.of("effects", worn)} makes the chosen effect the draft, and SAVE is lit while it is not
     * the worn one. Other keys hold whatever {@link SpatialContext#draft(String, Object)} is given;
     * values are compared with {@code equals}. A {@code null} value is allowed.
     *
     * <pre>{@code
     * menu.saved(ctx -> Collections.singletonMap("effects", worn(ctx.viewer())))
     *     .onSave(ctx -> equip(ctx.viewer(), ctx.selected("effects")))   // null: saved; a line: refused
     *     .onDiscard(ctx -> lines.discarded().send(ctx.viewer()));
     * }</pre>
     *
     * @since 1.414.0
     */
    public @NotNull SpatialMenu saved(@Nullable Function<SpatialContext, Map<String, ?>> saved) {
        this.saved = saved;
        return this;
    }

    /**
     * What SAVE does (a BUTTON or footer with the action {@code save}, or {@link SpatialContext#save}):
     * commit the drafts through the plugin's own services and return {@code null}, or return why not
     * (colours and palette tokens allowed), which the player is told with the theme's error sound; a
     * blank line refuses silently. Drafts the saved state now equals are dropped; the menu redraws.
     *
     * @since 1.414.0
     */
    public @NotNull SpatialMenu onSave(@Nullable Function<SpatialContext, String> save) {
        this.save = save;
        return this;
    }

    /**
     * Runs when the menu closes with unsaved drafts, which are dropped: where to say so.
     *
     * @since 1.414.0
     */
    public @NotNull SpatialMenu onDiscard(@Nullable Consumer<SpatialContext> discard) {
        this.discard = discard;
        return this;
    }

    /**
     * Runs on REVERT, after every draft is dropped and before the redraw: where a menu whose markers
     * follow its drafts rather than a saved key (a basket of parts, the piece being edited) moves them
     * back to what is saved. Sources named as saved keys are re-selected by the library first.
     *
     * @since 1.428.0
     */
    public @NotNull SpatialMenu onRevert(@Nullable Consumer<SpatialContext> onRevert) {
        this.onRevert = onRevert;
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
