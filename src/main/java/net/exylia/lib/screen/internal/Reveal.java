package net.exylia.lib.screen.internal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Words typing themselves out: the first characters of a styled text, each keeping its own style. A part that is
 * not plain text (a sprite, a translated name) counts as one character and shows whole.
 *
 * <p>Internal: free to change without notice.
 */
final class Reveal {

    private Reveal() {
    }

    /** How many characters {@code text} has, as {@link #take} counts them. */
    static int length(Component text) {
        int n = text instanceof TextComponent t ? t.content().codePointCount(0, t.content().length()) : 1;
        for (Component child : text.children()) n += length(child);
        return n;
    }

    /** The first {@code count} characters of {@code text}; empty for 0, the whole of it past its length. */
    static Component take(Component text, int count) {
        Component out = take(text, new int[]{count});
        return out == null ? Component.empty() : out;
    }

    private static Component take(Component text, int[] left) {
        if (left[0] <= 0) return null;
        Component head;
        if (text instanceof TextComponent t) {
            String s = t.content();
            int points = s.codePointCount(0, s.length());
            if (points > left[0]) {
                String cut = s.substring(0, s.offsetByCodePoints(0, left[0]));
                left[0] = 0;
                return t.content(cut).children(List.of());
            }
            left[0] -= points;
            head = t;
        } else {
            left[0]--;
            head = text;
        }
        List<Component> kids = new ArrayList<>();
        for (Component child : text.children()) {
            Component kept = take(child, left);
            if (kept == null) break;
            kids.add(kept);
        }
        return head.children(kids);
    }
}
