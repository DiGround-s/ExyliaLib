package net.exylia.lib.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * God mode and vanish as other plugins keep them: EssentialsX and CMI.
 *
 * <p>Neither plugin is on the compile classpath. Each is reached by reflection
 * through its own plugin instance, so a server without them pays a map lookup
 * and nothing else, and a call that cannot be made answers {@code false}
 * instead of throwing. Where neither plugin is present, god mode falls back to
 * Bukkit's {@code invulnerable} flag and vanish to the {@code vanished}
 * metadata that SuperVanish, PremiumVanish, EssentialsX and CMI all set.
 *
 * <p>Call it on the player's own thread: the plugins it reaches change the
 * player's state.
 *
 * @since 1.332.0
 */
public final class PlayerStates {

    private static final String ESSENTIALS = "Essentials";
    private static final String CMI = "CMI";
    private static final String VANISHED_METADATA = "vanished";
    private static final Map<String, Optional<Method>> METHODS = new ConcurrentHashMap<>();

    private PlayerStates() {
    }

    /**
     * Whether the player is in god mode, as EssentialsX or CMI sees it, or
     * Bukkit-invulnerable.
     *
     * @param player the player
     * @return whether nothing can hurt them
     */
    public static boolean isGod(@NotNull Player player) {
        return player.isInvulnerable() || flag(essentialsUser(player), "isGodModeEnabled") || cmiGod(player);
    }

    /**
     * Turns god mode off in EssentialsX, CMI and Bukkit.
     *
     * @param player the player
     * @return whether god mode was on anywhere
     */
    public static boolean disableGod(@NotNull Player player) {
        boolean changed = false;
        Object essentials = essentialsUser(player);
        if (flag(essentials, "isGodModeEnabled")) {
            changed = set(essentials, "setGodModeEnabled");
        }
        if (cmiGod(player)) {
            changed |= disableCmiGod(player);
        }
        if (player.isInvulnerable()) {
            player.setInvulnerable(false);
            changed = true;
        }
        return changed;
    }

    /**
     * Whether any vanish plugin hides the player.
     *
     * @param player the player
     * @return whether they are vanished
     */
    public static boolean isVanished(@NotNull Player player) {
        for (MetadataValue value : player.getMetadata(VANISHED_METADATA)) {
            if (value.asBoolean()) {
                return true;
            }
        }
        return flag(essentialsUser(player), "isVanished") || flag(cmiUser(player), "isVanished");
    }

    /**
     * Makes the player visible again in EssentialsX and CMI. Other vanish
     * plugins have no public switch and are left alone.
     *
     * @param player the player
     * @return whether a vanish was turned off
     */
    public static boolean disableVanish(@NotNull Player player) {
        boolean changed = false;
        Object essentials = essentialsUser(player);
        if (flag(essentials, "isVanished")) {
            changed = set(essentials, "setVanished");
        }
        Object cmi = cmiUser(player);
        if (flag(cmi, "isVanished")) {
            changed |= set(cmi, "setVanished");
        }
        return changed;
    }

    private static Object essentialsUser(Player player) {
        Plugin essentials = enabled(ESSENTIALS);
        return essentials == null ? null : call(essentials, "getUser", player);
    }

    private static Object cmiUser(Player player) {
        Plugin cmi = enabled(CMI);
        return cmi == null ? null : call(call(cmi, "getPlayerManager"), "getUser", player);
    }

    private static boolean cmiGod(Player player) {
        Plugin cmi = enabled(CMI);
        if (cmi == null) {
            return false;
        }
        Object user = cmiUser(player);
        return flag(user, "isGod") || flag(user, "isTgod") || flag(call(cmi, "getNMS"), "getGodMode", player);
    }

    private static boolean disableCmiGod(Player player) {
        Plugin cmi = enabled(CMI);
        if (cmi == null) {
            return false;
        }
        boolean changed = false;
        Object user = cmiUser(player);
        if (flag(user, "isTgod")) {
            changed = invoke(user, "setTgod", new Class<?>[]{long.class}, 0L);
        }
        Object nms = call(cmi, "getNMS");
        if (nms != null) {
            changed |= invoke(nms, "changeGodMode", new Class<?>[]{Player.class, boolean.class}, player, false);
        }
        return changed;
    }

    private static boolean invoke(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method method = target.getClass().getMethod(name, types);
            method.setAccessible(true);
            method.invoke(target, args);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    private static Plugin enabled(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled() ? plugin : null;
    }

    private static boolean flag(Object target, String name, Object... args) {
        return Boolean.TRUE.equals(call(target, name, args));
    }

    private static boolean set(Object target, String name) {
        Method method = method(target, name, 1);
        if (method == null) {
            return false;
        }
        try {
            method.invoke(target, false);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    private static Object call(Object target, String name, Object... args) {
        Method method = method(target, name, args.length);
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    private static Method method(Object target, String name, int parameters) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        return METHODS.computeIfAbsent(type.getName() + '#' + name + '/' + parameters, ignored -> {
            for (Method candidate : type.getMethods()) {
                if (candidate.getName().equals(name) && candidate.getParameterCount() == parameters
                        && (parameters == 0 || accepts(candidate))) {
                    candidate.setAccessible(true);
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }).orElse(null);
    }

    private static boolean accepts(Method method) {
        Class<?> parameter = method.getParameterTypes()[0];
        return parameter == boolean.class || parameter == Boolean.class || parameter.isAssignableFrom(Player.class);
    }
}
