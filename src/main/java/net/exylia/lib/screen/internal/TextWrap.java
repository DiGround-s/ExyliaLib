package net.exylia.lib.screen.internal;

import net.exylia.lib.text.Centering;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;

import java.util.ArrayList;
import java.util.List;

/**
 * Breaks styled text onto lines no wider than a width, at spaces, keeping every part's colour and weight. The lines
 * go to the client joined by newlines, so it draws exactly the lines the layout measured.
 *
 * <p>Internal: free to change without notice.
 */
final class TextWrap {

    private TextWrap() {
    }

    /** One run of text in one style, or a part that cannot be broken (a translated name, an icon). */
    private record Piece(String text, Component whole, Style style) {
    }

    /** {@code text} broken at spaces onto lines of at most {@code width} font pixels; a longer word keeps its own line. */
    static Layout.Wrapped wrap(Component text, double width) {
        List<Piece> pieces = new ArrayList<>();
        flatten(text, Style.empty(), pieces);
        List<List<Component>> lines = new ArrayList<>();
        List<Component> line = new ArrayList<>();
        double used = 0;
        double widest = 0;
        Component pendingSpace = null;
        double spaceWidth = 0;
        for (Piece piece : pieces) {
            if (piece.whole() != null) {
                double w = Centering.pixelWidth(piece.whole());
                if (!line.isEmpty() && used + spaceWidth + w > width) {
                    widest = Math.max(widest, used);
                    lines.add(line);
                    line = new ArrayList<>();
                    used = 0;
                    pendingSpace = null;
                }
                if (pendingSpace != null) {
                    line.add(pendingSpace);
                    used += spaceWidth;
                }
                pendingSpace = null;
                line.add(piece.whole());
                used += w;
                continue;
            }
            String s = piece.text();
            int at = 0;
            while (at < s.length()) {
                char c = s.charAt(at);
                if (c == '\n') {
                    widest = Math.max(widest, used);
                    lines.add(line);
                    line = new ArrayList<>();
                    used = 0;
                    pendingSpace = null;
                    at++;
                    continue;
                }
                if (c == ' ') {
                    Component space = Component.text(" ", piece.style());
                    // Spaces at the start of a line are not drawn; several in a row count once.
                    if (!line.isEmpty() && pendingSpace == null) {
                        pendingSpace = space;
                        spaceWidth = Centering.pixelWidth(space);
                    }
                    at++;
                    continue;
                }
                int end = at;
                while (end < s.length() && s.charAt(end) != ' ' && s.charAt(end) != '\n') end++;
                Component word = Component.text(s.substring(at, end), piece.style());
                double w = Centering.pixelWidth(word);
                if (!line.isEmpty() && used + (pendingSpace == null ? 0 : spaceWidth) + w > width) {
                    widest = Math.max(widest, used);
                    lines.add(line);
                    line = new ArrayList<>();
                    used = 0;
                    pendingSpace = null;
                }
                if (pendingSpace != null) {
                    line.add(pendingSpace);
                    used += spaceWidth;
                    pendingSpace = null;
                }
                line.add(word);
                used += w;
                at = end;
            }
        }
        widest = Math.max(widest, used);
        lines.add(line);
        Component out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out = out.append(Component.newline());
            for (Component part : lines.get(i)) out = out.append(part);
        }
        return new Layout.Wrapped(out.compact(), lines.size(), widest);
    }

    private static void flatten(Component component, Style parent, List<Piece> out) {
        Style style = component.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (component instanceof TextComponent text) {
            if (!text.content().isEmpty()) out.add(new Piece(text.content(), null, style));
        } else {
            out.add(new Piece(null, component.children(List.of()).style(style), style));
        }
        for (Component child : component.children()) flatten(child, style, out);
    }
}
