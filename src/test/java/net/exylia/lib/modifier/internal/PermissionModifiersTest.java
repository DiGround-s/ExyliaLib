package net.exylia.lib.modifier.internal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PermissionModifiersTest {

    private final UUID player = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        PermissionModifiers.resetReader();
    }

    @Test
    @DisplayName("nodes parse into type, source and factor; anything else is ignored")
    void parse() {
        assertEquals(new PermissionModifiers.Grant("money", "*", 1.5), PermissionModifiers.parse("exylia.modifier.money.*.1.5"));
        assertEquals(new PermissionModifiers.Grant("clan-exp", "mines", 2.0),
                PermissionModifiers.parse("Exylia.Modifier.Clan-Exp.Mines.2"));
        assertNull(PermissionModifiers.parse("exylia.modifier.money.*"));
        assertNull(PermissionModifiers.parse("exylia.modifier.money.*.lots"));
        assertNull(PermissionModifiers.parse("exylia.modifier.money..2"));
        assertNull(PermissionModifiers.parse("exylia.modifier.money.*.-1"));
        assertNull(PermissionModifiers.parse("essentials.fly"));
    }

    @Test
    @DisplayName("the highest matching node wins; * covers every source of its own type only")
    void highestWins() {
        PermissionModifiers.setReader(id -> List.of(
                "exylia.modifier.money.*.1.5",
                "exylia.modifier.money.mines.2",
                "exylia.modifier.money.mines.1.25",
                "exylia.modifier.xp.orbs.3"));
        assertEquals(2.0, PermissionModifiers.factor(player, "money", "mines"));
        assertEquals(1.5, PermissionModifiers.factor(player, "money", "shop-sell"));
        assertEquals(3.0, PermissionModifiers.factor(player, "xp", "orbs"));
        assertEquals(1.0, PermissionModifiers.factor(player, "xp", "mines"));
        assertEquals(1.0, PermissionModifiers.factor(player, "elo", "mines"), "a money node never moves elo");
    }

    @Test
    @DisplayName("permissions are read once until forgotten, and through the runtime the factor multiplies")
    void cached() {
        AtomicInteger reads = new AtomicInteger();
        PermissionModifiers.setReader(id -> {
            reads.incrementAndGet();
            return Set.of("exylia.modifier.drops.*.2");
        });
        for (int i = 0; i < 100; i++) PermissionModifiers.factor(player, "drops", "blocks");
        assertEquals(1, reads.get());
        ModifierRuntime.forget(player);
        assertEquals(2.0, ModifierRuntime.factor(player, "drops", "blocks", null));
        assertEquals(2, reads.get());
    }

    @Test
    @DisplayName("a reader that throws answers 1 and keeps nothing")
    void racedRead() {
        AtomicInteger reads = new AtomicInteger();
        PermissionModifiers.setReader(id -> {
            if (reads.incrementAndGet() == 1) throw new java.util.ConcurrentModificationException();
            return List.of("exylia.modifier.money.*.2");
        });
        assertEquals(1.0, PermissionModifiers.factor(player, "money", "x"));
        assertEquals(2.0, PermissionModifiers.factor(player, "money", "x"));
    }
}
