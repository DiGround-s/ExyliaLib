package net.exylia.lib.input;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * An explicit confirmation, kept distinct from a setting flag so transports can
 * render destructive actions with the prominence they require.
 *
 * @since 1.31.0
 */
public final class ConfirmInput extends InputRequest<Boolean, ConfirmInput> {

    private String confirmLabel = "Confirm";
    private String denyLabel = "Cancel";
    private boolean dangerous;

    ConfirmInput(String pluginName, Player player, String prompt) {
        super(pluginName, player, prompt, InputParser.flag());
    }

    /** Sets the affirmative button text. */
    public @NotNull ConfirmInput confirmLabel(@NotNull String label) {
        this.confirmLabel = Inputs.requireText(label, "confirm label");
        return this;
    }

    /** Sets the negative button text. */
    public @NotNull ConfirmInput denyLabel(@NotNull String label) {
        this.denyLabel = Inputs.requireText(label, "deny label");
        return this;
    }

    /** Marks this as destructive so a capable transport can use danger styling. */
    public @NotNull ConfirmInput dangerous() {
        this.dangerous = true;
        return this;
    }

    /** Affirmative label consumed by transports. */
    @ApiStatus.Internal
    public @NotNull String confirmLabel() {
        return confirmLabel;
    }

    /** Negative label consumed by transports. */
    @ApiStatus.Internal
    public @NotNull String denyLabel() {
        return denyLabel;
    }

    /** Whether transports should render destructive emphasis. */
    @ApiStatus.Internal
    public boolean isDangerous() {
        return dangerous;
    }
}
