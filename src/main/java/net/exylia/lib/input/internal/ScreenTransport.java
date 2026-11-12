package net.exylia.lib.input.internal;

import net.exylia.lib.input.ChoiceInput;
import net.exylia.lib.input.ConfirmInput;
import net.exylia.lib.input.FlagInput;
import net.exylia.lib.input.InputParser;
import net.exylia.lib.input.InputOutcome;
import net.exylia.lib.input.InputRequest;
import net.exylia.lib.input.SearchInput;
import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.Screen;
import net.exylia.lib.screen.ScreenSession;
import net.exylia.lib.screen.Screens;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.exylia.lib.ui.MenuStyles;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Confirms, yes/no flags and choices for a player whose menus are screens (MenuStyle SCREEN): drawn as a
 * dialog on a screen of their own instead of a small chest window. Cancel, Shift or anything closing the screen
 * cancels the request. A choice of more than twelve answers pages, turned with A and D or the arrows (1.517.0).
 * A search is a grid with its own keys (1.525.0, {@link ScreenSearch}).
 * Numbers and amounts are a keypad (1.607.0, {@link ScreenKeypad}). Anything else (text) is left to the next
 * transport.
 *
 * @since 1.516.0
 */
public final class ScreenTransport implements Transport {

    /** Choices a page shows; a longer list pages with A/D or the arrows under it. */
    private static final int PAGE = 12;
    private static final int PER_ROW = 4;
    private static final String PAGE_KEY = "page";

    private final Plugin library;
    private final Map<UUID, ScreenSession> shown = new ConcurrentHashMap<>();

    /** Creates the transport used by ExyliaLib. */
    public ScreenTransport(@NotNull Plugin plugin) {
        this.library = java.util.Objects.requireNonNull(plugin, "plugin");
    }

    /** One answer: what it parses as, how it reads, its icon (none for a confirm) and its tone. */
    private record Option(String raw, String label, @Nullable Material icon, @Nullable String description, String fill) {
    }

    @Override
    public boolean show(@NotNull InputSession session) {
        Player player = Bukkit.getPlayer(session.playerId());
        if (player == null || !player.isOnline()) return false;
        if (!(session.request() instanceof InputRequest<?, ?> request)) return false;
        ScreenSearch<?> search = request instanceof SearchInput<?> input ? search(session, input) : null;
        List<Option> options = options(request);
        ScreenKeypad keypad = ScreenKeypad.handles(request) ? new ScreenKeypad(session, request, () -> typeIt(session)) : null;
        if (options == null && search == null && keypad == null) return false;
        Plugin owner = Bukkit.getPluginManager().getPlugin(session.pluginName());
        if (owner == null || !owner.isEnabled()) owner = library;
        // Held by a camera the question goes through the next transport: a dialog or chat is seen from anywhere.
        // A request naming this transport comes from a screen (a game's screen form) whatever the player's menus are.
        boolean asked = request.preferredTransports().contains(kind()) && MenuStyles.canShowScreens(player);
        if (!asked && !MenuStyles.of(owner).screens(player) || net.exylia.lib.screen.internal.ScreenRuntime.cameraHeld(player)) return false;
        ScreenSearch<?> finder = search == null ? null : search.owner(owner);
        Screen screen = Screen.of(s -> finder != null ? finder.draw(s) : keypad != null ? keypad.draw(s)
                        : dialog(s, session, request, options))
                .onClose(s -> {
                    // Gone from the map means it was closed on purpose (answered, or "Type it" handing it on).
                    if (shown.remove(session.playerId(), s)) session.end(InputOutcome.CANCELLED);
                });
        ScreenSession opened = Screens.of(owner).open(player, screen);
        if (opened == null) return false;
        shown.put(session.playerId(), opened);
        return true;
    }

    /** "Type it" on the keypad: the keypad closes without cancelling and the request goes on to the dialog. */
    private void typeIt(InputSession session) {
        ScreenSession screen = shown.remove(session.playerId());
        if (screen != null && screen.open()) screen.close();
        InputRuntime.handOff(session, kind());
    }

    @Override
    public void close(@NotNull InputSession session) {
        ScreenSession screen = shown.remove(session.playerId());
        if (screen != null && screen.open()) screen.close();
    }

    @Override
    public @NotNull TransportKind kind() {
        return TransportKind.SCREEN;
    }

    private static @Nullable List<Option> options(InputRequest<?, ?> request) {
        if (request instanceof ConfirmInput confirm) {
            return List.of(new Option("false", confirm.denyLabel(), null, null, "{letters_black}20"),
                    new Option("true", confirm.confirmLabel(), null, null, confirm.isDangerous() ? "{error}" : "{primary}"));
        }
        if (request instanceof FlagInput) {
            return List.of(new Option("false", Phrases.tr("No"), null, null, "{letters_black}20"),
                    new Option("true", Phrases.tr("Yes"), null, null, "{primary}"));
        }
        if (request instanceof ChoiceInput<?> choice) return choices(choice);
        return null;
    }

