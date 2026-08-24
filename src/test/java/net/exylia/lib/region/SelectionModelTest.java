package net.exylia.lib.region;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionModelTest {

    @Test
    @DisplayName("Selection options provide safe immutable defaults and reject non-items")
    void optionsDefaultsAndValidation() {
        SelectionOptions defaults = SelectionOptions.defaults();
        // A golden axe, handed over, drawn and confirmed: the ExyliaCommons
        // selector. A wooden axe nobody was given is WorldEdit's.
        assertEquals(Material.GOLDEN_AXE, defaults.selectorMaterial());
        assertTrue(defaults.cancelInteractions());
        assertTrue(defaults.requireSameWorld());
        assertTrue(defaults.giveSelector());
        assertTrue(defaults.requireConfirmation());
        assertTrue(defaults.feedback());
        assertTrue(defaults.hasPreview());
        assertEquals(defaults, new SelectionOptions());

        SelectionOptions custom = new SelectionOptions(Material.STICK, false, false);
        assertEquals(Material.STICK, custom.selectorMaterial());
        assertFalse(custom.cancelInteractions());
        assertFalse(custom.requireSameWorld());

        assertThrows(IllegalArgumentException.class,
                () -> new SelectionOptions(Material.AIR, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> new SelectionOptions(Material.WATER, true, true));
        assertThrows(NullPointerException.class,
                () -> new SelectionOptions(null, true, true));

        // The three-argument form is the old one, so it must leave everything
        // it never knew about at its default.
        assertTrue(custom.giveSelector());
        assertTrue(custom.requireConfirmation());

        SelectionOptions quiet = SelectionOptions.builder()
                .giveSelector(false)
                .requireConfirmation(false)
                .feedback(false)
                .previewParticle(null)
                .build();
        assertFalse(quiet.giveSelector());
        assertFalse(quiet.requireConfirmation());
        assertFalse(quiet.feedback());
        assertFalse(quiet.hasPreview());
        assertEquals(quiet, quiet.toBuilder().build());

        assertThrows(IllegalArgumentException.class,
                () -> SelectionOptions.builder().previewParticle(" "));
        assertThrows(IllegalArgumentException.class,
                () -> SelectionOptions.builder().previewSpacing(0.0));
        assertThrows(IllegalArgumentException.class,
                () -> SelectionOptions.builder().previewPeriodTicks(0L));
    }

    @Test
    @DisplayName("Selection result retains exact corners and normalizes inclusive blocks")
    void resultPreservesExactInclusiveCorners() {
        WorldIdentity world = new WorldIdentity(UUID.randomUUID(), "selection-world");
        BlockPosition first = new BlockPosition(world, 7, -4, 11);
        BlockPosition second = new BlockPosition(world, 5, -6, 13);
        SelectionResult result = new SelectionResult(world, first, second);

        assertEquals(first, result.first());
        assertEquals(second, result.second());
        assertEquals(new Cuboid(5, -6, 11, 8, -3, 14), result.cuboid());
        assertTrue(result.cuboid().contains(7, -4, 13));
        assertFalse(result.cuboid().contains(8, -4, 13));
    }

    @Test
    @DisplayName("Cross-world permitted results use first world and exact coordinate triples")
    void resultCanNormalizePermittedCrossWorldCoordinates() {
        WorldIdentity firstWorld = new WorldIdentity(UUID.randomUUID(), "first");
        WorldIdentity secondWorld = new WorldIdentity(UUID.randomUUID(), "second");
        BlockPosition first = new BlockPosition(firstWorld, 1, 2, 3);
        BlockPosition second = new BlockPosition(secondWorld, 1, 2, 3);

        SelectionResult result = new SelectionResult(firstWorld, first, second);
        assertEquals(Cuboid.block(1, 2, 3), result.cuboid());
        assertThrows(IllegalArgumentException.class,
                () -> new SelectionResult(secondWorld, first, second));
    }
}
