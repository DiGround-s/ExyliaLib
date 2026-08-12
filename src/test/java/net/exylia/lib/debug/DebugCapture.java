package net.exylia.lib.debug;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lets tests in other packages read what was logged.
 *
 * <p>Lives here because {@code Debug}'s sink is package-private on purpose:
 * the production API should not grow a setter just so tests can watch it.
 */
public final class DebugCapture {

    private DebugCapture() {
    }

    /**
     * Starts capturing, returning the live list of plain-text lines.
     *
     * @return the lines logged from now on
     */
    public static List<String> start() {
        List<String> lines = new CopyOnWriteArrayList<>();
        Debug.setSink((line, error) ->
                lines.add(PlainTextComponentSerializer.plainText().serialize(line)));
        return lines;
    }

    /** Stops capturing and restores normal console output. */
    public static void stop() {
        Debug.resetSink();
    }
}
