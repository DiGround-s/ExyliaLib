package net.exylia.lib.hook.skill.internal;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.EventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillHookRuntimeTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @AfterEach
    void clear() {
        SkillHookRuntime.forget(alice);
        SkillHookRuntime.forget(bob);
    }

    @Test
    void aPlayerStaysSuspendedUntilEveryOwnerResumes() {
        assertTrue(SkillHookRuntime.suspend("Events", alice));
        assertFalse(SkillHookRuntime.suspend("Events", alice));
        SkillHookRuntime.suspend("Duels", alice);

        SkillHookRuntime.resume("Events", alice);
        assertTrue(SkillHookRuntime.isSuspended(alice));
        SkillHookRuntime.resume("Duels", alice);
        assertFalse(SkillHookRuntime.isSuspended(alice));
    }

    @Test
    void aDisabledOwnerReleasesOnlyItsOwnPlayers() {
        SkillHookRuntime.suspend("Events", alice);
        SkillHookRuntime.suspend("Events", bob);
        SkillHookRuntime.suspend("Duels", bob);

        SkillHookRuntime.release("Events");

        assertFalse(SkillHookRuntime.isSuspended(alice));
        assertTrue(SkillHookRuntime.isSuspended(bob));
    }

    @Test
    void experienceIsCancelledOnlyForSuspendedPlayers() throws Exception {
        EventExecutor executor = SkillHookRuntime.executor(XpGain.class, SkillHookRuntime.cancel(XpGain.class));
        SkillHookRuntime.suspend("Events", alice);

        XpGain suspended = new XpGain(player(alice));
        XpGain free = new XpGain(player(bob));
        executor.execute(null, suspended);
        executor.execute(null, free);

        assertTrue(suspended.isCancelled());
        assertFalse(free.isCancelled());
    }

    @Test
    void anEventThatCannotBeCancelledIsNotHooked() {
        assertThrows(NoSuchMethodException.class, () -> SkillHookRuntime.cancel(Uncancellable.class));
    }

    @Test
    void damageLosesTheModifiersOfTheSuspendedSideOnly() throws Exception {
        EventExecutor executor = SkillHookRuntime.executor(Damage.class,
                SkillHookRuntime.trimAuraDamage(Damage.class));
        SkillHookRuntime.suspend("Events", alice);

        Damage aliceHits = new Damage(player(alice), player(bob));
        executor.execute(null, aliceHits);
        assertEquals("attack", aliceHits.meta.cleared);

        Damage bobHits = new Damage(player(bob), player(alice));
        executor.execute(null, bobHits);
        assertEquals("defense", bobHits.meta.cleared);
    }

    @Test
    void nothingIsReadWhileNobodyIsSuspended() throws Exception {
        EventExecutor executor = SkillHookRuntime.executor(XpGain.class, event -> {
            throw new AssertionError("read with nobody suspended");
        });
        executor.execute(null, new XpGain(player(alice)));
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> method.getName().equals("getUniqueId") ? id : null);
    }

    public static class XpGain extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Player player;
        private boolean cancelled;

        XpGain(Player player) {
            this.player = player;
        }

        public Player getPlayer() {
            return player;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancel) {
            cancelled = cancel;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }
    }

    public static class Uncancellable extends Event {
        public Player getPlayer() {
            return null;
        }

        @Override
        public HandlerList getHandlers() {
            return new HandlerList();
        }
    }

    public static class Meta {
        private final Player attacker;
        private final Player target;
        String cleared = "";

        Meta(Player attacker, Player target) {
            this.attacker = attacker;
            this.target = target;
        }

        public Player getAttackerAsPlayer() {
            return attacker;
        }

        public Player getTargetAsPlayer() {
            return target;
        }

        public void clearAttackModifiers() {
            cleared += "attack";
        }

        public void clearDefenseModifiers() {
            cleared += "defense";
        }
    }

    public static class Damage extends Event {
        final Meta meta;

        Damage(Player attacker, Player target) {
            meta = new Meta(attacker, target);
        }

        public Meta getDamageMeta() {
            return meta;
        }

        @Override
        public HandlerList getHandlers() {
            return new HandlerList();
        }
    }
}
