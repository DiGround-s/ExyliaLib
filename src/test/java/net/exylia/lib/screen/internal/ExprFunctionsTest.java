package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.ExpressionFunctions;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import net.exylia.lib.FakeServer;

import static org.junit.jupiter.api.Assertions.*;

class ExprFunctionsTest {

    private static final Expr.Env ENV = new Expr.Env() {
        public Object name(String name) { return null; }
        public Object placeholder(String name) { return null; }
        public String word(String key) { return null; }
        public Object literal(String text) { return text; }
    };

    @AfterEach
    void release() {
        ExpressionFunctions.releaseAll();
    }

    @Test
    void containsMatchesAndChance() {
        assertTrue(Expr.compile("contains('hello world', 'lo w')").test(ENV));
        assertFalse(Expr.compile("contains('hello', 'H')").test(ENV));
        assertTrue(Expr.compile("matches('abc123', '[a-z]+\\d+')").test(ENV));
        assertFalse(Expr.compile("matches('abc', '(')").test(ENV));
        assertTrue(Expr.compile("chance(100)").test(ENV));
        assertFalse(Expr.compile("chance(0)").test(ENV));
    }

    @Test
    void pluginFunctions() {
        Plugin plugin = FakeServer.newPlugin("Test");
        assertThrows(IllegalArgumentException.class, () -> Expr.compile("data('coins') >= 5"));
        ExpressionFunctions.register(plugin, "data", (viewer, args) -> 7d);
        Expr compiled = Expr.compile("data('coins') >= 5");
        assertTrue(compiled.test(ENV));
        assertThrows(IllegalArgumentException.class, () -> ExpressionFunctions.register(plugin, "perm", (v, a) -> null));
        ExpressionFunctions.release("Test");
        assertFalse(compiled.test(ENV));
    }
}
