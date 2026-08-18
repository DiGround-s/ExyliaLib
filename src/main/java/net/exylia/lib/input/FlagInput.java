package net.exylia.lib.input;

import org.bukkit.entity.Player;

/**
 * A non-destructive yes-or-no input suitable for settings and switches.
 *
 * @since 1.31.0
 */
public final class FlagInput extends InputRequest<Boolean, FlagInput> {

    FlagInput(String pluginName, Player player, String prompt) {
        super(pluginName, player, prompt, InputParser.flag());
    }
}
