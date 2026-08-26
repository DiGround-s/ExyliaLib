package net.exylia.lib.item.internal;

import net.exylia.lib.item.Appearance;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which flags an appearance actually writes.
 *
 * <p>Recorded off a stand-in {@code ItemMeta}, because a real {@code ItemStack}
 * cannot be built without the server's registry. The flags are the whole of the
 * decision, so recording them is enough — and it is the part that was wrong:
 * a smithing template in a menu kept describing itself.
 */
class AppearanceFlagsTest {

    /** The flags an appearance asked for, in the order it asked. */
    private static Set<ItemFlag> flagsOf(Appearance appearance) {
        EnumSet<ItemFlag> added = EnumSet.noneOf(ItemFlag.class);
        ItemMeta meta = (ItemMeta) Proxy.newProxyInstance(
                AppearanceFlagsTest.class.getClassLoader(),
                new Class<?>[]{ItemMeta.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addItemFlags")) {
                        for (ItemFlag flag : (ItemFlag[]) args[0]) {
                            added.add(flag);
                        }
                    }
                    return net.exylia.lib.FakeServer.defaultValue(method.getReturnType());
                });
        ItemRenderer.appearance(meta, appearance, (where, problem) -> { });
        return added;
    }

    @Test
    @DisplayName("an amount past what the material stacks to raises the limit to match")
    void anAmountRaisesTheStackLimit() {
        // A sword stacks to one, so "40" on a kit icon was dropped and the menu
        // drew a single sword. This is what commons did for every item, and the
        // reason every menu with a count had to know about max_stack_size.
        assertEquals(40, ItemRenderer.stackLimit(Appearance.PLAIN, 40, 1),
                "the count is the whole point of the icon");

        // Already allowed, so nothing is written: a stack of 16 arrows needs no
        // component, and stamping one onto every icon in every menu is how an
        // item stops matching the plain one beside it.
        assertEquals(-1, ItemRenderer.stackLimit(Appearance.PLAIN, 16, 64),
                "a material that already allows the count is left alone");
        assertEquals(-1, ItemRenderer.stackLimit(Appearance.PLAIN, 1, 1),
                "one of a one-stack item is not a raise");

        // A file that names a limit means it, whichever way it points.
        Appearance named = Appearance.builder().maxStackSize(8).build();
        assertEquals(8, ItemRenderer.stackLimit(named, 40, 1),
                "an explicit max_stack_size still wins");
    }

    @Test
    @DisplayName("hiding attributes hides everything vanilla writes by itself")
    void hideAttributesCoversTheWholeTooltip() {
        // The report: a smithing template drawn in a menu still said "Smithing
        // Template / Applies to: Armor / Ingredients: Ingots & Crystals".
        // HIDE_ATTRIBUTES alone never covered that — it is the additional
        // tooltip, which is also where a potion lists its effects.
        Set<ItemFlag> flags = flagsOf(Appearance.builder().hideAttributes(true).build());

        assertTrue(flags.contains(ItemFlag.HIDE_ATTRIBUTES), "the modifier lines");
        assertTrue(flags.contains(ItemFlag.HIDE_ADDITIONAL_TOOLTIP),
                "the block a template, a potion or a firework adds: " + flags);
        assertTrue(flags.contains(ItemFlag.HIDE_ARMOR_TRIM), "a trim on armour");
        assertTrue(flags.contains(ItemFlag.HIDE_DYE), "a dye on leather");
    }

    @Test
    @DisplayName("hiding attributes leaves the enchantments an item means to show")
    void hideAttributesKeepsEnchantments() {
        // ExyliaCommons applied ItemFlag.values() here, so an item asking to
        // hide its attributes also lost the enchantment lines it wanted. This
        // is that bug not being copied along with the behaviour.
        Set<ItemFlag> flags = flagsOf(Appearance.builder().hideAttributes(true).build());

        assertFalse(flags.contains(ItemFlag.HIDE_ENCHANTS),
                "hiding an enchantment stays something a file asks for: " + flags);
        assertFalse(flags.contains(ItemFlag.HIDE_UNBREAKABLE),
                "and so does hiding the unbreakable line");
    }

    @Test
    @DisplayName("an item that did not ask hides nothing")
    void nothingIsHiddenByDefault() {
        assertTrue(flagsOf(Appearance.PLAIN).isEmpty());
        assertTrue(flagsOf(Appearance.builder().glow(true).build()).isEmpty(),
                "a glow is not a reason to hide anything");
    }

    @Test
    @DisplayName("a file naming its own flags still gets exactly those")
    void namedFlagsAreUnaffected() {
        Set<ItemFlag> flags = flagsOf(Appearance.builder()
                .flags(List.of("HIDE_ENCHANTS"))
                .build());

        assertEquals(Set.of(ItemFlag.HIDE_ENCHANTS), flags);
    }

    @Test
    @DisplayName("unbreakable still hides its own line, and nothing more")
    void unbreakableHidesItsLine() {
        Set<ItemFlag> flags = flagsOf(Appearance.builder().unbreakable(true).build());

        assertEquals(Set.of(ItemFlag.HIDE_UNBREAKABLE), flags);
    }

    /** Records whether the additional-tooltip component was written. */
    private static final class RecordingComponents implements ItemRenderer.Components {
        private int hidden;

        @Override
        public void hideAdditionalTooltip(org.bukkit.inventory.ItemStack item,
                                          TraitApplier.Reporter problems) {
            hidden++;
        }
    }

    /**
     * Whether an appearance calls for the additional-tooltip component.
     *
     * <p>Asks the seam rather than rendering: an {@code ItemStack} cannot be
     * built without the server's registry, and naming
     * {@code DataComponentTypes} at all needs one too — it resolves its
     * constants against the registry in a static initialiser. This is the
     * decision, and the decision is what was wrong.
     *
     * <p>The flag is deliberately not what these two assert on. It was being
     * set all along and the tooltip stayed anyway, because a flag is only
     * persisted next to the data it hides and a smithing template holds none:
     * its block comes from the item type. A test that asks about the flag
     * passes while players see otherwise, which is what happened here.
     */
    private static int componentWritesFor(Appearance appearance) {
        RecordingComponents recording = new RecordingComponents();
        ItemRenderer.Components previous = ItemRenderer.components(recording);
        try {
            ItemRenderer.hideAdditionalTooltip(null, appearance, (where, problem) -> { });
        } finally {
            ItemRenderer.components(previous);
        }
        return recording.hidden;
    }

    @Test
    @DisplayName("hiding attributes disables the block the item type writes itself")
    void hideAttributesWritesTheComponent() {
        // The report: a WILD_ARMOR_TRIM_SMITHING_TEMPLATE drawn in a menu kept
        // saying "Applies to: Armor / Ingredients: Ingots & Crystals" with
        // hide-attributes: true set. Only the data component reaches that.
        assertEquals(1, componentWritesFor(Appearance.builder().hideAttributes(true).build()),
                "the component is written exactly once");
    }

    @Test
    @DisplayName("hiding attributes hides what the flags hide, not only the type block")
    void theComponentCoversWhatTheFlagsCover() {
        // The report: armour, weapons and trims kept their extra lines with
        // hide-attributes: true. On 1.21.5+ an ItemFlag is an entry in
        // tooltip_display, and this write replaces that component whole — so
        // hiding the type-written block handed the flagged lines straight back.
        List<String> hidden = ItemComponents.hiddenNamesForTests();

        assertTrue(hidden.contains("attribute_modifiers"),
                "the armour and damage lines: " + hidden);
        assertTrue(hidden.contains("trim"), "a trim on armour: " + hidden);
        assertTrue(hidden.contains("dyed_color"), "a dye on leather: " + hidden);
        assertTrue(hidden.contains("provides_trim_material"),
                "and the block a template writes for itself: " + hidden);

        assertFalse(hidden.contains("enchantments"),
                "hiding an enchantment stays something a file asks for: " + hidden);
        assertFalse(hidden.contains("unbreakable"),
                "and so does hiding the unbreakable line: " + hidden);
    }

    @Test
    @DisplayName("an item that did not ask keeps the block its type writes")
    void withoutHideAttributesTheComponentIsLeftAlone() {
        // A potion listing its effects is usually the point of drawing it.
        assertEquals(0, componentWritesFor(Appearance.PLAIN),
                "nothing is hidden unless the file asked");
        assertEquals(0, componentWritesFor(Appearance.builder().glow(true).build()),
                "a glow is not a reason to hide anything");
    }

    @Test
    @DisplayName("a server that cannot write the component still draws the item")
    void anUnsupportedComponentIsReportedRatherThanThrown() {
        // The library compiles against paper-api 1.21.4 and runs on servers
        // that moved on: on 1.21.11 this component is gone, and naming it as a
        // field threw NoSuchFieldError straight through the renderer, taking
        // the whole menu down with it. A tooltip is never worth that.
        //
        // Run against the real implementation, with no server: neither registry
        // lookup can work here, which is the same shape of failure a server
        // that knows neither name produces.
        ItemComponents.forgetReportedForTests();
        List<String> reported = new java.util.ArrayList<>();

        assertDoesNotThrow(() -> ItemComponents.INSTANCE.hideAdditionalTooltip(
                        null, (where, problem) -> reported.add(where)),
                "a component this server does not have is reported, never thrown");

        assertEquals(List.of("hide-attributes"), reported,
                "and the file is told which of its keys could not be honoured");
    }

    @Test
    @DisplayName("a server that cannot write it is told once, not once per item")
    void theUnsupportedReportIsNotRepeatedPerItem() {
        // Opening one menu renders every slot in it. Reporting per item put
        // eighteen identical lines in the console for a single screen, which
        // buries whatever else the server was saying. It is a fact about the
        // version, not an incident about the item.
        ItemComponents.forgetReportedForTests();
        List<String> reported = new java.util.ArrayList<>();

        for (int slot = 0; slot < 18; slot++) {
            ItemComponents.INSTANCE.hideAdditionalTooltip(
                    null, (where, problem) -> reported.add(where));
        }

        assertEquals(1, reported.size(), "said once for the whole server: " + reported);
    }
}
