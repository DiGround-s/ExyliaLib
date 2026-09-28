package net.exylia.lib.region.internal;

import net.exylia.lib.region.CommonRegionPolicies;
import net.exylia.lib.region.MaterialSet;
import net.exylia.lib.region.PolicyKey;
import net.exylia.lib.region.PolicySet;
import net.exylia.lib.region.RegionId;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static net.exylia.lib.region.CommonRegionPolicies.ALLOWED_BLOCKS;
import static net.exylia.lib.region.CommonRegionPolicies.BUILD;
import static net.exylia.lib.region.CommonRegionPolicies.FALL_DAMAGE;
import static net.exylia.lib.region.CommonRegionPolicies.PVP;
import static net.exylia.lib.region.CommonRegionPolicies.TEMPORARY_BLOCKS_SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a click does to a policy editor's working copy, and what its rows say,
 * without a window.
 */
class PolicyDraftTest {

    private static PolicyDraft draft(PolicySet policies) {
        return new PolicyDraft(policies, null, Map.of());
    }

    @Test
    @DisplayName("a boolean row cycles Default, Allow, Deny and back to Default")
    void cycle() {
        PolicyDraft draft = draft(PolicySet.empty());

        assertEquals(PolicyDraft.State.DEFAULT, draft.state(PVP));
        assertTrue(draft.cycle(PVP));
        assertEquals(PolicyDraft.State.ALLOW, draft.state(PVP));
        assertEquals(Optional.of(true), draft.result().explicit(PVP));
        draft.cycle(PVP);
        assertEquals(PolicyDraft.State.DENY, draft.state(PVP));
        assertEquals(Optional.of(false), draft.result().explicit(PVP));
        draft.cycle(PVP);
        // Default is an absent declaration, not the key's default written down:
        // a region that says nothing must keep following whatever decides for it.
        assertEquals(PolicyDraft.State.DEFAULT, draft.state(PVP));
        assertFalse(draft.result().declares(PVP));
        assertFalse(draft.changed(), "a full cycle is back where it started");
    }

    @Test
    @DisplayName("a locked row never changes, whatever is clicked")
    void locked() {
        PolicyDraft draft = new PolicyDraft(PolicySet.of(PVP, true), null,
                Map.of(PVP, "The game switches this by itself"));

        assertFalse(draft.cycle(PVP));
        assertFalse(draft.reset(PVP));
        assertFalse(draft.set(PVP, false));
        assertEquals(Optional.of(true), draft.result().explicit(PVP));
        assertEquals("The game switches this by itself", draft.lockReason(PVP));
        assertTrue(draft.cycle(BUILD), "only the locked row is refused");
    }

    @Test
    @DisplayName("Default resolves through the defaults, then the key, or says the plugin decides")
    void defaults() {
        PolicyDraft none = draft(PolicySet.empty());
        assertEquals(Optional.empty(), none.fallback(PVP), "no defaults given: the plugin decides");

        PolicyDraft given = new PolicyDraft(PolicySet.empty(), PolicySet.of(PVP, false), Map.of());
        assertEquals(Optional.of(false), given.fallback(PVP), "declared by the defaults");
        assertEquals(Optional.of(true), given.fallback(FALL_DAMAGE), "not declared: the key's own default");
    }

    @Test
    @DisplayName("value rows are set and reset, and keys not shown pass through")
    void valueRows() {
        PolicyKey<Boolean> custom = PolicyKey.of(new RegionId("test", "keep_inventory"), Boolean.class, false);
        PolicyDraft draft = draft(PolicySet.of(custom, true));

        assertTrue(draft.set(ALLOWED_BLOCKS, MaterialSet.of(Material.SAND, Material.GRAVEL)));
        assertTrue(draft.set(TEMPORARY_BLOCKS_SECONDS, 30));
        assertEquals(MaterialSet.of(Material.GRAVEL, Material.SAND), draft.result().explicit(ALLOWED_BLOCKS).orElseThrow());
        assertEquals(Optional.of(30), draft.result().explicit(TEMPORARY_BLOCKS_SECONDS));

        draft.reset(ALLOWED_BLOCKS);
        assertFalse(draft.result().declares(ALLOWED_BLOCKS));
        assertEquals(Optional.of(true), draft.result().explicit(custom), "a key nobody showed survives");
        assertEquals(1, draft.declared(List.of(PVP, TEMPORARY_BLOCKS_SECONDS, ALLOWED_BLOCKS)));
    }

