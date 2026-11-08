package net.exylia.lib.input.internal;

import net.exylia.lib.input.InputOutcome;
import net.exylia.lib.input.InputParser;
import net.exylia.lib.input.SearchInput;
import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.ScreenSession;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.UnaryOperator;

/**
 * A {@link SearchInput} for a player whose menus are screens: results as a grid on a screen of their own, narrowed with
 * the keys drawn above them, so nothing steps aside and no chest window opens. Only ever touched on the viewer's thread.
 *
 * @param <T> what the request offers
 * @since 1.525.0
 */
final class ScreenSearch<T> {

    private static final int COLUMNS = 9;
    private static final int PAGE = COLUMNS * 4;
    private static final String PAGE_KEY = "page";
    private static final String QUERY_KEY = "query";

    private final InputSession session;
    private final SearchInput<T> request;
    private Plugin owner;
    private final Map<T, String> normalized;

    /** What {@link #matches} answers; a page too when results are fetched a page at a time. */
    private @Nullable String answered;
    private List<T> matches = List.of();
    private int total;
    private boolean loading;
    private boolean unavailable;

    ScreenSearch(InputSession session, SearchInput<T> request) {
        this.session = session;
        this.request = request;
        this.normalized = request.normalizedSearchStrings();
    }

    /** The plugin whose thread rules fetched results come back under. */
    ScreenSearch<T> owner(Plugin owner) {
        this.owner = owner;
        return this;
    }

    Node draw(ScreenSession screen) {
        String query = screen.state(QUERY_KEY, "");
        int page = screen.state(PAGE_KEY, opening());
        refresh(screen, query, page);
        int pages = Math.max(1, (total + PAGE - 1) / PAGE);
        page = Math.min(page, pages - 1);

        Node head = Node.row(Node.text(Text.of(request.prompt()).build()),
                Node.box().grow(1),
                Node.text(Text.of(query.isEmpty() ? "{muted}" + Phrases.tr("Type to narrow it down")
                        : "{letters_black}⌕ {letters}%q%").with("%q%", query).build()).scale(0.8))
                .gap(6).align(Node.Align.CENTER);
        Node grid = Node.column().gap(3).grow(1);
        List<T> shown = request.source() != null ? matches
                : matches.subList(Math.min(page * PAGE, matches.size()), Math.min((page + 1) * PAGE, matches.size()));
        Node row = null;
        for (int i = 0; i < shown.size(); i++) {
            if (i % COLUMNS == 0) grid.add(row = Node.row().gap(3));
            row.add(card(shown.get(i)));
        }
        if (shown.isEmpty()) {
            String words = loading ? Phrases.tr("Loading") : unavailable ? Phrases.tr("Could not load these right now")
                    : Phrases.tr("No matches");
            grid.add(Node.column(Node.text(Text.of("{muted}" + words).build())).grow(1).align(Node.Align.CENTER)
                    .justify(Node.Justify.CENTER));
        }
        Node cancel = Node.text(Text.of("{letters}" + Phrases.tr("Cancel")).build()).padding(3, 10).background("{letters_black}20")
                .hover("{primary}50").onClick(event -> session.end(InputOutcome.CANCELLED));
        Node foot = Node.row(cancel, Node.box().grow(1), ScreenTransport.pager(page, pages),
                Node.box().grow(1), Node.text(Text.of("{letters_black}" + total + " " + Phrases.tr("found")).build()).scale(0.7))
                .gap(6).align(Node.Align.CENTER);
        Node panel = Node.column(head, keyboard(), grid, foot).size(400, 225).padding(8, 8).gap(5)
                .background("#17131f").outline("{primary}60", 1);
        return Node.column(panel).align(Node.Align.CENTER).justify(Node.Justify.CENTER);
    }

    /** An edit opens on the page holding the value it edits. */
    private int opening() {
        T current = request.defaultValue();
        int at = current == null || request.source() != null ? -1 : request.choices().indexOf(current);
        return at < 0 ? 0 : at / PAGE;
    }

