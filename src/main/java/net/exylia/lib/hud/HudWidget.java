package net.exylia.lib.hud;

import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.ScreenMarkup;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * An element that stays on a player's HUD until it is removed: money, a quest tracker, a combat timer.
 *
 * <pre>{@code
 * huds.show(player, HudWidget.of("money", HudAnchor.TOP_LEFT, p -> Text.of("{success}" + balance(p)).build())
 *         .when(p -> p.getWorld().getName().equals("spawn"))
 *         .every(20));
 * }</pre>
 *
 * <p>{@code text} and {@code when} run on the player's own thread every {@code every} ticks (and on
 * {@link HudHandle#refresh()}), so they read memory, never a database. A text equal to the one on screen sends nothing.
 * A {@code null} text hides the element as a {@code false} condition does.
 *
 * @param id         unique per plugin and player: showing another with the same id replaces it in place
 * @param anchor     where it sits
 * @param text       what it says, one or more lines ({@code \n})
 * @param when       whether it shows now; {@code null} always
 * @param order      its place in its anchor's stack, lowest nearest the edge
 * @param every      how often text and condition are read again, in ticks (at least 2)
 * @param background ARGB of the panel behind it, {@code 0} for none
 * @param scale      its size against the HUD's default text size
 * @param layout     a designed widget's tree ({@link #layout}); {@code null} for words alone
 * @since 1.566.0
 */
public record HudWidget(@NotNull String id, @NotNull HudAnchor anchor, @NotNull Function<Player, Component> text,
                        @Nullable Predicate<Player> when, int order, int every, int background, double scale,
                        @Nullable Function<Player, Node> layout) {

    /** The panel behind an element unless it says otherwise: the palette's near-black, a little see-through. */
    public static final int PANEL = 0xB0120E1A;

    public HudWidget {
        every = Math.max(2, every);
        scale = Math.clamp(scale, 0.25, 4);
    }

    /** A widget of words, with no layout. */
    public HudWidget(@NotNull String id, @NotNull HudAnchor anchor, @NotNull Function<Player, Component> text,
                     @Nullable Predicate<Player> when, int order, int every, int background, double scale) {
        this(id, anchor, text, when, order, every, background, scale, null);
    }

    /**
     * @param id     unique per plugin and player
     * @param anchor where it sits
     * @param text   what it says, read again every 10 ticks
     * @return the widget
     */
    public static @NotNull HudWidget of(@NotNull String id, @NotNull HudAnchor anchor,
                                        @NotNull Function<Player, Component> text) {
        return new HudWidget(id, anchor, text, null, 0, 10, 0, 1);
    }

    /** A text that never changes. */
    public static @NotNull HudWidget of(@NotNull String id, @NotNull HudAnchor anchor, @NotNull Component text) {
        return of(id, anchor, player -> text).every(1200);
    }

    /**
     * A widget designed as a screen is: boxes with backgrounds, gradients and outlines, rows and columns, words,
     * progress bars and pictures, laid out by the screens' flexbox and read again every 10 ticks. A {@code null}
     * tree hides it. Clicks, hovers and motion do nothing on a HUD; items and turning bodies are left out.
     *
     * @param layout the tree for the player now, built on their thread
     * @since 1.582.0
     */
    public static @NotNull HudWidget layout(@NotNull String id, @NotNull HudAnchor anchor,
                                            @NotNull Function<Player, Node> layout) {
        return new HudWidget(id, anchor, player -> null, null, 0, 10, 0, 1, layout);
    }

    /**
     * A widget written as screen markup and CSS ({@link ScreenMarkup}): what {@link ScreenMarkup#node} draws for the
     * player, read again every 10 ticks.
     *
     * @since 1.582.0
     */
    public static @NotNull HudWidget markup(@NotNull String id, @NotNull HudAnchor anchor, @NotNull ScreenMarkup markup) {
        return layout(id, anchor, markup::node);
    }

    public @NotNull HudWidget when(@Nullable Predicate<Player> condition) {
        return new HudWidget(id, anchor, text, condition, order, every, background, scale, layout);
    }

    public @NotNull HudWidget order(int place) {
        return new HudWidget(id, anchor, text, when, place, every, background, scale, layout);
    }

    public @NotNull HudWidget every(int ticks) {
        return new HudWidget(id, anchor, text, when, order, ticks, background, scale, layout);
    }

    public @NotNull HudWidget background(int argb) {
        return new HudWidget(id, anchor, text, when, order, every, argb, scale, layout);
    }

    public @NotNull HudWidget scale(double size) {
        return new HudWidget(id, anchor, text, when, order, every, background, size, layout);
    }
}
