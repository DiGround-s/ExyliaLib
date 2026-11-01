package net.exylia.lib.input.internal;

import net.exylia.lib.platform.RequiresServer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.AnvilView;

/** The anvil through {@code MenuType}'s builder, on servers that have one (1.21.4). */
@RequiresServer("1.21.4")
final class ModernAnvil {

    private ModernAnvil() {
    }

    /**
     * Builds the view without opening it.
     *
     * <p>{@code checkReachable(false)} is required: without it the server
     * validates that the player stands next to a real anvil block and closes
     * the view immediately, which would make search silently do nothing.
     */
    static AnvilView build(Player player, Component title) {
        return MenuType.ANVIL.builder()
                .title(title)
                .checkReachable(false)
                .build(player);
    }
}
