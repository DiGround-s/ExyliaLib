package net.exylia.lib.database.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One write of an {@link net.exylia.lib.database.Atomic}: what it does, to which
 * table, with which record.
 *
 * @param kind     what the write is
 * @param model    the table's model
 * @param record   the record written
 * @param column   the compared column, for {@link Kind#UPDATE_IF}
 * @param expected the value it must hold, in record form, for {@link Kind#UPDATE_IF}
 * @since 1.663.0
 */
public record AtomicStep(@NotNull Kind kind, @NotNull EntityModel<?> model, @NotNull Object record,
                         @Nullable ColumnModel column, @Nullable Object expected) {

    /** What a step does. */
    public enum Kind {
        /** Writes over the row only while a column holds a value; no match fails the whole unit. */
        UPDATE_IF,
        /** Creates the row; one that already exists fails the whole unit. */
        INSERT,
        /** Writes the row, creating it when there is none. */
        SAVE
    }

    /** The record's key, decoded, for a cache that must forget the row. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public @NotNull Object id() {
        EntityModel raw = model;
        return raw.id().decode(raw.idOf(record));
    }
}
