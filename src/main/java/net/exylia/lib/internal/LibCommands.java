package net.exylia.lib.internal;

import net.exylia.lib.ExyliaLib;
import net.exylia.lib.command.lamp.Suggestions;
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
        Lamp<BukkitCommandActor> lamp = BukkitLamp.builder(plugin)
                .suggestionProviders(providers -> providers.addProviderFactory(Suggestions.filtering()))
                .build();
        lamp.register(new ReloadCommand(plugin));
        LibrarySettings.PlayerSettings.Command settings = LibrarySettings.get().settings().command();
        if (settings.enabled() && settings.name() != null && !settings.name().isBlank()) {
            java.util.List<String> names = new java.util.ArrayList<>();
            names.add(settings.name());
            if (settings.aliases() != null) names.addAll(settings.aliases());
            lamp.register(revxrsal.commands.orphan.Orphans.path(names.toArray(String[]::new))
                    .handler(new SettingsCommand()));
        }
    }
}
