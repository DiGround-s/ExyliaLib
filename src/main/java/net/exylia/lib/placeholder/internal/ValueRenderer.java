package net.exylia.lib.placeholder.internal;

import net.kyori.adventure.text.Component;

/**
 * How a substituted value becomes part of a component.
 *
 * <p>Two kinds exist because two kinds of values exist. A value that says what
 * it means through formatting — a class's display name from a config, written
 * as {@code <#c8c8c8><bold>ARCHER</bold>} — is only itself when parsed;
 * inserting it literally is how a live server ended up showing the raw tags in
 * chat. A value that is just data — a number, a name — stays literal, because
 * inserting typed text as formatting is how a player injects {@code <bold>}
 * into a message.
 *
 * <p>The formatted implementation lives in the text module, which hands it in;
 * this module only knows the shape.
 */
@FunctionalInterface
public interface ValueRenderer {

    /** Inserted as plain text: no formatting in the value can take effect. */
    ValueRenderer LITERAL = Component::text;

    /**
     * Renders a substituted value.
     *
     * @param value the text of the value
     * @return the component to insert in its place
     */
    Component render(String value);
}
