package net.exylia.lib.ability.internal;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Which block types a line names, compiled once into an {@link EnumSet}: the
 * {@code block} condition and {@code break-area}'s {@code only}.
 *
 * <pre>{@code
 * block: { types: [DIAMOND_ORE], tags: [minecraft:logs] }
 * block: [DIAMOND_ORE, '#minecraft:logs']   # the short form: '#' marks a tag
 * }</pre>
 *
 * Tags are expanded at load, so a match is one bit test. A tag a datapack
 * changes later is seen on the next reload.
 */
public final class BlockMatcher {

    private BlockMatcher() {
        throw new AssertionError("No instances.");
    }

    /**
     * Compiles what a line wrote.
     *
     * @param written  a map with {@code types} and {@code tags}, a list, or one value
     * @param problems told about each name that is neither a block nor a tag
     * @return the matching types, empty when nothing readable was written
     */
    public static @NotNull Set<Material> compile(@Nullable Object written, @NotNull Consumer<String> problems) {
        return compile(written, problems, BlockMatcher::bukkitTag);
    }

    /** {@link #compile(Object, Consumer)} with the tag lookup handed in; a seam for tests. */
    static @NotNull Set<Material> compile(@Nullable Object written, @NotNull Consumer<String> problems,
                                          @NotNull Function<String, @Nullable Collection<Material>> tags) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        if (written instanceof Map<?, ?> map) {
            for (String name : list(map.get("types"))) type(name, out, problems);
            for (String name : list(map.get("tags"))) tag(name, out, problems, tags);
        } else {
            for (String name : list(written)) {
                if (name.startsWith("#")) tag(name.substring(1), out, problems, tags);
                else type(name, out, problems);
            }
        }
        return out;
    }

    private static void type(String name, Set<Material> out, Consumer<String> problems) {
        Material material = Material.matchMaterial(name);
        if (material == null) problems.accept("unknown block " + name + "; ignored");
        else out.add(material);
    }

    private static void tag(String name, Set<Material> out, Consumer<String> problems,
                            Function<String, Collection<Material>> tags) {
        Collection<Material> members = tags.apply(name.toLowerCase(Locale.ROOT));
        if (members == null) problems.accept("unknown block tag " + name + "; ignored");
        else out.addAll(members);
    }

    private static List<String> list(@Nullable Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).map(String::trim).toList();
        return List.of(String.valueOf(value).trim());
    }

    private static @Nullable Collection<Material> bukkitTag(String name) {
        NamespacedKey key = NamespacedKey.fromString(name);
        if (key == null || Bukkit.getServer() == null) return null;
        Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
        return tag == null ? null : tag.getValues();
    }
}
