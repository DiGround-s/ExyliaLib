package net.exylia.lib.util.combat.internal;

import net.exylia.lib.util.combat.CombatStats;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * DeluxeCombat, through reflection.
 *
 * <p>The one provider that counts things: kills, deaths, streaks and points all
 * come from here, which is why {@code statsOf} is implemented and PvPManager's
 * is not.
 */
final class DeluxeCombatProvider implements CombatProvider {

    private static final String PLUGIN = "DeluxeCombat";

    /** What DeluxeCombat's own two-argument tag uses. */
    private static final int DEFAULT_TAG_SECONDS = 15;

    private final boolean present;
    private final Object api;

    private final Method isInCombat;
    private final Method remainingMillis;
    private final Method opponent;
    private final Method tag;
    private final Method untag;
    private final Method hasProtection;
    private final Method hasPvpEnabled;
    private final Method togglePvp;
    private final Method kills;
    private final Method deaths;
    private final Method streak;
    private final Method highestStreak;
    private final Method combatLogs;
    private final Method points;

    private DeluxeCombatProvider(Builder builder) {
        this.present = builder.present;
        this.api = builder.api;
        this.isInCombat = builder.isInCombat;
        this.remainingMillis = builder.remainingMillis;
        this.opponent = builder.opponent;
        this.tag = builder.tag;
        this.untag = builder.untag;
        this.hasProtection = builder.hasProtection;
        this.hasPvpEnabled = builder.hasPvpEnabled;
        this.togglePvp = builder.togglePvp;
        this.kills = builder.kills;
        this.deaths = builder.deaths;
        this.streak = builder.streak;
        this.highestStreak = builder.highestStreak;
        this.combatLogs = builder.combatLogs;
        this.points = builder.points;
    }

    static CombatProvider tryCreate() {
        Builder builder = new Builder();
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) {
                return new DeluxeCombatProvider(builder);
            }
            Class<?> apiClass = Class.forName("nl.marido.deluxecombat.api.DeluxeCombatAPI");
            builder.api = apiClass.getConstructor().newInstance();
            builder.isInCombat = apiClass.getMethod("isInCombat", Player.class);
            builder.remainingMillis = apiClass.getMethod("getRemainingCombatTimeMillis", Player.class);
            builder.opponent = apiClass.getMethod("getCurrentOpponent", Player.class);
            // The third argument is seconds, and the second is declared Object.
            builder.tag = apiClass.getMethod("tag", Player.class, Object.class, int.class);
            builder.untag = apiClass.getMethod("untag", Player.class);
            builder.hasProtection = apiClass.getMethod("hasProtection", Player.class);
            builder.hasPvpEnabled = apiClass.getMethod("hasPvPEnabled", Player.class);
            builder.togglePvp = apiClass.getMethod("togglePvP", Player.class, boolean.class);
            builder.kills = apiClass.getMethod("getKills", Player.class);
            builder.deaths = apiClass.getMethod("getDeaths", Player.class);
            builder.streak = apiClass.getMethod("getStreak", Player.class);
            builder.highestStreak = apiClass.getMethod("getHighestStreak", Player.class);
            builder.combatLogs = apiClass.getMethod("getCombatlogs", Player.class);
            builder.points = apiClass.getMethod("getPoints", Player.class);
            builder.present = true;
            return new DeluxeCombatProvider(builder);
        } catch (Throwable broken) {
            Logger.getLogger("ExyliaLib").warning(
                    "DeluxeCombat is installed but its API could not be reached, so combat"
                            + " falls back to nothing: " + broken);
            return new DeluxeCombatProvider(new Builder());
        }
    }

    @Override
    public boolean enabled() {
        return present;
    }

    @Override
    public String name() {
        return PLUGIN;
    }

    @Override
    public boolean isTagged(Player player) {
        return Boolean.TRUE.equals(call(isInCombat, player));
    }

    @Override
    public Duration remaining(Player player) {
        // Millis rather than the seconds method: this drives an action bar, and
        // a countdown rounded to a whole second sits still and then jumps.
        return call(remainingMillis, player) instanceof Long millis && millis > 0
                ? Duration.ofMillis(millis)
                : Duration.ZERO;
    }

    @Override
    public Optional<Player> opponentOf(Player player) {
        return call(opponent, player) instanceof Player found
                ? Optional.of(found)
                : Optional.empty();
    }

    @Override
    public void tag(Player target, Player attacker, Duration duration) {
        int seconds = duration == null
                ? DEFAULT_TAG_SECONDS
                // Anything under a second still has to tag for one: asking for
                // half a second and getting none is worse than getting one.
                : Math.max(1, (int) duration.toSeconds());
        call(tag, target, attacker, seconds);
    }

    @Override
    public void untag(Player player) {
        call(untag, player);
    }

    @Override
    public boolean isProtected(Player player) {
        return Boolean.TRUE.equals(call(hasProtection, player));
    }

    @Override
    public boolean isPvpEnabled(Player player) {
        return !Boolean.FALSE.equals(call(hasPvpEnabled, player));
    }

    @Override
    public void setPvpEnabled(Player player, boolean enabled) {
        call(togglePvp, player, enabled);
    }

    /**
     * DeluxeCombat has no such question, so the answer is its parts.
     *
     * <p>The previous implementation left this as a {@code TODO} returning
     * {@code true}, which meant a protected player could be hit by anyone who
     * asked politely.
     */
    @Override
    public boolean canAttack(Player attacker, Player defender) {
        return isPvpEnabled(attacker)
                && isPvpEnabled(defender)
                && !isProtected(attacker)
                && !isProtected(defender);
    }

    @Override
    public Optional<CombatStats> statsOf(Player player) {
        if (!present) {
            return Optional.empty();
        }
        return Optional.of(new CombatStats(
                number(kills, player),
                number(deaths, player),
                number(streak, player),
                number(highestStreak, player),
                number(combatLogs, player),
                number(points, player)));
    }

    private int number(Method method, Player player) {
        return call(method, player) instanceof Number value ? value.intValue() : 0;
    }

    /**
     * Calls a method on the API, answering {@code null} when it fails.
     *
     * <p>Their bug must not become a cancelled damage event on our side; every
     * caller above reads {@code null} as "no answer".
     */
    private Object call(Method method, Object... args) {
        if (!present || method == null) {
            return null;
        }
        try {
            return method.invoke(api, args);
        } catch (Throwable broken) {
            return null;
        }
    }

    /** Holds the reflected members while they are resolved. */
    private static final class Builder {
        private boolean present;
        private Object api;
        private Method isInCombat;
        private Method remainingMillis;
        private Method opponent;
        private Method tag;
        private Method untag;
        private Method hasProtection;
        private Method hasPvpEnabled;
        private Method togglePvp;
        private Method kills;
        private Method deaths;
        private Method streak;
        private Method highestStreak;
        private Method combatLogs;
        private Method points;
    }
}
