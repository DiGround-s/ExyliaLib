package net.exylia.lib.util.head;

import net.exylia.lib.input.PluginInputs;
import net.exylia.lib.input.SearchInput;
import net.exylia.lib.util.head.internal.HeadDb;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The head catalogue, searchable from a menu.
 *
 * <pre>{@code
 * Heads.browse(inputs, player, "{warning}Browse a head")
 *      .open(head -> arenas.save(arena.withIcon(head.icon())));
 * }</pre>
 *
 * <p>Separate from {@link net.exylia.lib.skull}, which answers "what does this
 * player look like". This one answers "which of the eighty thousand decorative
 * heads did you want", and the two meet only at the end: what a player picks
 * here is a {@code urlhead-} value the skull module draws.
 *
 * <h2>What it costs</h2>
 * Nothing until somebody searches, and one page at a time after that. The
 * catalogue is never downloaded, never indexed and never held: a search is one
 * request for the forty-five results on screen, answered by the catalogue's own
 * index rather than by a scan of a copy of it. A few recently seen pages are
 * remembered so paging back is instant, and that cache is the entire memory
 * cost of the module.
 *
 * <p>The other side of that trade: a server with no way out to the internet has
 * no catalogue. The picker says so and every other way of choosing an icon still
 * works, which is why this is an extra way and not a replacement.
 *
 * @since 1.82.0
 */
public final class Heads {

    private Heads() {
        throw new AssertionError("No instances.");
    }

    /**
     * Starts a search over the whole catalogue.
     *
     * <p>Returned rather than opened, so the caller keeps the request builder:
     * a timeout, a validation, a page size are all still theirs to set.
     *
     * @param inputs the asking plugin's inputs
     * @param player who is choosing
     * @param prompt the window title, in Exylia text notation
     * @return the request, ready to open
     */
    public static @NotNull SearchInput<Head> browse(@NotNull PluginInputs inputs,
                                                    @NotNull Player player,
                                                    @NotNull String prompt) {
        return inputs.<Head>search(player, prompt)
                .source(catalog())
                .label(Head::name)
                .iconItem(Head::item);
    }

    /**
     * The catalogue as a page source, for a search built by hand.
     *
     * <p>The query is matched by the catalogue against names, ids and tag names,
     * so "cat" finds the cats and "flag" finds the flags without anything
     * local knowing what a tag is.
     *
     * @return the source
     */
    public static SearchInput.@NotNull Pages<Head> catalog() {
        return HeadDb::fetch;
    }

    /** Forgets the remembered pages, so the next search asks again. */
    public static void invalidate() {
        HeadDb.invalidate();
    }
}
