package net.exylia.lib.panel;

import net.exylia.lib.panel.internal.PanelRuntime;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The panels of one plugin.
 *
 * <p>Obtained from {@link Panels#of(Plugin)} and cheap to ask for again: two
 * classes in the same plugin that both need panels see the same registry, which
 * is what makes releasing one plugin release exactly its own.
 *
 * <pre>{@code
 * PluginPanels panels = Panels.of(this);
 *
 * // later, wherever a command or button decides to
 * panels.session(player).ifPresent(PanelSession::save);
 * }</pre>
 *
 * <h2>Threads</h2>
 * {@link #session(Player)} is a read off the player's open window and is safe
 * from any thread. {@link #close(Player)} ends a screen, so it belongs on the
 * thread that owns the player.
 *
 * @since 1.50.0
 */
public final class PluginPanels {

    private final PanelRuntime runtime;

    @ApiStatus.Internal
    PluginPanels(@NotNull PanelRuntime runtime) {
        this.runtime = runtime;
    }

    /** Whose panels these are. */
    public @NotNull Plugin plugin() {
        return runtime.plugin();
    }

    /**
     * The panel a player has open, if it is one of this plugin's.
     *
     * <p>A plugin asking about somebody else's screen should use
     * {@link Panels#session(Player)} instead.
     *
     * @param viewer the player
     * @return the session, or empty
     */
    public @NotNull Optional<PanelSession> session(@NotNull Player viewer) {
        PanelSession session = PanelRuntime.publicSessionOf(viewer);
        return session != null && session.owner().equals(runtime.plugin())
                ? Optional.of(session)
                : Optional.empty();
    }

    /**
     * Closes whatever panel a player has open, if it is one of this plugin's.
     *
     * <p>The working copy is discarded, exactly as {@link PanelSession#cancel()}
     * would: closing a panel is not a way to save it by accident.
     *
     * @param viewer the player
     */
    public void close(@NotNull Player viewer) {
        session(viewer).ifPresent(PanelSession::cancel);
    }

    /** How many panels of this plugin are on screen, for diagnostics. */
    public int open() {
        return runtime.open();
    }
}
