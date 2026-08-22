package net.exylia.lib.schematic.internal;

import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An engine that writes a placeholder file and remembers what it was asked.
 *
 * <p>This is what makes the module testable at all: everything it decides — the
 * name, the folder, the order of the stages, what happens when one of them
 * fails — sits above {@link SchematicEngine}, so a fake here exercises all of
 * it with no FastAsyncWorldEdit and no server.
 */
final class RecordingEngine implements SchematicEngine {

    /** Every call, in order, as {@code save:<file>:<entities>} or {@code paste:...}. */
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    /** A shared log, so a test can assert the order of the three stages. */
    private final List<String> log;

    /** Thrown by the next call, when a test wants a stage to fail. */
    private volatile Throwable failure;

    private volatile boolean released;

    RecordingEngine(List<String> log) {
        this.log = log;
    }

    /** Makes the next call throw whatever the test wants, including an Error. */
    RecordingEngine failsWith(Throwable value) {
        this.failure = value;
        return this;
    }

    @Override
    public void save(World world, Bounds bounds, File destination, boolean copyEntities)
            throws Exception {
        calls.add("save:" + destination.getName() + ":" + copyEntities);
        log.add("save");
        raise();
        // A real engine writes a file, and the store's index and resolution are
        // both about files that exist: a fake that wrote nothing would let a
        // save pass while a paste of the same name reported NOT_FOUND.
        Files.writeString(destination.toPath(), "schematic");
    }

    @Override
    public void paste(World world, int x, int y, int z, File source, boolean copyEntities) {
        calls.add("paste:" + source.getName() + ":" + x + "," + y + "," + z
                + ":" + copyEntities);
        log.add("paste");
        raiseUnchecked();
    }

    @Override
    public void releaseAll() {
        released = true;
    }

    /** Every call, in order. */
    List<String> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    /** Whether the engine was told to drop what it holds. */
    boolean isReleased() {
        return released;
    }

    private void raise() throws Exception {
        Throwable pending = failure;
        if (pending == null) {
            return;
        }
        failure = null;
        if (pending instanceof Exception exception) {
            throw exception;
        }
        throw (Error) pending;
    }

    /**
     * The same, for the method that declares no checked exception.
     *
     * <p>Wrapped in an unchecked one rather than sneaky-thrown: the module has
     * to survive either shape, and this is the honest one.
     */
    private void raiseUnchecked() {
        Throwable pending = failure;
        if (pending == null) {
            return;
        }
        failure = null;
        if (pending instanceof Error error) {
            throw error;
        }
        if (pending instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new IllegalStateException(new IOException(pending));
    }
}
