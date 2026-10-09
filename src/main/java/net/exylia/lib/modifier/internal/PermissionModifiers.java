package net.exylia.lib.modifier.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * The built-in provider: {@code exylia.modifier.<type>.<source>.<factor>}.
 *
 * <p>A player's permissions are read once and kept for {@link #TTL}, dropped
 * on join, quit and {@code /exylialib reload}: a rank bought mid-session is
 * honoured within seconds, and no payout ever walks a permission list.
 */
public final class PermissionModifiers {

    public static final String PREFIX = "exylia.modifier.";
    static final Duration TTL = Duration.ofSeconds(10);

    record Grant(String type, String source, double factor) {
    }

    private static final Function<UUID, Collection<String>> LIVE = PermissionModifiers::live;
    private static volatile Function<UUID, Collection<String>> reader = LIVE;

    private static final Cache<UUID, List<Grant>> GRANTS = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .maximumSize(10_000)
            .build();

    private PermissionModifiers() {
    }

    /** The highest factor the player's permissions grant for this type and source, or for every source of it. */
    public static double factor(@NotNull UUID player, @NotNull String type, @NotNull String source) {
        List<Grant> grants = GRANTS.get(player, PermissionModifiers::read);
        if (grants == null) return 1.0;
        double best = Double.NaN;
        for (Grant grant : grants) {
            if (!grant.type().equals(type)) continue;
            if (!grant.source().equals(source) && !grant.source().equals("*")) continue;
            if (Double.isNaN(best) || grant.factor() > best) best = grant.factor();
        }
        return Double.isNaN(best) ? 1.0 : best;
    }

    public static void forget(@NotNull UUID player) {
        GRANTS.invalidate(player);
    }

    public static void forgetAll() {
        GRANTS.invalidateAll();
    }

    private static List<Grant> read(UUID player) {
        Collection<String> nodes;
        try {
            nodes = reader.apply(player);
        } catch (RuntimeException raced) {
            // A permission list changing under a reader on another thread: answer
            // nothing this once, and keep nothing (null is not cached) so the
            // next call reads again.
            return null;
        }
        List<Grant> grants = new ArrayList<>(2);
        for (String node : nodes) {
            Grant grant = parse(node);
            if (grant != null) grants.add(grant);
        }
        return List.copyOf(grants);
    }

    /** {@code exylia.modifier.money.*.1.5} → money, *, 1.5; anything else {@code null}. */
    static Grant parse(String node) {
        String lower = node.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(PREFIX)) return null;
        String rest = lower.substring(PREFIX.length());
        int typeEnd = rest.indexOf('.');
        if (typeEnd <= 0) return null;
        int sourceEnd = rest.indexOf('.', typeEnd + 1);
        if (sourceEnd <= typeEnd + 1) return null;
        try {
            double factor = Double.parseDouble(rest.substring(sourceEnd + 1));
            if (!Double.isFinite(factor) || factor < 0) return null;
            return new Grant(rest.substring(0, typeEnd), rest.substring(typeEnd + 1, sourceEnd), factor);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static Collection<String> live(UUID id) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return List.of();
        List<String> nodes = new ArrayList<>();
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (info.getValue() && info.getPermission().startsWith(PREFIX)) nodes.add(info.getPermission());
        }
        return nodes;
    }

    /** Test seam: where a player's permission nodes come from. */
    static void setReader(Function<UUID, Collection<String>> replacement) {
        reader = replacement;
        forgetAll();
    }

    static void resetReader() {
        reader = LIVE;
        forgetAll();
    }
}
