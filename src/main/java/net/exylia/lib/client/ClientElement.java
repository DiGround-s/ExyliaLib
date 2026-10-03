package net.exylia.lib.client;

import org.jetbrains.annotations.NotNull;

/**
 * Something a modified client draws and keeps until told otherwise: a timer,
 * a rally, a beam, a zone border or a progress bar.
 *
 * <p>Every element is addressed by its name. Showing an element whose name is
 * already on screen replaces it, and the library remembers what each plugin
 * showed so a player whose client reconnects gets it back.
 *
 * @since 1.233.0
 */
public sealed interface ClientElement permits Timer, Rally, Beam, ZoneBorder, ProgressBar {

    /**
     * Returns the handle it is removed by.
     *
     * @return the name
     */
    @NotNull String name();
}
