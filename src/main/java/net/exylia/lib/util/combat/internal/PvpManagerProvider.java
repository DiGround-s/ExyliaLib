package net.exylia.lib.util.combat.internal;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * PvPManager, through reflection.
 *
 * <p>Reflection rather than a compile-time dependency, for the same reason the
 * clan module uses it: this library loads on servers that have none of these
 * plugins, and naming a type it cannot resolve would fail the class before the
 * check inside it ever ran.
 *
 * <p>PvPManager counts nothing about a player, so {@code statsOf} is left at
 * its empty default. The previous implementation returned a record full of
 * zeroes instead, which made a scoreboard show "0 kills" for a player the
 * plugin had simply never been asked about.
 */
final class PvpManagerProvider implements CombatProvider {

    private static final String PLUGIN = "PvPManager";

    private final boolean present;
    private final Object playerManager;

    /** {@code CombatPlayer.get(Player)}. */
    private final Method combatPlayerOf;
    private final Method isInCombat;
    private final Method tagTimeLeft;
    private final Method enemy;
    private final Method enemyPlayer;
    private final Method tag;
    private final Method tagFor;
    private final Method untag;
    private final Method isNewbie;
    private final Method hasRespawnProtection;
    private final Method hasPvpEnabled;
    private final Method setPvp;
    private final Method canAttack;

    /** {@code UntagReason.PLUGIN_API}. */
    private final Object untagReason;

    private PvpManagerProvider(Builder builder) {
        this.present = builder.present;
        this.playerManager = builder.playerManager;
        this.combatPlayerOf = builder.combatPlayerOf;
        this.isInCombat = builder.isInCombat;
        this.tagTimeLeft = builder.tagTimeLeft;
        this.enemy = builder.enemy;
        this.enemyPlayer = builder.enemyPlayer;
        this.tag = builder.tag;
        this.tagFor = builder.tagFor;
        this.untag = builder.untag;
        this.isNewbie = builder.isNewbie;
        this.hasRespawnProtection = builder.hasRespawnProtection;
        this.hasPvpEnabled = builder.hasPvpEnabled;
        this.setPvp = builder.setPvp;
        this.canAttack = builder.canAttack;
        this.untagReason = builder.untagReason;
    }

    static CombatProvider tryCreate() {
        Builder builder = new Builder();
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) {
                return new PvpManagerProvider(builder);
            }
            Object plugin = Bukkit.getPluginManager().getPlugin(PLUGIN);
            Class<?> pluginClass = Class.forName("me.chancesd.pvpmanager.PvPManager");
            Class<?> combatPlayer = Class.forName("me.chancesd.pvpmanager.player.CombatPlayer");
            Class<?> reasons = Class.forName("me.chancesd.pvpmanager.player.UntagReason");
            Class<?> managerClass = Class.forName("me.chancesd.pvpmanager.manager.PlayerManager");

