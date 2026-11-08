package net.exylia.lib.screen;

import net.exylia.lib.text.Phrases;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The Java half of the game frame, the {@code <game-frame>} component of a game drawn on a screen: the result it shows
 * in place and the choices that follow it (Again, Change stake, Leave). Layout and words are the markup's; see
 * docs/screens.md, "Giving a game its screen form".
 *
 * <pre>{@code
 * GameFrame.install(markup);                        // once, when the plugin takes the markup
 * GameFrame frame = GameFrame.of(session, markup);
 * frame.result("success", "{success}Cashed out", "{letters}+$241");
 * frame.offer(new GameFrame.Offer("$100", null, this::again, this::change, this::leave));
 * frame.clear();                                    // the next round, on the same screen
 * }</pre>
 *
 * @since 1.531.0
 */
public final class GameFrame {

    /** The state that holds the open tab: {@code play}, {@code how} or {@code payouts}. */
    public static final String TAB = "game_tab";
    /** What the frame reads in markup: {@code game_tone} is {@code success}, {@code error} or any tone's name. */
    private static final String[] VALUES = {"game_result", "game_detail", "game_tone", "game_again", "game_refused"};
    private static final String OFFER = "game_offer";

    /**
     * What a finished round offers.
     *
     * @param stake   what playing again costs, as players read it
     * @param refused why Again cannot be pressed now (a stake they cannot pay), shown under it; {@code null} when it can
     * @param again   runs when Again is pressed
     * @param change  runs when Change stake is pressed; {@code null} leaves the button out
     * @param leave   runs when Leave is pressed
     */
    public record Offer(@NotNull String stake, @Nullable String refused, @NotNull Runnable again,
                        @Nullable Runnable change, @NotNull Runnable leave) {
    }

    private final ScreenSession session;
    private final ScreenMarkup markup;

    private GameFrame(ScreenSession session, ScreenMarkup markup) {
        this.session = session;
        this.markup = markup;
    }

    /** Registers what the frame's component reads and does on {@code markup}. Asking twice changes nothing. */
    public static @NotNull ScreenMarkup install(@NotNull ScreenMarkup markup) {
        // Values rather than state: a result carries colours, and state is drawn as written.
        for (String name : VALUES) markup.value(name, session -> session.state(name, ""));
        markup.action("game_again", (event, args) -> choose(event, Offer::again));
        markup.action("game_change", (event, args) -> choose(event, Offer::change));
        markup.action("game_leave", (event, args) -> choose(event, Offer::leave));
        return markup;
    }

    private static void choose(ScreenEvent event, java.util.function.Function<Offer, Runnable> which) {
        Object offer = event.session().state(OFFER, "");
        if (!(offer instanceof Offer shown)) return;
        Runnable choice = which.apply(shown);
        if (choice != null) choice.run();
    }

    /** The frame of an open screen drawn from {@code markup}. */
    public static @NotNull GameFrame of(@NotNull ScreenSession session, @NotNull ScreenMarkup markup) {
        return new GameFrame(session, markup);
    }

    /** The state a screen drawn with a game frame starts with. */
    public static @NotNull Map<String, Object> state() {
        return Map.of(TAB, "play");
    }

    /**
     * The round's result, in place of the main action, with the board still in view.
     *
     * @param tone   a tone's name, for the styling of its box: {@code success}, {@code error}, {@code warning}...
     * @param title  what happened, with its colours
     * @param detail what it came to, with its colours; empty for none
     */
    public void result(@NotNull String tone, @NotNull String title, @NotNull String detail) {
        session.set("game_tone", tone);
        session.set("game_detail", detail);
        session.set("game_result", title);
    }

    /** Puts up what the result offers: Again, Change stake and Leave. */
    public void offer(@NotNull Offer offer) {
        session.set("game_again", "{letters}" + markup.word("frame.again", Phrases.tr("Again")) + " {letters_black}· {letters}" + offer.stake());
        session.set("game_refused", offer.refused() == null ? "" : offer.refused());
        session.set("game_can_change", offer.change() != null);
        session.set(OFFER, offer);
    }

    /** Takes the offer down, the result staying: the choice was made and the next round is on its way. */
    public void withdraw() {
        session.set(OFFER, null);
    }

    /** Clears the result and the offer: a new round starts on the same screen. */
    public void clear() {
        session.set(OFFER, null);
        for (String name : VALUES) session.set(name, "");
    }

    /** The tab on view. */
    public @NotNull String tab() {
        return String.valueOf(session.state(TAB, "play"));
    }
}
