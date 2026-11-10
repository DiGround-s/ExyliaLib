package net.exylia.lib.leaderboard;

import net.exylia.lib.economy.Economy;
import net.exylia.lib.format.Amounts;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.playtime.Playtime;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * What a leaderboard ranks by: a vanilla statistic, a balance, playtime or any
 * placeholder.
 *
 * <pre>{@code
 * LeaderboardSource.statistic(Statistic.PLAYER_KILLS);
 * LeaderboardSource.statistic(Statistic.MINE_BLOCK, Material.DIAMOND_ORE);
 * LeaderboardSource.balance();                 // economy.yml's default currency
 * LeaderboardSource.playtime();                // milliseconds, from Playtime
 * LeaderboardSource.placeholder("%player_level%").perServer();
 * }</pre>
 *
 * <h2>One value everywhere, or one per server</h2>
 * A statistic lives in each server's world folder, so every server keeps its
 * own row and the board adds them up: kills on the whole network. A balance,
 * playtime and a placeholder are taken to read the same on every server, so the
 * server a player is on writes their one row. {@link #perServer()} makes any
 * source the first kind: a Vault balance each server keeps apart, a placeholder
 * from a plugin with no shared database.
 *
 * <h2>Offline players</h2>
 * A statistic, a balance and playtime can be read for somebody offline, so a
 * board tracked for the first time is filled with everybody who ever played
 * here. A placeholder needs the player online: it ranks players as they join.
 *
 * @since 1.563.0
 */
public final class LeaderboardSource {

    private static final Pattern FORMATTING = Pattern.compile("(?i)[&§][0-9a-fk-orx]|<[^>]*>|[\\s,_]");

    private enum Kind { STATISTIC, BALANCE, PLAYTIME, PLACEHOLDER }

    private final Kind kind;
    private final @Nullable Statistic statistic;
    private final @Nullable Material material;
    private final @Nullable EntityType entity;
    private final String text;
    private final boolean perServer;

    private LeaderboardSource(Kind kind, @Nullable Statistic statistic, @Nullable Material material,
                              @Nullable EntityType entity, String text, boolean perServer) {
        this.kind = kind;
        this.statistic = statistic;
        this.material = material;
        this.entity = entity;
        this.text = text;
        this.perServer = perServer;
    }

    /**
     * A statistic that takes no block, item or entity.
     *
     * @throws IllegalArgumentException when it needs one
     */
    public static @NotNull LeaderboardSource statistic(@NotNull Statistic statistic) {
        if (statistic.getType() != Statistic.Type.UNTYPED) {
            throw new IllegalArgumentException(statistic + " counts per " + statistic.getType().name().toLowerCase()
                    + ": name one with statistic(" + statistic + ", ...)");
        }
        return new LeaderboardSource(Kind.STATISTIC, statistic, null, null, "", true);
    }

    /**
     * A statistic of one block or item: blocks mined, items crafted, used, broken, picked up or dropped.
     *
     * @throws IllegalArgumentException when the statistic does not count blocks or items
     */
    public static @NotNull LeaderboardSource statistic(@NotNull Statistic statistic, @NotNull Material material) {
        Statistic.Type type = statistic.getType();
        if (type != Statistic.Type.BLOCK && type != Statistic.Type.ITEM) {
            throw new IllegalArgumentException(statistic + " does not count blocks or items");
        }
        return new LeaderboardSource(Kind.STATISTIC, statistic, material, null, "", true);
    }

    /**
     * A statistic of one mob: mobs killed, or killed by.
     *
     * @throws IllegalArgumentException when the statistic does not count entities
     */
    public static @NotNull LeaderboardSource statistic(@NotNull Statistic statistic, @NotNull EntityType entity) {
        if (statistic.getType() != Statistic.Type.ENTITY) {
            throw new IllegalArgumentException(statistic + " does not count entities");
        }
        return new LeaderboardSource(Kind.STATISTIC, statistic, null, entity, "", true);
    }

    /** The balance in {@code economy.yml}'s default currency. */
    public static @NotNull LeaderboardSource balance() {
        return balance("");
    }

    /** The balance in one currency; empty names the default one. */
    public static @NotNull LeaderboardSource balance(@NotNull String currency) {
        return new LeaderboardSource(Kind.BALANCE, null, null, null, currency, false);
    }

    /** Time actually played on the network, in milliseconds, from {@link Playtime}. */
    public static @NotNull LeaderboardSource playtime() {
        return new LeaderboardSource(Kind.PLAYTIME, null, null, null, "", false);
    }

    /**
     * Any placeholder, ExyliaLib's or PlaceholderAPI's, read for players online.
     * Colours, spaces and thousands separators are ignored and {@code 1.5K}
     * reads as 1500; a value that is not a number is not sampled.
     *
     * @param placeholder the text to resolve, such as {@code %player_level%}
     */
    public static @NotNull LeaderboardSource placeholder(@NotNull String placeholder) {
        return new LeaderboardSource(Kind.PLACEHOLDER, null, null, null, placeholder, false);
    }

    /** This source, read apart on each server and added up on the board. */
    public @NotNull LeaderboardSource perServer() {
        return new LeaderboardSource(kind, statistic, material, entity, text, true);
    }

    /** Whether each server keeps its own value and the board adds them up. */
    public boolean isPerServer() {
        return perServer;
    }

    /** Whether offline players can be read, so a new board starts full. */
    public boolean seedsOffline() {
        return kind != Kind.PLACEHOLDER;
    }

    // ------------------------------------------------------------------
    // Reading, driven by Leaderboards
    // ------------------------------------------------------------------

    /** The value of a player online here, on that player's own thread. */
    OptionalDouble read(@NotNull Player player, @NotNull Plugin host) {
        try {
            return switch (kind) {
                case STATISTIC -> OptionalDouble.of(statisticOf(player));
                case BALANCE -> OptionalDouble.of(Economy.of(text).balance(player.getUniqueId()).doubleValue());
                case PLAYTIME -> {
                    long played = Playtime.of(host).online(player.getUniqueId());
                    yield played < 0 ? OptionalDouble.empty() : OptionalDouble.of(played);
                }
                case PLACEHOLDER -> number(text, Placeholders.apply(text, player));
            };
        } catch (RuntimeException unreadable) {
            return OptionalDouble.empty();
        }
    }

    /** Whether an offline read must run on the server thread: statistics read the server's own stat files. */
    boolean seedsOnServerThread() {
        return kind == Kind.STATISTIC;
    }

    /** The value of somebody offline, for seeding a new board; empty for nothing worth a row. */
    CompletableFuture<OptionalDouble> readOffline(@NotNull OfflinePlayer player, @NotNull Plugin host) {
        try {
            return switch (kind) {
                case STATISTIC -> CompletableFuture.completedFuture(OptionalDouble.of(statisticOf(player)));
                case BALANCE -> Economy.of(text).balanceLater(player.getUniqueId())
                        .thenApply(amount -> OptionalDouble.of(amount.doubleValue()));
                case PLAYTIME -> Playtime.of(host).total(player.getUniqueId())
                        .thenApply(played -> OptionalDouble.of(played));
                case PLACEHOLDER -> CompletableFuture.completedFuture(OptionalDouble.empty());
            };
        } catch (RuntimeException unreadable) {
            return CompletableFuture.completedFuture(OptionalDouble.empty());
        }
    }

    private int statisticOf(OfflinePlayer player) {
        Statistic stat = Objects.requireNonNull(statistic);
        if (material != null) return player.getStatistic(stat, material);
        if (entity != null) return player.getStatistic(stat, entity);
        return player.getStatistic(stat);
    }

    /** A resolved placeholder as a number; empty when it was not resolved or is not one. */
    static OptionalDouble number(String raw, String resolved) {
        if (resolved.equals(raw)) return OptionalDouble.empty();
        String bare = FORMATTING.matcher(resolved).replaceAll("");
        try {
            return OptionalDouble.of(Double.parseDouble(bare));
        } catch (NumberFormatException notPlain) {
            return Amounts.parseSigned(bare).map(BigDecimal::doubleValue)
                    .map(OptionalDouble::of).orElse(OptionalDouble.empty());
        }
    }

    @Override
    public String toString() {
        String what = switch (kind) {
            case STATISTIC -> "statistic " + statistic + (material != null ? ":" + material : entity != null ? ":" + entity : "");
            case BALANCE -> "balance " + (text.isEmpty() ? "(default)" : text);
            case PLAYTIME -> "playtime";
            case PLACEHOLDER -> "placeholder " + text;
        };
        return perServer && kind != Kind.STATISTIC ? what + " per server" : what;
    }
}
