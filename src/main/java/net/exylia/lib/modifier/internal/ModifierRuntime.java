package net.exylia.lib.modifier.internal;

import net.exylia.lib.modifier.ModifierProvider;
import net.exylia.lib.modifier.ModifierSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Who provides modifiers and which sources are listed, per plugin, released with it. */
public final class ModifierRuntime {

    private record Provided(String plugin, ModifierProvider provider) {
    }

    private record Listed(String plugin, ModifierSource source) {
    }

    private static final Logger LOG = Logger.getLogger("ExyliaLib");
    private static final List<Provided> PROVIDERS = new CopyOnWriteArrayList<>();
    private static final List<Listed> SOURCES = new CopyOnWriteArrayList<>();

    private ModifierRuntime() {
    }

    public static void register(@NotNull String plugin, @NotNull ModifierProvider provider) {
        PROVIDERS.add(new Provided(plugin, provider));
    }

    public static void unregister(@NotNull ModifierProvider provider) {
        PROVIDERS.removeIf(provided -> provided.provider() == provider);
    }

    /** Lists a source; the same plugin listing the same id again replaces its own entry in place. */
    public static synchronized void source(@NotNull String plugin, @NotNull ModifierSource source) {
        for (int i = 0; i < SOURCES.size(); i++) {
            Listed listed = SOURCES.get(i);
            if (listed.plugin().equals(plugin) && listed.source().id().equals(source.id())) {
                SOURCES.set(i, new Listed(plugin, source));
                return;
            }
        }
        SOURCES.add(new Listed(plugin, source));
    }

    /** Every listed source in the order they were listed; the first plugin to list an id wins. */
    public static @NotNull List<ModifierSource> sources() {
        Set<String> seen = new HashSet<>();
        List<ModifierSource> out = new ArrayList<>();
        for (Listed listed : SOURCES) {
            if (seen.add(listed.source().id())) out.add(listed.source());
        }
        return List.copyOf(out);
    }

    /** Every provider's answer multiplied, the permission provider's included. */
    public static double factor(@NotNull UUID player, @NotNull String type, @NotNull String source,
                                @Nullable String scope) {
        double factor = PermissionModifiers.factor(player, type, source);
        for (Provided provided : PROVIDERS) {
            double one;
            try {
                one = provided.provider().factor(player, type, source, scope);
            } catch (RuntimeException broken) {
                LOG.log(Level.WARNING, "Modifier provider of " + provided.plugin() + " failed; counted as 1.", broken);
                continue;
            }
            if (Double.isNaN(one)) continue;
            factor *= Math.max(0.0, one);
        }
        return factor;
    }

    public static void forget(@NotNull UUID player) {
        PermissionModifiers.forget(player);
    }

    public static void reload() {
        PermissionModifiers.forgetAll();
    }

    public static void release(@NotNull String plugin) {
        PROVIDERS.removeIf(provided -> provided.plugin().equals(plugin));
        SOURCES.removeIf(listed -> listed.plugin().equals(plugin));
    }
}
