package net.exylia.lib.panel.internal;

import org.jetbrains.annotations.ApiStatus;
import net.exylia.lib.item.Item;
import net.exylia.lib.ui.ClickBindings;
import net.exylia.lib.ui.UiDefinition;
import net.exylia.lib.ui.UiFillers;
import net.exylia.lib.ui.UiItem;
import net.exylia.lib.ui.UiRefresh;
import net.exylia.lib.ui.UiSection;
import net.exylia.lib.ui.UiSounds;

import java.util.List;
import java.util.Map;

/**
 * Where a panel's shape comes from.
 *
 * <p>Slots and sizes are never written into engine control flow: an owner
 * retitles, recolours and moves a panel's buttons the same way they retheme any
 * other menu. What lives here is only the answer of last resort — a layout
 * built in Java that keeps a panel operable when the file is missing,
 * unreadable, or malformed.
 *
 * <p>The built-in default is not a fallback in the apologetic sense. It is a
 * complete, usable panel: an owner who deletes every YAML file still gets a
 * working settings screen, which is what makes theming optional rather than
 * load-bearing.
 */
@ApiStatus.Internal
public final class Layouts {

    /** Six rows: five for controls, the last for chrome. */
    static final int SIZE = 54;

    /** Where the working area ends and the button row begins. */
    static final int CONTROL_SLOTS = 45;

    static final int SAVE_SLOT = 49;
    static final int CANCEL_SLOT = 45;
    static final int UNDO_SLOT = 47;
    static final int PREVIOUS_SLOT = 48;
    static final int NEXT_SLOT = 50;

    /**
     * How many rows a list panel shows at once.
     *
     * <p>Three rows of nine rather than the settings panel's five, because a
     * list is read down rather than scanned: a page a viewer cannot take in is a
     * page they scroll past. The rest of the window is deliberately quiet.
     */
    static final int LIST_ROW_SLOTS = 27;

    /** The list's own buttons, which sit beside the panel's on the last row. */
    static final int SEARCH_SLOT = 46;
    static final int ADD_SLOT = 51;
    static final int PASTE_SLOT = 52;

    /**
     * The panel every panel falls back to.
     *
     * <p>A {@link UiDefinition} is immutable and holds raw strings rather than
     * parsed components (see {@code docs/reload.md}), so one instance is shared
     * by every viewer on the server and survives a palette reload without
     * holding a stale colour: what it carries is {@code {primary}}, and what
     * that means is decided when it is drawn.
     */
    public static final UiDefinition BUILT_IN = builtIn();

    private Layouts() {
        throw new AssertionError("No instances.");
    }

    private static UiDefinition builtIn() {
        // A section rather than fixed slots: a record with more components than
        // fit on one screen has to paginate, and the section is what knows how.
        // No templates: every row brings its own item, because no single
        // template could describe a toggle and a nested sub-panel at once.
        UiSection controls = new UiSection(UiSection.MAIN, slots(0, CONTROL_SLOTS),
                Map.of(), null, null, null);

        return new UiDefinition(
                "exylialib:panel",
                "{primary}&lSETTINGS",
                UiDefinition.UiKind.CHEST,
                SIZE,
                Map.of(
                        CANCEL_SLOT, chrome("BARRIER", "{error}&lCANCEL"),
                        UNDO_SLOT, chrome("CLOCK", "{warning}&lUNDO"),
                        PREVIOUS_SLOT, chrome("ARROW", "{secondary}&lPREVIOUS"),
                        SAVE_SLOT, chrome("LIME_DYE", "{success}&lSAVE"),
                        NEXT_SLOT, chrome("ARROW", "{secondary}&lNEXT")),
                UiFillers.NONE,
                Map.of(UiSection.MAIN, controls),
                List.of(),
                UiSounds.DEFAULTS,
                // Nothing here changes on a timer: a control changes when the
                // player edits it, and that redraw is asked for by name.
                UiRefresh.NEVER,
                null,
                List.of(),
                List.of(),
                null);
    }

    /**
     * A button.
     *
     * <p>No bindings: what a panel's buttons do is decided by the engine from
     * the slot the layout put them in, not by an action line a file could
     * rewrite. A layout chooses where save is, never what save means.
     */
    private static UiItem chrome(String material, String name) {
        return new UiItem(Item.of(material).name(name).build(),
                ClickBindings.none(), null, List.of());
    }

    private static List<Integer> slots(int from, int toExclusive) {
        return java.util.stream.IntStream.range(from, toExclusive).boxed().toList();
    }
}
