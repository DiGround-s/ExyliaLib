package net.exylia.lib.util.combat.internal;

import net.exylia.lib.util.combat.CombatStats;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A combat plugin that answers from a set instead of from a server.
 *
 * <p>PvPManager and DeluxeCombat cannot run in a unit test. What has to be
 * proven is the module's own behaviour around them — what it caches, when it
 * stops caching, and what it answers when they throw — so this counts how often
 * it was asked.
 */
final class FakeProvider implements CombatProvider {

    private final CopyOnWriteArraySet<UUID> tagged = new CopyOnWriteArraySet<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();

    /** How many times the module actually asked, rather than used a cache. */
    private final AtomicInteger tagQuestions = new AtomicInteger();

    volatile boolean broken;
    volatile boolean pvpEnabled = true;
    volatile Duration left = Duration.ofSeconds(10);
    volatile CombatStats stats;

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String name() {
        return "Fake";
    }

    @Override
    public boolean isTagged(Player player) {
        fail();
        tagQuestions.incrementAndGet();
        return tagged.contains(player.getUniqueId());
    }

    @Override
    public Duration remaining(Player player) {
        fail();
        calls.add("remaining:" + player.getName());
        return tagged.contains(player.getUniqueId()) ? left : Duration.ZERO;
    }

    @Override
    public void tag(Player target, Player attacker, Duration duration) {
        fail();
        calls.add("tag:" + target.getName() + ":" + attacker.getName() + ":" + duration);
        tagged.add(target.getUniqueId());
    }

    @Override
    public void untag(Player player) {
        fail();
        calls.add("untag:" + player.getName());
        tagged.remove(player.getUniqueId());
    }

    @Override
    public boolean isPvpEnabled(Player player) {
        fail();
        return pvpEnabled;
    }

    @Override
    public boolean canAttack(Player attacker, Player defender) {
        fail();
        return pvpEnabled;
    }

    @Override
    public Optional<CombatStats> statsOf(Player player) {
        fail();
        calls.add("stats:" + player.getName());
        return Optional.ofNullable(stats);
    }

    private void fail() {
        if (broken) {
            throw new IllegalStateException("the combat plugin is broken");
        }
    }

    int tagQuestions() {
        return tagQuestions.get();
    }

    List<String> calls() {
        return List.copyOf(calls);
    }

    void clear() {
        calls.clear();
        tagQuestions.set(0);
    }
}
