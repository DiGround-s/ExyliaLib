package net.exylia.lib.config.internal;

import net.exylia.lib.config.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * Copies an analysed {@link SchemaNode} into the public {@link Schema} value.
 *
 * <p>This class is the whole reason {@code SchemaNode} can stay package-private
 * while a UI module still learns the shape of a config record. Its signature
 * takes and returns only public types, so neither {@code SchemaNode} nor the
 * canonical {@link java.lang.reflect.Constructor} it holds can reach a public
 * one: the constructor is the piece that would turn a description into a way to
 * build arbitrary instances, and it is deliberately dropped here.
 *
 * <p>The copy is taken once, at the call, and shares nothing mutable with the
 * cached analysis. Direction of dependency is {@code internal -> public}, the
 * same way {@link SchemaCache} already imports {@code config.Comment}.
 */
final class SchemaProjection {

    private SchemaProjection() {
    }

    /**
     * Projects a record type into its public schema description.
     *
     * @param type       the record class to describe
     * @param pluginName the plugin that declared it, so the underlying analysis
     *                   is released with that plugin
     * @return an immutable value copy; never {@code null}
     */
    static Schema of(Class<?> type, String pluginName) {
        return copy(SchemaCache.of(type, pluginName));
    }

    private static Schema copy(SchemaNode node) {
        List<Schema.Field> fields = new ArrayList<>(node.components().size());
        for (SchemaNode.SchemaComponent component : node.components()) {
            fields.add(new Schema.Field(
                    component.name(),
                    component.key(),
                    component.type(),
                    component.generic(),
                    component.comments(),
                    component.isSection() ? copy(component.nested()) : null));
        }
        return new Schema(node.type(), node.comments(), fields);
    }
}
