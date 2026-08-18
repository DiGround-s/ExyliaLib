package net.exylia.lib.input.internal;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Routes Bedrock players to native Floodgate forms.
 *
 * <p>The transport contains no Floodgate or Cumulus descriptor. Detection is
 * reflective in {@link Bedrocks}, and form construction is reflective in
 * {@link BedrockForms}, so discovering this built-in cannot stop ExyliaLib from
 * loading on a server where neither bridge library exists.
 */
public final class BedrockTransport implements Transport {

    private final Plugin plugin;

    /** Creates a transport whose validation re-shows run on the library task scheduler. */
    public BedrockTransport(@NotNull Plugin plugin) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean show(@NotNull InputSession session) {
        return Bedrocks.isBedrock(session.playerId())
                && Bedrocks.formsAvailable()
                && BedrockForms.show(plugin, session);
    }

    @Override
    public void close(@NotNull InputSession session) {
        BedrockForms.close(session);
    }

    @Override
    public @NotNull TransportKind kind() {
        return TransportKind.BEDROCK;
    }
}
