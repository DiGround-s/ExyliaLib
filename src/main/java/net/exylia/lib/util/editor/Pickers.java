package net.exylia.lib.util.editor;

import net.exylia.lib.input.Inputs;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Choosing one thing out of a list the server owns.
 *
 * <pre>{@code
 * editors.pick().particle(player)
 *        .thenAccept(name -> name.ifPresent(entry::setParticle));
 * }</pre>
 *
 * <p>Every one of these is a {@code SearchInput} underneath, so paging,
 * filtering and every transport were already solved. What this adds is the list
 * — read from the registry rather than from an enum's {@code values()}, because
 * several of these stopped being enums and a data pack can add to any of them.
 *
 * <p>The answer is the name, not the object: a name is what goes into a config
 * column, and a caller that wants the object looks it up once.
 *
 * @since 1.56.0
 */
@SuppressWarnings("deprecation")
public final class Pickers {

    /** What the colour list calls the option that opens a text box. */
    private static final String CUSTOM = " custom";

    /** The colour names every colour key in the ecosystem already accepts. */
    private static final List<String> COLOUR_NAMES = List.of(
            "white", "silver", "gray", "black", "red", "maroon", "yellow", "olive",
            "lime", "green", "aqua", "teal", "blue", "navy", "fuchsia", "purple", "orange");

    private final Plugin plugin;

    Pickers(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * Which particle.
     *
     * @param viewer who is choosing
     * @return the particle name, or nothing
     */
    public @NotNull CompletionStage<Optional<String>> particle(@NotNull Player viewer) {
        List<String> names = new ArrayList<>();
        for (org.bukkit.Particle particle : org.bukkit.Particle.values()) {
            names.add(particle.name());
        }
        return search(viewer, "{primary}&lWHICH PARTICLE?", names, Material.FIREWORK_ROCKET);
    }

    /**
     * Which sound.
     *
     * @param viewer who is choosing
     * @return the sound name, or nothing
     */
    public @NotNull CompletionStage<Optional<String>> sound(@NotNull Player viewer) {
        List<String> names = new ArrayList<>();
        for (org.bukkit.Sound sound : Registry.SOUNDS) {
            // Asked of the registry rather than of the sound: Sound.getKey() is
            // marked for removal, and the registry has always known the answer.
            NamespacedKey key = Registry.SOUNDS.getKey(sound);
            if (key != null) {
                names.add(keyOf(key));
            }
        }
        return search(viewer, "{primary}&lWHICH SOUND?", names, Material.NOTE_BLOCK);
    }

    /**
     * Which enchantment.
     *
     * @param viewer who is choosing
     * @return the enchantment name, or nothing
     */
    public @NotNull CompletionStage<Optional<String>> enchantment(@NotNull Player viewer) {
        List<String> names = new ArrayList<>();
        for (Enchantment enchantment : Registry.ENCHANTMENT) {
            NamespacedKey key = Registry.ENCHANTMENT.getKey(enchantment);
            if (key != null) {
                names.add(keyOf(key));
            }
        }
        return search(viewer, "{primary}&lWHICH ENCHANTMENT?", names, Material.ENCHANTED_BOOK);
    }

    /**
     * Which potion effect.
     *
     * @param viewer who is choosing
     * @return the effect name, or nothing
     */
    public @NotNull CompletionStage<Optional<String>> potionEffect(@NotNull Player viewer) {
        List<String> names = new ArrayList<>();
        for (PotionEffectType type : Registry.EFFECT) {
            NamespacedKey key = Registry.EFFECT.getKey(type);
            if (key != null) {
                names.add(keyOf(key));
            }
        }
        return search(viewer, "{primary}&lWHICH EFFECT?", names, Material.POTION);
    }

    /**
     * Which material.
     *
     * @param viewer who is choosing
     * @return the material name, or nothing
     */
    public @NotNull CompletionStage<Optional<String>> material(@NotNull Player viewer) {
        List<String> names = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!material.isLegacy() && material.isItem() && material != Material.AIR) {
                names.add(material.name());
            }
        }
        return search(viewer, "{primary}&lWHICH MATERIAL?", names, Material.CHEST);
    }

    /**
     * Which colour.
     *
     * <p>Answers with what a config writes: one of the vanilla colour names, or
     * a {@code #rrggbb} value when the viewer chose to type one. Both are read
     * by the same parser every colour key in the ecosystem already uses, so the
     * answer goes straight into a column.
     *
     * @param viewer who is choosing
     * @return the colour, as written, or nothing
     */
    public @NotNull CompletionStage<Optional<String>> colour(@NotNull Player viewer) {
        List<String> options = new ArrayList<>(COLOUR_NAMES.size() + 1);
        options.addAll(COLOUR_NAMES);
        options.add(CUSTOM);
        return Inputs.of(plugin).search(viewer, "{primary}&lWHICH COLOUR?", options)
                .label(name -> name.equals(CUSTOM)
                        ? "{highlight}&lTYPE A HEX VALUE"
                        : "{primary}&l" + name.toUpperCase(Locale.ROOT))
                .key(name -> name)
                .icon(Pickers::dye)
                .open()
                .thenCompose(result -> {
                    if (!result.completed()) {
                        return CompletableFuture.completedFuture(Optional.<String>empty());
                    }
                    return result.value().equals(CUSTOM)
                            ? hex(viewer)
                            : CompletableFuture.completedFuture(Optional.of(result.value()));
                });
    }

    private CompletionStage<Optional<String>> hex(Player viewer) {
        return Inputs.of(plugin).text(viewer, "{primary}Type a colour as #rrggbb")
                .validate(Pickers::isHex, "Write it as #rrggbb, such as #8a51c4.")
                .open()
                .thenApply(result -> result.completed()
                        ? Optional.of(result.value().trim().toLowerCase(Locale.ROOT))
                        : Optional.empty());
    }

    private CompletionStage<Optional<String>> search(Player viewer, String prompt,
                                                     List<String> names, Material icon) {
        if (names.isEmpty()) {
            // A registry with nothing in it is a server that is not ready, not a
            // question worth showing an empty page for.
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return Inputs.of(plugin).search(viewer, prompt, names)
                .label(name -> name.toLowerCase(Locale.ROOT).replace('_', ' '))
                .key(name -> name)
                .icon(name -> icon)
                .open()
                .thenApply(result -> result.completed()
                        ? Optional.of(result.value().toUpperCase(Locale.ROOT))
                        : Optional.empty());
    }

    private static boolean isHex(String value) {
        String text = value.trim();
        if (text.length() != 7 || text.charAt(0) != '#') {
            return false;
        }
        try {
            Color.fromRGB(Integer.parseInt(text.substring(1), 16));
            return true;
        } catch (RuntimeException notAColour) {
            return false;
        }
    }

    private static Material dye(String name) {
        Material dye = Material.matchMaterial(name.toUpperCase(Locale.ROOT) + "_DYE");
        return dye != null ? dye : Material.WHITE_DYE;
    }

    private static String keyOf(NamespacedKey key) {
        return key.getKey().toUpperCase(Locale.ROOT);
    }
}
