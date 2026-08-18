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
}