    private void refresh(ScreenSession screen, String query, int page) {
        if (request.source() == null) {
            if (query.equals(answered)) return;
            answered = query;
            matches = SearchView.filter(request, normalized, query);
            total = matches.size();
            return;
        }
        String asked = query + '\n' + page;
        if (asked.equals(answered)) return;
        answered = asked;
        loading = true;
        unavailable = false;
        CompletionStage<SearchInput.Page<T>> fetched;
        try {
            fetched = request.source().fetch(query, page * PAGE, PAGE);
        } catch (RuntimeException failure) {
            fetched = CompletableFuture.failedFuture(failure);
        }
        Player viewer = screen.viewer();
        fetched.whenComplete((result, error) -> Tasks.of(owner).runAtEntity(viewer, () -> {
            if (!asked.equals(answered) || !screen.open()) return;
            loading = false;
            unavailable = error != null || result == null;
            matches = unavailable ? List.of() : List.copyOf(result.items());
            total = unavailable ? 0 : Math.max(result.total(), result.items().size());
            screen.redraw();
        }));
    }

    private Node keyboard() {
        Node keys = Node.row().gap(1).align(Node.Align.CENTER);
        for (char letter : "abcdefghijklmnopqrstuvwxyz_".toCharArray()) {
            keys.add(key(String.valueOf(letter), 12, typed -> typed + letter));
        }
        keys.add(key(Phrases.tr("space"), 24, typed -> typed + ' '));
        keys.add(key("←", 14, typed -> typed.isEmpty() ? typed : typed.substring(0, typed.length() - 1)));
        return keys;
    }

    private Node key(String label, double width, UnaryOperator<String> edit) {
        return Node.column(Node.text(Text.of("{letters}%k%").with("%k%", label).build()).scale(0.7))
                .size(width, 13).align(Node.Align.CENTER).justify(Node.Justify.CENTER).background("#201a2b").hover("{primary}50")
                .onClick(event -> {
                    String typed = edit.apply(event.session().state(QUERY_KEY, ""));
                    event.session().set(PAGE_KEY, 0);
                    event.session().set(QUERY_KEY, typed.isEmpty() ? null : typed);
                });
    }

    private Node card(T value) {
        String label;
        ItemStack item;
        try {
            label = request.labelOf(value);
            item = request.itemOf(value);
            if (item == null) item = new ItemStack(request.iconOf(value));
        } catch (RuntimeException brokenElement) {
            // One unreadable element must not blank the page.
            label = String.valueOf(value);
            item = new ItemStack(Material.PAPER);
        }
        Node card = Node.column(Node.item(item).size(16, 16),
                        Node.text(Text.of("{letters}%l%").with("%l%", label.replace('_', ' ')).build()).scale(0.45)
                                .wrap(70).textAlign(Node.Align.CENTER))
                .size(40, 36).padding(1).gap(1).align(Node.Align.CENTER).background("#201a2b").hover("#2e2541")
                .hoverOutline("{primary}").tooltip(label.replace('_', ' ')).onClick(event -> choose(event.session(), value));
        if (value.equals(request.defaultValue())) card.outline("{success}", 1);
        return card;
    }

    private void choose(ScreenSession screen, T value) {
        InputParser.Parsed<T> parsed;
        try {
            // A fetched result was never indexed by key, so it is accepted as the value it already is.
            parsed = request.source() != null ? request.parseValue(value) : request.parseRaw(request.keyOf(value));
        } catch (RuntimeException failure) {
            screen.toast("error", Phrases.tr("{error}That option could not be read."));
            return;
        }
        if (parsed.ok() && parsed.value() != null) {
            session.complete(parsed.value());
        } else {
            screen.toast("error", parsed.error() == null ? Phrases.tr("That option is not accepted.") : parsed.error());
        }
    }
}
