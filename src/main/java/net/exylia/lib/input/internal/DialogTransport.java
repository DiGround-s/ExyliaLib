package net.exylia.lib.input.internal;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Selects Minecraft's native dialog transport without linking its optional
 * packet implementation into the always-loaded input runtime.
 *
 * <p>Every PacketEvents descriptor is confined to {@link DialogPackets}. This
 * class therefore remains loadable when PacketEvents is absent; otherwise the
 * JVM could resolve a PacketEvents method descriptor while discovering built-in
 * transports and fail before chat or menu fallback was attempted.
 */
public final class DialogTransport implements Transport {

    /**
     * Whether the server owner allows dialogs at all.
     *
     * <p>Read from {@code input.yml}. A setting that is parsed and then ignored
     * is worse than no setting: an owner turns it off, watches nothing change,
     * and reports the plugin as broken. Checked before anything else so
     * {@code prefer-dialogs: false} really does send every question to chat and
     * menus.
     */
    private static volatile boolean enabled = true;

    private final Plugin plugin;

    /** Creates the transport owned by the library plugin. */
    public DialogTransport(@NotNull Plugin plugin) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * Turns the dialog transport on or off for the whole server.
     *
     * @param allowed whether dialogs may be used
     */
    @ApiStatus.Internal
    public static void enabled(boolean allowed) {
        enabled = allowed;
    }

    /**
     * Whether dialogs are allowed.
     *
     * @return {@code true} when the owner has not turned them off
     */
    @ApiStatus.Internal
    public static boolean enabled() {
        return enabled;
    }

    @Override
    public boolean show(@NotNull InputSession session) {
        return enabled && DialogPackets.available() && DialogPackets.show(plugin, session);
    }

    @Override
    public void close(@NotNull InputSession session) {
        if (DialogPackets.available()) {
            DialogPackets.close(session);
        }
    }

    @Override
    public @NotNull TransportKind kind() {
        return TransportKind.DIALOG;
    }
}
