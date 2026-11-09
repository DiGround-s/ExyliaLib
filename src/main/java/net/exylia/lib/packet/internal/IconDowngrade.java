package net.exylia.lib.packet.internal;

import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemLore;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import net.exylia.lib.packet.ClientVersions;
import net.exylia.lib.text.internal.Icons;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Swaps the sprites an icon token drew for their glyphs, on an item or a
 * sidebar on its way to a client too old to draw them.
 *
 * <p>Sprites are text objects from Minecraft 1.21.9, and item sprites read
 * from the {@code items} atlas only from 1.21.11. A player joining through
 * ViaBackwards with an older client would see a gap where the icon is; they
 * see the glyph {@code {icon:...}} named instead, in the colour around it.
 *
 * <p>The object component type is newer than the Adventure ExyliaLib compiles
 * against, so it is read by reflection. It only exists on a server that drew
 * a sprite, which is the only server that reaches this class with one.
 */
final class IconDowngrade {

    /** What a viewer's client cannot draw. */
    record Limits(boolean sprites, boolean itemsAtlas) {
        boolean any() {
            return sprites || itemsAtlas;
        }
    }

    private static final String OBJECT = "net.kyori.adventure.text.ObjectComponent";
    private static final String SPRITE = "net.kyori.adventure.text.object.SpriteObjectContents";
    private static final String ITEMS = "minecraft:items";

    private static final ClassValue<Method> CONTENTS = methodOf(OBJECT, "contents");
    private static final ClassValue<Method> ATLAS = methodOf(SPRITE, "atlas");
    private static final ClassValue<Method> SPRITE_ID = methodOf(SPRITE, "sprite");

    private IconDowngrade() {
    }

    /** What this viewer cannot draw, or {@code null} when the server drew no icon or the client draws them all. */
    static @Nullable Limits limitsOf(Player viewer) {
        if (!Icons.sprites() || !Icons.any()) {
            return null;
        }
        Limits limits = new Limits(!ClientVersions.atLeast(viewer, ClientVersion.V_1_21_9),
                !ClientVersions.atLeast(viewer, ClientVersion.V_1_21_11));
        return limits.any() ? limits : null;
    }

    /** A copy of the item with its icons drawn as glyphs, or {@code null} when it has none to swap. */
    static @Nullable ItemStack downgrade(ItemStack item, Limits limits) {
        boolean changed = false;
        ItemStack copy = item.copy();
        ItemLore lore = copy.getComponentOr(ComponentTypes.LORE, null);
        if (lore != null) {
            List<Component> lines = new ArrayList<>(lore.getLines().size());
            for (Component line : lore.getLines()) {
                Component swapped = swap(line, limits);
                changed |= swapped != line;
                lines.add(swapped);
            }
            if (changed) {
                copy.setComponent(ComponentTypes.LORE, new ItemLore(lines));
            }
        }
        Component custom = copy.getComponentOr(ComponentTypes.CUSTOM_NAME, null);
        if (custom != null) {
            Component swapped = swap(custom, limits);
            if (swapped != custom) {
                copy.setComponent(ComponentTypes.CUSTOM_NAME, swapped);
                changed = true;
            }
        }
        Component name = copy.getComponentOr(ComponentTypes.ITEM_NAME, null);
        if (name != null) {
            Component swapped = swap(name, limits);
            if (swapped != name) {
                copy.setComponent(ComponentTypes.ITEM_NAME, swapped);
                changed = true;
            }
        }
        return changed ? copy : null;
    }

    /** The component with every sprite this client cannot draw replaced by its glyph; the same instance when none. */
    static Component swap(Component component, Limits limits) {
        Component self = component;
        String glyph = glyphFor(component, limits);
        if (glyph != null) {
            // An icon in its own colours is wrapped in white; its glyph takes
            // the colour around it instead, as it does on a server without sprites.
            Style style = NamedTextColor.WHITE.equals(component.color())
                    ? component.style().color(null) : component.style();
            self = Component.text(glyph).style(style).children(component.children());
        }
        List<Component> children = self.children();
        if (children.isEmpty()) {
            return self;
        }
        List<Component> swapped = null;
        for (int i = 0; i < children.size(); i++) {
            Component child = children.get(i);
            Component replaced = swap(child, limits);
            if (replaced != child) {
                if (swapped == null) {
                    swapped = new ArrayList<>(children);
                }
                swapped.set(i, replaced);
            }
        }
        return swapped == null ? self : self.children(swapped);
    }

    /** The glyph standing in for this component, or {@code null} when it is no sprite of ours this client misses. */
    private static @Nullable String glyphFor(Component component, Limits limits) {
        Method contents = CONTENTS.get(component.getClass());
        if (contents == null) {
            return null;
        }
        try {
            Object sprite = contents.invoke(component);
            if (sprite == null) {
                return null;
            }
            Method atlasOf = ATLAS.get(sprite.getClass());
            Method idOf = SPRITE_ID.get(sprite.getClass());
            if (atlasOf == null || idOf == null) {
                return null;
            }
            String atlas = ((net.kyori.adventure.key.Key) atlasOf.invoke(sprite)).asString();
            if (!limits.sprites() && !(limits.itemsAtlas() && atlas.equals(ITEMS))) {
                return null;
            }
            return Icons.fallback(atlas, ((net.kyori.adventure.key.Key) idOf.invoke(sprite)).asString());
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return null;
        }
    }

    private static ClassValue<Method> methodOf(String owner, String name) {
        return new ClassValue<>() {
            @Override
            protected Method computeValue(Class<?> type) {
                Class<?> declaring = find(type, owner);
                if (declaring == null) {
                    return null;
                }
                try {
                    return declaring.getMethod(name);
                } catch (NoSuchMethodException absent) {
                    return null;
                }
            }
        };
    }

    private static @Nullable Class<?> find(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> implemented : current.getInterfaces()) {
                Class<?> found = implemented.getName().equals(name) ? implemented : find(implemented, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
