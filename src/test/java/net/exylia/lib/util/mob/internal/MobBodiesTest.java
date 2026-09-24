package net.exylia.lib.util.mob.internal;

import net.exylia.lib.ragdoll.RagdollPart;
import net.exylia.lib.ragdoll.RagdollSkin;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobBodiesTest {

    /** The humanoids the design promises a ragdoll. */
    private static final Set<EntityType> PROMISED = Set.of(EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED,
            EntityType.SKELETON, EntityType.STRAY, EntityType.WITHER_SKELETON, EntityType.PIGLIN_BRUTE,
            EntityType.VINDICATOR, EntityType.PILLAGER, EntityType.EVOKER, EntityType.WITCH);

    @Test
    @DisplayName("every curated humanoid has a whole skin, one shared instance, and a palette of its own")
    void humanoidsHaveBodies() {
        assertTrue(MobBodies.humanoids().containsAll(PROMISED));
        for (EntityType type : MobBodies.humanoids()) {
            RagdollSkin skin = MobBodies.skin(type);
            assertNotNull(skin, type.name());
            assertSame(skin, MobBodies.skin(type), "built once per type");
            for (RagdollPart part : RagdollPart.values()) {
                assertTrue(skin.has(part), type + " " + part);
            }
            assertTrue(MobBodies.palette(type, "", "") != MobBodies.FALLBACK, type + " has its own palette");
        }
        assertNull(MobBodies.skin(EntityType.COW), "a cow shatters instead");
        assertFalse(MobBodies.humanoid(EntityType.CREEPER));
    }

    @Test
    @DisplayName("heads are the vanilla item where one exists and a plain head in the skin's colour otherwise")
    void heads() {
        assertEquals(Material.ZOMBIE_HEAD, MobBodies.head(EntityType.ZOMBIE));
        assertEquals(Material.SKELETON_SKULL, MobBodies.head(EntityType.SKELETON));
        assertEquals(Material.WITHER_SKELETON_SKULL, MobBodies.head(EntityType.WITHER_SKELETON));
        assertEquals(Material.PIGLIN_HEAD, MobBodies.head(EntityType.PIGLIN_BRUTE));
        assertNull(MobBodies.head(EntityType.HUSK), "a green zombie head on a tan body reads wrong");
        for (EntityType type : EntityType.values()) {
            if (MobBodies.head(type) != null) assertTrue(MobBodies.humanoid(type), type + " has a head but no body");
        }
        assertTrue(MobBodies.isHead(Material.PLAYER_HEAD));
        assertTrue(MobBodies.isHead(Material.WITHER_SKELETON_SKULL));
        assertFalse(MobBodies.isHead(Material.IRON_HELMET));
        assertFalse(MobBodies.isHead(null));
    }

    @Test
    @DisplayName("palettes follow the variant and the carpet, and an unknown mob falls back to a plain one")
    void palettes() {
        assertEquals(MobBodies.FALLBACK, MobBodies.palette(EntityType.GIANT, "", ""));
        assertEquals(MobBodies.palette(EntityType.LLAMA, "", ""), MobBodies.palette(EntityType.LLAMA, "PURPLE", ""),
                "an unknown variant reads as the type's own");
        assertEquals(Material.WHITE_WOOL, MobBodies.palette(EntityType.LLAMA, "white", "").getFirst());
        List<Material> dressed = MobBodies.palette(EntityType.LLAMA, "CREAMY", "MAGENTA_CARPET");
        assertEquals(Material.MAGENTA_WOOL, dressed.getFirst(), "the carpet leads");
        assertTrue(dressed.contains(Material.WHITE_TERRACOTTA));
        assertEquals(MobBodies.palette(EntityType.LLAMA, "CREAMY", ""),
                MobBodies.palette(EntityType.LLAMA, "CREAMY", "WOLF_ARMOR"), "only a carpet dresses it");
        assertEquals(Material.LIME_WOOL, MobBodies.palette(EntityType.SHEEP, "LIME", "").getFirst());
        assertEquals(Material.BROWN_WOOL, MobBodies.palette(EntityType.TRADER_LLAMA, "BROWN", "").getFirst());
    }
}
