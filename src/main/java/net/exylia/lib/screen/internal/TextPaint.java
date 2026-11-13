package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.Motion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Words painted with a {@link Motion.TextGradient}: every character of plain text takes its colour along the gradient
 * and keeps the rest of its style. A part that is not plain text (a sprite, a translated name) counts as one character
 * and keeps its own colour; line breaks are not counted.
 *
 * <p>Internal: free to change without notice.
 */
final class TextPaint {

    private TextPaint() {
    }

    /** {@code text} painted with {@code gradient} at {@code phase} (0 to 1). */
    static Component paint(Component text, Motion.TextGradient gradient, double phase) {
        int n = count(text);
        if (n == 0) return text;
        return paint(text, gradient, phase, n, new int[]{0}).compact();
    }

    private static int count(Component text) {
        int n;
        if (text instanceof TextComponent t) {
            n = 0;
            String s = t.content();
            for (int i = 0; i < s.length(); i = s.offsetByCodePoints(i, 1)) if (s.charAt(i) != '\n') n++;
        } else {
            n = 1;
        }
        for (Component child : text.children()) n += count(child);
        return n;
    }

    private static Component paint(Component text, Motion.TextGradient gradient, double phase, int n, int[] at) {
        List<Component> kids = new ArrayList<>();
        Component head;
        if (text instanceof TextComponent t) {
            String s = t.content();
            for (int i = 0; i < s.length(); ) {
                int next = s.offsetByCodePoints(i, 1);
                String ch = s.substring(i, next);
                kids.add(ch.equals("\n") ? Component.text(ch)
                        : Component.text(ch, TextColor.color(gradient.colourAt(at[0]++, n, phase))));
                i = next;
            }
            head = t.content("");
        } else {
            at[0]++;
            head = text;
        }
        for (Component child : text.children()) kids.add(paint(child, gradient, phase, n, at));
        return head.children(kids);
    }
}