            builder.playerManager = pluginClass.getMethod("getPlayerManager").invoke(plugin);
            builder.combatPlayerOf = combatPlayer.getMethod("get", Player.class);
            builder.isInCombat = combatPlayer.getMethod("isInCombat");
            builder.tagTimeLeft = combatPlayer.getMethod("getTagTimeLeft");
            builder.enemy = combatPlayer.getMethod("getEnemy");
            builder.enemyPlayer = combatPlayer.getMethod("getPlayer");
            builder.tag = combatPlayer.getMethod("tag", boolean.class, combatPlayer);
            builder.tagFor = combatPlayer.getMethod("tag", boolean.class, combatPlayer, long.class);
            builder.untag = combatPlayer.getMethod("untag", reasons);
            builder.isNewbie = combatPlayer.getMethod("isNewbie");
            builder.hasRespawnProtection = combatPlayer.getMethod("hasRespawnProtection");
            builder.hasPvpEnabled = combatPlayer.getMethod("hasPvPEnabled");
            builder.setPvp = combatPlayer.getMethod("setPvP", boolean.class);
            builder.canAttack = managerClass.getMethod("canAttack", Player.class, Player.class);
            builder.untagReason = reasons.getField("PLUGIN_API").get(null);
            builder.present = true;
            return new PvpManagerProvider(builder);
        } catch (Throwable broken) {
            Logger.getLogger("ExyliaLib").warning(
                    "PvPManager is installed but its API could not be reached, so combat"
                            + " falls back to nothing: " + broken);
            return new PvpManagerProvider(new Builder());
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
        Object combatant = combatant(player);
        return combatant != null && Boolean.TRUE.equals(call(isInCombat, combatant));
    }

    @Override
    public Duration remaining(Player player) {
        Object combatant = combatant(player);
        if (combatant == null) {
            return Duration.ZERO;
        }
        Object millis = call(tagTimeLeft, combatant);
        return millis instanceof Long value && value > 0 ? Duration.ofMillis(value) : Duration.ZERO;
    }

    @Override
    public Optional<Player> opponentOf(Player player) {
        Object combatant = combatant(player);
        if (combatant == null) {
            return Optional.empty();
        }
        Object other = call(enemy, combatant);
        if (other == null) {
            return Optional.empty();
        }
        return call(enemyPlayer, other) instanceof Player found ? Optional.of(found)
                : Optional.empty();
    }

    @Override
    public void tag(Player target, Player attacker, Duration duration) {
        Object tagged = combatant(target);
        Object by = combatant(attacker);
        if (tagged == null || by == null) {
            return;
        }
        if (duration == null) {
            call(tag, tagged, true, by);
        } else {
            call(tagFor, tagged, true, by, duration.toMillis());
        }
    }

    @Override
    public void untag(Player player) {
        Object combatant = combatant(player);
        if (combatant != null) {
            call(untag, combatant, untagReason);
        }
    }

    @Override
    public boolean isProtected(Player player) {
        Object combatant = combatant(player);
        if (combatant == null) {
            return false;
        }
        return Boolean.TRUE.equals(call(isNewbie, combatant))
                || Boolean.TRUE.equals(call(hasRespawnProtection, combatant));
    }

    @Override
    public boolean isPvpEnabled(Player player) {
        Object combatant = combatant(player);
        // Fails open: a player nobody could look up is not a player who may not
        // fight.
        return combatant == null || !Boolean.FALSE.equals(call(hasPvpEnabled, combatant));
    }

    @Override
    public void setPvpEnabled(Player player, boolean enabled) {
        Object combatant = combatant(player);
        if (combatant != null) {
            call(setPvp, combatant, enabled);
        }
    }

    @Override
    public boolean canAttack(Player attacker, Player defender) {
        return !Boolean.FALSE.equals(call(canAttack, playerManager, attacker, defender));
    }

    private Object combatant(Player player) {
        return present ? call(combatPlayerOf, null, player) : null;
    }

    /**
     * Calls a method, answering {@code null} when it fails.
     *
     * <p>A combat plugin throwing is their bug. Letting it out of here would
     * turn it into a cancelled damage event on a server that was fine a version
     * ago, so every caller above treats {@code null} as "no answer" and falls
     * back to what a server with no combat plugin would do.
     */
    private static Object call(Method method, Object target, Object... args) {
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target, args);
        } catch (Throwable broken) {
            return null;
        }
    }

    /** Holds the reflected members while they are resolved. */
    private static final class Builder {
        private boolean present;
        private Object playerManager;
        private Method combatPlayerOf;
        private Method isInCombat;
        private Method tagTimeLeft;
        private Method enemy;
        private Method enemyPlayer;
        private Method tag;
        private Method tagFor;
        private Method untag;
        private Method isNewbie;
        private Method hasRespawnProtection;
        private Method hasPvpEnabled;
        private Method setPvp;
        private Method canAttack;
        private Object untagReason;
    }
}