    private static <T> @Nullable ScreenSearch<T> search(InputSession session, SearchInput<T> input) {
        return input.choices().isEmpty() && input.source() == null ? null : new ScreenSearch<>(session, input);
    }

    private static <T> @Nullable List<Option> choices(ChoiceInput<T> choice) {
        if (choice.choices().isEmpty()) return null;
        List<Option> out = new ArrayList<>();
        for (T value : choice.choices()) {
            out.add(new Option(choice.keyOf(value), ChoiceOptions.labelOf(choice, value), choice.iconOf(value),
                    choice.descriptionOf(value), "{letters_black}20"));
        }
        return out;
    }

    /** The dialog: the question, then the answers as buttons (a confirm) or cards (a choice), then Cancel. */
    private static Node dialog(ScreenSession screen, InputSession session, InputRequest<?, ?> request, List<Option> options) {
        boolean choice = options.get(0).icon() != null;
        Node panel = Node.column(Node.text(Text.of(request.prompt()).build()).wrap(240).textAlign(Node.Align.CENTER))
                .padding(12, 16).gap(10).align(Node.Align.CENTER).background("#17131f")
                .outline(danger(request) ? "{error}80" : "{primary}60", 1)
                .enter(Motion.Tween.of(Motion.Transform.scale(0.9), 5, Motion.Ease.BACK));
        if (choice) {
            int pages = (options.size() + PAGE - 1) / PAGE;
            int page = Math.max(0, Math.min(pages - 1, screen.state(PAGE_KEY, 0)));
            Node grid = Node.column().gap(4).align(Node.Align.CENTER);
            Node row = null;
            for (int i = page * PAGE; i < Math.min(options.size(), (page + 1) * PAGE); i++) {
                if (i % PER_ROW == 0) grid.add(row = Node.row().gap(4));
                row.add(card(session, request, options.get(i)));
            }
            panel.add(grid);
            if (pages > 1) panel.add(pager(page, pages));
            panel.add(button(session, request, null, Phrases.tr("Cancel"), "{letters_black}20"));
        } else {
            Node row = Node.row().gap(6);
            for (Option option : options) row.add(button(session, request, option.raw(), option.label(), option.fill()));
            panel.add(row);
        }
        return Node.column(panel).align(Node.Align.CENTER).justify(Node.Justify.CENTER);
    }

    /** {@code ‹ 2 / 3 ›}, turned by a click or by A and D. */
    static Node pager(int page, int pages) {
        Node previous = Node.text(Text.of(page > 0 ? "{letters}‹" : "{muted}‹").build()).padding(2, 6)
                .hover("{primary}50").key("a").onClick(event -> event.session().set(PAGE_KEY, Math.max(0, page - 1)));
        Node next = Node.text(Text.of(page < pages - 1 ? "{letters}›" : "{muted}›").build()).padding(2, 6)
                .hover("{primary}50").key("d").onClick(event -> event.session().set(PAGE_KEY, Math.min(pages - 1, page + 1)));
        Node label = Node.text(Text.of("{letters_black}" + (page + 1) + " / " + pages).build()).scale(0.8);
        return Node.row(previous, label, next).gap(6).align(Node.Align.CENTER);
    }

    private static boolean danger(InputRequest<?, ?> request) {
        return request instanceof ConfirmInput confirm && confirm.isDangerous();
    }

    private static Node card(InputSession session, InputRequest<?, ?> request, Option option) {
        Node card = Node.column(Node.item(new ItemStack(option.icon())).size(18, 18),
                        Node.text(Text.of("{letters}" + option.label()).build()).scale(0.7).textAlign(Node.Align.CENTER))
                .size(64, 40).padding(3).gap(3).align(Node.Align.CENTER).justify(Node.Justify.CENTER)
                .background("#201a2b").hover("#2e2541").hoverOutline("{primary}")
                .active(null, Motion.Transform.scale(0.95)).onClick(event -> answer(session, request, option.raw()));
        if (option.description() != null && !option.description().isBlank()) {
            card.tooltip(option.label(), option.description());
        }
        return card;
    }

    private static Node button(InputSession session, InputRequest<?, ?> request, @Nullable String raw, String label,
                               String fill) {
        return Node.text(Text.of("{letters}" + label).build()).padding(4, 12).background(fill).hover("{primary}50")
                .active(null, Motion.Transform.scale(0.95))
                .onClick(event -> {
                    if (raw == null) session.end(InputOutcome.CANCELLED);
                    else answer(session, request, raw);
                });
    }

    private static void answer(InputSession session, InputRequest<?, ?> request, String raw) {
        InputParser.Parsed<?> parsed = request.parseRaw(raw);
        if (parsed.ok()) session.complete(parsed.value());
        else session.end(InputOutcome.CANCELLED);
    }
}
