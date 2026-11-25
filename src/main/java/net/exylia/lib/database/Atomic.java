package net.exylia.lib.database;

import net.exylia.lib.database.internal.AtomicStep;
import net.exylia.lib.database.internal.ColumnModel;
import net.exylia.lib.database.internal.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Several writes to tables of one plugin's database that land together or not at all.
 *
 * <p>For a change that must never be half-written: a balance and the ledger row
 * that says it was paid, a prize and the delivery that still owes it.
 *
 * <pre>{@code
 * database.atomic()
 *         .updateIf(accounts, paid, "version", read.version())
 *         .insert(ledger, new LedgerRow(operationId, ...))
 *         .commit()
 *         .thenAccept(landed -> { ... });
 * }</pre>
 *
 * <p>{@link #commit} answers {@code true} when every write landed, and
 * {@code false} when an {@link #updateIf} matched no row, an {@link #insert}
 * found its key taken, or the database broke a deadlock by giving up on this
 * one: then nothing was written. A failed future means the database could not
 * be asked, or the answer to the commit was lost, and the writes may or may not
 * have landed; a unique key among them (an operation id) is what makes asking
 * again safe.
 *
 * <p>SQL databases (H2, MySQL, MariaDB, Postgres) run it as one transaction;
 * MongoDB as a multi-document transaction, which needs a replica set. A
 * standalone MongoDB server has none: it is warned about once, and the steps
 * are then written one after another, so a crash between them leaves the
 * earlier ones written, and a step that fails after others landed fails the
 * future.
 *
 * @see PluginDatabase#atomic()
 * @since 1.663.0
 */
public final class Atomic {

    private final PluginDatabase database;
    private final List<AtomicStep> steps = new ArrayList<>();
    private @Nullable Storage storage;
    /** Every table's storage, so each table is created before the transaction names it. */
    private final java.util.Set<Storage> tables = new java.util.LinkedHashSet<>();
    private boolean frozen;

    Atomic(@NotNull PluginDatabase database) {
        this.database = database;
    }

    /**
     * Writes a record over its row only while one column still holds a value,
     * like {@link Repository#updateIf}; a row that does not match fails the unit.
     *
     * @return this unit
     * @throws IllegalArgumentException if the column is unknown or is the key
     */
    public <T> @NotNull Atomic updateIf(@NotNull Repository<T> repository, @NotNull T row, @NotNull String column,
                                        @Nullable Object expected) {
        ColumnModel target = repository.model().byComponent(column);
        if (target == null) target = repository.model().column(column);
        if (target == null || target.id()) {
            throw new IllegalArgumentException("updateIf() needs a column of " + repository.table()
                    + " other than its key, and '" + column + "' is " + (target == null ? "not one" : "the key"));
        }
        return add(repository, new AtomicStep(AtomicStep.Kind.UPDATE_IF, repository.model(), row, target, expected));
    }

    /**
     * Creates a row under the key it carries; a key already taken fails the unit.
     *
     * @return this unit
     */
    public <T> @NotNull Atomic insert(@NotNull Repository<T> repository, @NotNull T row) {
        return add(repository, new AtomicStep(AtomicStep.Kind.INSERT, repository.model(), row, null, null));
    }

    /**
     * Writes a row, creating it when there is none, like {@link Repository#save}.
     *
     * @return this unit
     */
    public <T> @NotNull Atomic save(@NotNull Repository<T> repository, @NotNull T row) {
        return add(repository, new AtomicStep(AtomicStep.Kind.SAVE, repository.model(), row, null, null));
    }

    private Atomic add(Repository<?> repository, AtomicStep step) {
        if (!database.has(repository.type())) {
            throw new IllegalArgumentException(repository.table() + " is not a table of "
                    + database.plugin().getName() + "'s database");
        }
        if (storage == null) storage = repository.storage();
        tables.add(repository.storage());
        frozen |= repository.frozen();
        steps.add(step);
        return this;
    }

    /**
     * Writes every step in one transaction.
     *
     * @return {@code true} when all landed, {@code false} when none did because a
     *         condition failed; fails when the outcome is unknown
     */
    public @NotNull CompletableFuture<Boolean> commit() {
        if (steps.isEmpty() || storage == null) return CompletableFuture.completedFuture(true);
        // A wipe in progress drops writes, so a unit that touches a frozen table lands nowhere.
        if (frozen) return CompletableFuture.completedFuture(false);
        Storage runner = storage;
        List<AtomicStep> written = List.copyOf(steps);
        CompletableFuture<?>[] ready = tables.stream().map(Storage::whenReady).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(ready).thenCompose(ignored -> runner.atomic(written));
    }
}
