package net.exylia.lib.hologram.internal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a line down to its first characters, keeping every colour and style.
 *
 * <p>Works on the parsed component rather than on the source text, so a cut
 * can never land inside a tag or a placeholder. Anything that is not plain
 * text, such as a sprite or a translated name, counts as one character.
 */
final class Typewriter {

    private Typewriter() {
    }

    /** How many characters the line has, as the typewriter counts them. */
    static int length(Component component) {
        int length = component instanceof TextComponent text
                ? text.content().codePointCount(0, text.content().length())
                : 1;
        for (Component child : component.children()) {
            length += length(child);
        }
        return length;
    }

    /** The first {@code count} characters of a line. */
    static Component cut(Component component, int count) {
        return count <= 0 ? Component.empty() : cut(component, new int[]{count});
    }

    private static Component cut(Component component, int[] budget) {
        Component head;
        if (component instanceof TextComponent text) {
            String content = text.content();
            int available = content.codePointCount(0, content.length());
            int take = Math.min(available, budget[0]);
            budget[0] -= take;
            head = text.content(content.substring(0, content.offsetByCodePoints(0, take)));
        } else {
            head = component;
            budget[0] -= 1;
        }
        List<Component> children = new ArrayList<>();
        for (Component child : component.children()) {
            if (budget[0] <= 0) {
                break;
            }
            children.add(cut(child, budget));
        }
        return head.children(children);
    }
}
