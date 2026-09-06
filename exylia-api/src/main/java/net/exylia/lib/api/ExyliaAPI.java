package net.exylia.lib.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The way into every Exylia plugin's API.
 *
 * <pre>{@code
 * ExyliaAPI.get(ClansService.class)
 *          .flatMap(clans -> clans.clanOf(player.getUniqueId()))
 *          .ifPresent(clan -> getLogger().info(player.getName() + " is in " + clan.name()));
 * }</pre>
 *
 * <p>Every service is empty whenever its plugin is not installed, is disabled,
 * or has not finished enabling. Callers have to handle that, which is the
 * point: an integration with a soft dependency is a feature that may simply not
 * be there, not an error to report.
 *
 * <h2>Getting hold of this</h2>
 * Add the API to your build and let the server provide it at runtime:
 *
 * <pre>{@code
 * repositories {
 *     maven { url 'https://jitpack.io' }
 * }
 * dependencies {
 *     compileOnly 'com.github.DiGround-s.ExyliaLib:exylia-api:1.0.0'
 * }
 * }</pre>
 *
 * <p>and declare the library in your {@code plugin.yml}, so Bukkit enables it
 * before you and your classloader is allowed to see its classes:
 *
 * <pre>{@code
 * depend: [ ExyliaLib ]
 * }</pre>
 *
 * <h2>Why the contract lives in the library</h2>
 * Because a contract between two plugins has to be one class, and a class is
 * only one class if one classloader owns it. Exylia's plugins are loader
 * plugins: the server loads a small loader jar that then loads the real plugin
 * into a classloader of its own. Bukkit's soft-dependency delegation reaches the
 * loader's classloader and stops there, so an interface shipped inside one
 * plugin is simply not visible to another, no matter what either
 * {@code plugin.yml} says — and a copy shipped in both is two different classes,
 * which would make every service lookup miss.
 *
 * <p>ExyliaLib is the one jar every Exylia plugin already sees and every
 * third-party plugin can depend on, so the contract lives there and nowhere
 * else. The {@code exylia-api} artifact you compile against carries the same
 * classes ExyliaLib ships, which is why it must be {@code compileOnly}: bundling
 * a second copy into your own jar would recreate exactly the split it exists to
 * avoid.
 *
 * <h2>Timing</h2>
 * Services register when their plugin enables. Look one up in your own
 * {@code onEnable} and you may be asking before the plugin that provides it has
 * started. Resolve services when you use them, or from
 * {@link org.bukkit.event.server.ServerLoadEvent}, rather than caching one at
 * enable time.
 *
 * @since 1.0.0
 */
public final class ExyliaAPI {

    private ExyliaAPI() {
    }

    /**
     * Looks up an Exylia service.
     *
     * @param service the service interface, for example {@code ClansService.class}
     * @param <T>     the service type
     * @return the running implementation, or empty when the plugin behind it is
     *         absent or not enabled yet
     */
    @NotNull
    public static <T> Optional<T> get(@NotNull Class<T> service) {
        RegisteredServiceProvider<T> registration =
                Bukkit.getServicesManager().getRegistration(service);
        return registration == null ? Optional.empty() : Optional.of(registration.getProvider());
    }

    /**
     * Looks up an Exylia service, failing loudly when it is not there.
     *
     * <p>For plugins that declare a hard {@code depend} on the plugin behind the
     * service and genuinely cannot work without it. Anything softer should use
     * {@link #get(Class)} and degrade.
     *
     * @param service the service interface
     * @param <T>     the service type
     * @return the running implementation
     * @throws IllegalStateException when no implementation is registered
     */
    @NotNull
    public static <T> T require(@NotNull Class<T> service) {
        return get(service).orElseThrow(() -> new IllegalStateException(
                service.getSimpleName() + " is not available: the plugin providing it is not "
                        + "installed, is disabled, or has not enabled yet."));
    }

    /**
     * Whether a service is available right now.
     *
     * @param service the service interface
     * @return {@code true} when an implementation is registered
     */
    public static boolean isAvailable(@NotNull Class<?> service) {
        return Bukkit.getServicesManager().getRegistration(service) != null;
    }
}
