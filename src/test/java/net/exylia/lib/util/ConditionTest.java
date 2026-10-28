package net.exylia.lib.util;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionTest {

    private static UnaryOperator<String> values(Map<String, String> values) {
        return side -> {
            String out = side;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                out = out.replace("%" + entry.getKey() + "%", entry.getValue());
            }
            return out;
        };
    }

    @Test
    void comparesNumbersAndText() {
        UnaryOperator<String> resolve = values(Map.of("level", "7", "world", "Nether"));
        assertTrue(Condition.parse("%level% >= 5").test(resolve, false));
        assertFalse(Condition.parse("%level% < 5").test(resolve, true));
        assertTrue(Condition.parse("%level% == 7").test(resolve, false));
        assertTrue(Condition.parse("%world% == nether").test(resolve, false));
        assertTrue(Condition.parse("%world% != overworld").test(resolve, false));
        assertNull(Condition.parse("%level% >= 5").problem());
    }

    @Test
    void blankAndLiteralsMeanThemselves() {
        UnaryOperator<String> resolve = values(Map.of("flag", "false"));
        assertTrue(Condition.parse(null).test(resolve, false));
        assertTrue(Condition.parse("  ").test(resolve, false));
        assertTrue(Condition.parse("TRUE").test(resolve, false));
        assertFalse(Condition.parse("false").test(resolve, true));
        assertFalse(Condition.parse("%flag%").test(resolve, true));
    }

    @Test
    void unreadableAnswersTheCallersChoice() {
        Condition broken = Condition.parse("level five");
        assertNotNull(broken.problem());
        assertTrue(broken.test(UnaryOperator.identity(), true));
        assertFalse(broken.test(UnaryOperator.identity(), false));

        Condition missing = Condition.parse(">= 5");
        assertNotNull(missing.problem());
        assertFalse(missing.test(UnaryOperator.identity(), false));

        Condition notNumber = Condition.parse("%name% > 3");
        UnaryOperator<String> resolve = values(Map.of("name", "Steve"));
        assertTrue(notNumber.test(resolve, true));
        assertFalse(notNumber.test(resolve, false));
    }

    @Test
    void theOperatorIsFoundBeforeResolving() {
        UnaryOperator<String> resolve = values(Map.of("arrow", "a>b"));
        assertTrue(Condition.parse("%arrow% == a>b").test(resolve, false));
        assertEquals("%arrow% == a>b", Condition.parse(" %arrow% == a>b ").text());
    }
}
