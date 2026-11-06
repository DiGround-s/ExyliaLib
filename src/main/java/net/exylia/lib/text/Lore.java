package net.exylia.lib.text;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Item names and lore in the Exylia card style, for lore a plugin builds in
 * code. Menus written in YAML follow the same layout by hand; this writes it
 * so code and files cannot drift apart.
 *
 * <pre>{@code
 * String name = Lore.name(Lore.Tone.PRIMARY, "Mine", "%mine_name%");
 * List<String> lore = Lore.of(Lore.Tone.PRIMARY)
 *         .subtitle("Mine setting")
 *         .section("Information")
 *         .text("How and when this mine")
 *         .text("refills its blocks.")
 *         .section("✦", "Status")
 *         .stat("clock", "info", "Interval", "%interval%")
 *         .click("Left-click", "edit interval")
 *         .lines();
 * }</pre>
 *
 * <p>Produces the strings, in ExyliaLib notation; they are parsed wherever the
 * lore is drawn, like any other lore. A card reads:
 * <pre>
 * Mine ▸ Reset                         name: context, then the subject in the tone's gradient
 * MINE SETTING                         subtitle: what kind of thing this is
 *
 * ✎ Information                        section: glyph and label in the tone
 *  How and when this mine              text: body
 *
 * ✦ Status
 *   {icon:clock} Interval: 15m                stat: icon and value in the value's colour
 *
 * {icon:click} Left-click · edit interval         action: in the tone
 * </pre>
 *
 * @since 1.477.0
 */
public final class Lore {

    /**
     * What a card is about, which colours its name, section labels and
     * actions. Each runs from a palette colour to its light pair.
     */
    public enum Tone {
        /** A subject's own card, settings and management: the default. */
        PRIMARY("primary", "secondary_light"),
        /** Browsing, choosing and read-only information. */
        INFO("info", "info_light"),
        /** Creating, claiming, joining, buying, turning something on. */
        SUCCESS("success", "success_light"),
        /** Rankings, rewards and anything featured. */
        HIGHLIGHT("highlight", "warning_light"),
        /** Caution: pending, limited or about to expire. */
        WARNING("warning", "warning_light"),
        /** Destructive actions, leaving, and the way back. */
        ERROR("error", "error_light"),
        /** Cosmetics and the purely decorative. */
        ACCENT("accent", "secondary_light");

        private final String colour;
        private final String light;

        Tone(String colour, String light) {
            this.colour = colour;
            this.light = light;
        }

        /** The tone's colour token, such as {@code {info}}. */
        public @NotNull String colour() {
            return "{" + colour + "}";
        }

        /** {@code text} in the tone's gradient. */
        public @NotNull String gradient(@NotNull String text) {
            return "<gradient:{" + colour + "}:{" + light + "}>" + text + "</gradient>";
        }
    }

    private final Tone tone;
    private final List<String> lines = new ArrayList<>();
    private boolean actions;

    private Lore(Tone tone) {
        this.tone = tone;
    }

    /**
     * A card name: where the item belongs, then what it is.
     *
     * @param tone    the card's tone
     * @param context the screen or kind it belongs to, such as {@code Mine}
     * @param subject what it is; a placeholder is painted across its value
     * @return the name, such as {@code {letters}Mine {letters_black}▸ <gradient…>Reset</gradient>}
     */
    public static @NotNull String name(@NotNull Tone tone, @NotNull String context, @NotNull String subject) {
        return "{letters}" + context + " {letters_black}▸ " + tone.gradient(subject);
    }

    /**
     * A card name with no context: a header that is the screen's own subject.
     *
     * @param tone    the card's tone
     * @param subject what it is
     * @return the name in the tone's gradient
     */
    public static @NotNull String name(@NotNull Tone tone, @NotNull String subject) {
        return tone.gradient(subject);
    }

    /** Starts a card's lore in a tone. */
    public static @NotNull Lore of(@NotNull Tone tone) {
        return new Lore(tone);
    }

    /** The line under the name saying what kind of thing this is: {@code Mine setting}, {@code Danger zone}. */
    public @NotNull Lore subtitle(@NotNull String subtitle) {
        lines.add("{letters_black}" + subtitle);
        return this;
    }

    /** A section headed by {@code ✎}, for a description: {@code ✎ Information}. */
    public @NotNull Lore section(@NotNull String label) {
        return section("✎", label);
    }

    /** A section with a glyph of its own: {@code ✦} for data, {@code ⚔} for combat, {@code ♦} for rewards. */
    public @NotNull Lore section(@NotNull String glyph, @NotNull String label) {
        gap();
        lines.add(tone.colour() + glyph + " " + label);
        return this;
    }

    /** A line of body text under the current section. */
    public @NotNull Lore text(@NotNull String text) {
        lines.add(" {letters}" + text);
        return this;
    }

    /**
     * A stat: {@code · {icon:clock} Interval: 15m}.
     *
     * @param icon   an {@code {icon:…}} name such as {@code clock}, or {@code null} for none
     * @param colour the value's palette role, such as {@code info}; the icon's glyph takes it too
     * @param label  what is measured
     * @param value  the value, often a placeholder
     */
    public @NotNull Lore stat(String icon, @NotNull String colour, @NotNull String label, @NotNull String value) {
        String drawn = icon == null ? "" : "{" + colour + "}{icon:" + icon + "} ";
        lines.add("  {letters_black}· " + drawn + "{letters}" + label + "{letters_black}: {" + colour + "}" + value);
        return this;
    }

    /** The one thing a click does: {@code {icon:click} Left-click to edit}. */
    public @NotNull Lore action(@NotNull String action) {
        actionGap();
        lines.add(tone.colour() + clickIcon(action) + action);
        return this;
    }

    /** One of several clicks: {@code {icon:click} Left-click · +1 weight}. */
    public @NotNull Lore click(@NotNull String click, @NotNull String effect) {
        actionGap();
        lines.add(tone.colour() + clickIcon(click) + click + " {letters_black}· {letters}" + effect);
        return this;
    }

    /** A dark note under the prompts, such as a destructive action's "You will be asked to confirm." */
    public @NotNull Lore note(@NotNull String note) {
        lines.add("{letters_black}" + note);
        return this;
    }

    /** Any line of the caller's own, as written. */
    public @NotNull Lore line(@NotNull String line) {
        lines.add(line);
        return this;
    }

    /** The lore, one string per line. */
    public @NotNull List<String> lines() {
        return List.copyOf(lines);
    }

    private void gap() {
        if (!lines.isEmpty()) {
            lines.add("");
        }
    }

    private void actionGap() {
        if (!actions) {
            gap();
            actions = true;
        }
    }

    /** The mouse sprite for a click line: the right button for a right-click, the plain one otherwise. */
    private static String clickIcon(String text) {
        return text.toLowerCase(java.util.Locale.ROOT).contains("right") ? "{icon:right_click} " : "{icon:click} ";
    }
}
