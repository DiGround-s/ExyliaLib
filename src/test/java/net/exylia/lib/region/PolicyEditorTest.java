package net.exylia.lib.region;

import net.exylia.lib.FakeServer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the policy editor refuses when it is built, before anything is drawn. */
class PolicyEditorTest {

    private PolicyEditor editor;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        editor = Regions.of(FakeServer.newPlugin("Flags")).policyEditor(PolicySet.empty());
    }

    @Test
    @DisplayName("a key the screen has no row type for is refused")
    void unsupportedType() {
        PolicyKey<String> text = PolicyKey.of(new RegionId("flags", "motd"), String.class, "");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> editor.keys(text));
        assertTrue(refused.getMessage().contains("Boolean, Integer and MaterialSet"), refused.getMessage());
    }

    @Test
    @DisplayName("duplicates, none and more than fit are refused")
    void counts() {
        assertThrows(IllegalArgumentException.class,
                () -> editor.keys(CommonRegionPolicies.PVP, CommonRegionPolicies.PVP));
        assertThrows(IllegalArgumentException.class, () -> editor.keys());
        PolicyKey<?>[] many = new PolicyKey<?>[PolicyEditor.MAX_KEYS + 1];
        for (int index = 0; index < many.length; index++) {
            many[index] = PolicyKey.of(new RegionId("flags", "k" + index), Boolean.class, true);
        }
        assertThrows(IllegalArgumentException.class, () -> editor.keys(many));
    }

    @Test
    @DisplayName("a key of your own must be described before it can open")
    void undescribed() {
        PolicyKey<Boolean> keep = PolicyKey.of(new RegionId("flags", "keep_inventory"), Boolean.class, false);
        // Never touched: the refusal comes before anything is scheduled.
        Player viewer = (Player) java.lang.reflect.Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> null);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> editor.keys(CommonRegionPolicies.PVP, keep).open(viewer));
        assertTrue(refused.getMessage().contains("describe"), refused.getMessage());
    }
}
