package net.exylia.lib.practicebot;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Optional;

/**
 * The way in.
 *
 * <pre>{@code
 * PracticeBots.get().ifPresent(bots -> bots.spawn(spec).thenAccept(...));
 * }</pre>
 *
 * <p>Empty whenever the bot plugin is not installed, is disabled, or has not
 * finished enabling. Every caller has to handle that, which is the point: an
 * integration with a soft dependency is a feature that may simply not be there,
 * not an error to report.
 *
 * <h2>Why this lives in the library</h2>
 * Because a contract between two plugins has to be one class, and a class is
 * only one class if one classloader owns it. Exylia's plugins are loader
 * plugins: the server loads a small loader jar that then loads the real plugin
 * into a classloader of its own. Bukkit's soft-dependency delegation reaches the
 * loader's classloader and stops there, so an interface shipped inside one
 * plugin is simply not visible to another, no matter what either plugin.yml
 * says - and a copy shipped in both is two different classes, which would make
 * every service lookup miss.
 *
 * <p>The library is the one jar every plugin already sees, the same way
 * {@link net.exylia.lib.session.Sessions} and {@link net.exylia.lib.clan.Clans}
 * are shared contracts rather than anybody's internals.
 *
 * @since 1.73.0
 */
public final class PracticeBots {

    private PracticeBots() {}

    /**
     * The running bot service, if there is one.
     *
     * @return the service, or empty when the bot plugin is absent or not ready
     */
    public static Optional<PracticeBotService> get() {
        RegisteredServiceProvider<PracticeBotService> registration =
                Bukkit.getServicesManager().getRegistration(PracticeBotService.class);
        return registration == null ? Optional.empty() : Optional.ofNullable(registration.getProvider());
    }

    /** Whether bots can be spawned right now. */
    public static boolean available() {
        return get().isPresent();
    }
}
