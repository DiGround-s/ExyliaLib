package net.exylia.lib.internal;

import net.exylia.lib.ExyliaLib;
import revxrsal.commands.Lamp;
import revxrsal.commands.bukkit.BukkitLamp;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;

/**
 * Registers the library's commands.
 *
 * <p>Lamp is confined to this class and {@link ReloadCommand}: if the
 * framework were ever swapped, nothing outside this package would change.
 */
public final class LibCommands {

    private LibCommands() {
        throw new AssertionError("No instances.");
    }

    /**
     * Registers every command the library owns.
     *
     * @param plugin the running library
     */
    public static void register(ExyliaLib plugin) {
        Lamp<BukkitCommandActor> lamp = BukkitLamp.builder(plugin).build();
        lamp.register(new ReloadCommand(plugin));
    }
}
