package net.exylia.lib.platform;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether {@code hide-attributes} reaches a sword that only has its type's modifiers.
 *
 * <p>The report: menu icons in nine plugins showed "When in Main Hand: Attack
 * Damage" with {@code hide-attributes: true}. On 1.21.4 the flag lives inside
 * the {@code attribute_modifiers} component, and an item without that
 * component drops the flag.
 */
class AttributeLinesTest {

    /** A stand-in meta recording the flags and the modifiers written onto it. */
    private static final class Recording {
        final Set<ItemFlag> flags = EnumSet.noneOf(ItemFlag.class);
        Multimap<?, ?> written;
        final boolean carriesModifiers;
        final ItemMeta meta;

        Recording(boolean carriesModifiers) {
            this.carriesModifiers = carriesModifiers;
            this.meta = (ItemMeta) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ItemMeta.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "addItemFlags" -> {
                            flags.addAll(Set.of((ItemFlag[]) args[0]));
                            yield null;
                        }
                        case "hasAttributeModifiers" -> this.carriesModifiers;
                        case "setAttributeModifiers" -> {
                            written = (Multimap<?, ?>) args[0];
                            yield null;
                        }
                        default -> net.exylia.lib.FakeServer.defaultValue(method.getReturnType());
                    });
        }
    }

    /**
     * A non-empty map standing in for a sword's own modifiers. Neither an
     * {@code Attribute} nor an {@code AttributeModifier} can be built without
     * the server's registry, and only the map's identity is asserted on.
     */
    @SuppressWarnings("unchecked")
    private static Multimap<Attribute, AttributeModifier> swordModifiers() {
        return (Multimap<Attribute, AttributeModifier>) (Multimap<?, ?>)
                ImmutableMultimap.of("attack_damage", "base_attack_damage");
    }

    @Test
    @DisplayName("an item with only its type's modifiers gets them written, so the flag is kept")
    void typeModifiersAreWrittenUpTo1214() {
        Recording sword = new Recording(false);
        Multimap<Attribute, AttributeModifier> own = swordModifiers();

        assertTrue(AttributeLines.hide(sword.meta, () -> own, false));

        assertTrue(sword.flags.contains(ItemFlag.HIDE_ATTRIBUTES));
        assertSame(own, sword.written,
                "without the component the flag is dropped and the lines stay on screen");
    }

    @Test
    @DisplayName("an item that already carries modifiers only needs the flag")
    void ownModifiersAreLeftAlone() {
        Recording kit = new Recording(true);

        assertTrue(AttributeLines.hide(kit.meta, () -> {
            throw new AssertionError("the type's modifiers would replace the item's own");
        }, false));

        assertTrue(kit.flags.contains(ItemFlag.HIDE_ATTRIBUTES));
        assertNull(kit.written);
    }

    @Test
    @DisplayName("from 1.21.5 the flag is stored by itself and nothing else is written")
    void onlyTheFlagFrom1215() {
        Recording sword = new Recording(false);

        assertTrue(AttributeLines.hide(sword.meta, AttributeLinesTest::swordModifiers, true));

        assertEquals(Set.of(ItemFlag.HIDE_ATTRIBUTES), sword.flags);
        assertNull(sword.written, "tooltip_display keeps the flag without the component");
    }

    @Test
    @DisplayName("an item without attribute lines needs no meta set")
    void nothingToHide() {
        Recording paper = new Recording(false);

        assertFalse(AttributeLines.hide(paper.meta, ImmutableMultimap::of, false));
        assertNull(paper.written);
    }
}