    @Test
    @DisplayName("a row reads its state, its default and its action")
    void rowText() {
        PolicyLooks.Look look = PolicyLooks.builtIn(PVP);
        PolicyDraft plugin = draft(PolicySet.empty());
        assertEquals("{primary}&lPVP &8[{muted}DEFAULT&8]", PolicyLooks.name(PVP, look, plugin));
        List<String> lore = PolicyLooks.lore(PVP, look, plugin);
        assertTrue(lore.contains(" {letters_black}▎ {letters}Default {letters_black}» {muted}Set by the plugin"), lore.toString());
        assertTrue(lore.contains("{warning}➥ Click to cycle"), lore.toString());
        assertFalse(lore.contains("{warning}➥ Right-click for default"), "nothing to reset yet");

        PolicyDraft denied = new PolicyDraft(PolicySet.of(PVP, true), PolicySet.of(PVP, false), Map.of());
        assertEquals("{primary}&lPVP &8[{success}ALLOW&8]", PolicyLooks.name(PVP, look, denied));
        lore = PolicyLooks.lore(PVP, look, denied);
        assertTrue(lore.contains(" {letters_black}▎ {letters}Default {letters_black}» {error}Deny"), lore.toString());
        assertTrue(lore.contains("{warning}➥ Right-click for default"), lore.toString());

        PolicyDraft locked = new PolicyDraft(PolicySet.empty(), null, Map.of(PVP, "Driven by the game"));
        lore = PolicyLooks.lore(PVP, look, locked);
        assertTrue(lore.contains(" {letters_black}▎ {muted}Driven by the game"), lore.toString());
        assertTrue(lore.stream().noneMatch(line -> line.startsWith("{warning}➥")), "a locked row offers nothing");
    }

    @Test
    @DisplayName("a block list names its first materials and counts the rest")
    void materialText() {
        PolicyLooks.Look look = PolicyLooks.builtIn(ALLOWED_BLOCKS);
        PolicyDraft draft = draft(PolicySet.of(ALLOWED_BLOCKS, MaterialSet.of(Material.STONE, Material.DIRT,
                Material.SAND, Material.GRAVEL, Material.OAK_LOG, Material.GLASS, Material.TNT)));

        assertEquals("{primary}&lALLOWED BLOCKS &8[{info}7&8]", PolicyLooks.name(ALLOWED_BLOCKS, look, draft));
        List<String> lore = PolicyLooks.lore(ALLOWED_BLOCKS, look, draft);
        assertTrue(lore.contains(" {letters_black}▎ {letters}Now {letters_black}» {info}7 materials"), lore.toString());
        assertTrue(lore.contains(" {letters_black}▎ {muted}and 2 more"), lore.toString());
        assertEquals(PolicyLooks.LISTED, lore.stream().filter(line -> line.startsWith(" {letters_black}▎ {info}")).count());

        PolicyLooks.Look seconds = PolicyLooks.builtIn(TEMPORARY_BLOCKS_SECONDS);
        assertEquals("{primary}&lBLOCK LIFETIME &8[{info}30s ⌚&8]", PolicyLooks.name(TEMPORARY_BLOCKS_SECONDS,
                seconds, draft(PolicySet.of(TEMPORARY_BLOCKS_SECONDS, 30))));
    }

    @Test
    @DisplayName("every common policy has a look, and the default rows are its fifteen booleans")
    void builtIns() {
        assertEquals(15, PolicyLooks.BOOLEANS.size());
        assertEquals(PVP, PolicyLooks.BOOLEANS.get(0));
        for (PolicyKey<?> key : List.of(CommonRegionPolicies.TEMPORARY_BLOCKS_SECONDS,
                CommonRegionPolicies.ALLOWED_BLOCKS, CommonRegionPolicies.BREAKABLE_BLOCKS)) {
            assertTrue(PolicyLooks.builtIn(key) != null, key.toString());
            assertTrue(PolicyLooks.editable(key), key.toString());
        }
        assertFalse(PolicyLooks.editable(PolicyKey.of(new RegionId("test", "name"), String.class, "")));
    }

    @Test
    @DisplayName("rows sit seven to a line inside the frame, with the controls on the last line")
    void layout() {
        assertEquals(27, PolicyEditorHolder.size(1));
        assertEquals(45, PolicyEditorHolder.size(15));
        assertEquals(45, PolicyEditorHolder.size(18));
        assertEquals(54, PolicyEditorHolder.size(28));
        assertEquals(10, PolicyEditorHolder.slotOf(0));
        assertEquals(16, PolicyEditorHolder.slotOf(6));
        assertEquals(19, PolicyEditorHolder.slotOf(7));
        assertEquals(43, PolicyEditorHolder.slotOf(27));
    }
}
