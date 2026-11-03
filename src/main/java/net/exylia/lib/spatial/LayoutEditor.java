package net.exylia.lib.spatial;

import com.google.gson.JsonObject;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.internal.RecordJson;
import net.exylia.lib.web.WebEditor;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Every registered menu's layout as a web editor subject of kind {@link #KIND}: the browser draws the board as
 * the eyes see it and moves its pieces. Besides the layout's values, the subject carries what that screen needs
 * to draw and check it faithfully ({@code details}): the bundled layout to reset pieces to, sample words for the
 * catalogue's placeholders, the sources and actions the menu answers, the ranges the record clamps to, and the
 * numbers the library's own layout tests use. Applying writes the file through the record (comments kept) and
 * redraws every open copy of the menu. Since 1.464.0.
 */
final class LayoutEditor {

    /** The editor kind of a spatial menu's layout. */
    static final String KIND = "spatial/layout";

    private LayoutEditor() {
    }

    /** The subject id of menu {@code id}'s layout. */
    static String subjectId(String id) {
        String clean = id.replaceAll("[^A-Za-z0-9_-]", "-");
        String out = "spatial-" + clean;
        return out.length() > 48 ? out.substring(0, 48) : out;
    }

    /**
     * Registers the layout of menu {@code id} with the web editor.
     *
     * @param bundled the text of the layout the plugin ships (what "reset to default" brings back), or
     *                {@code null} for the library's own wardrobe
     */
    static void register(SpatialMenus menus, String id, ConfigFile<SpatialLayout> file, SpatialMenu menu,
                         @Nullable Supplier<InputStream> bundled) {
        Plugin plugin = menus.plugin();
        try {
            WebEditor.of(plugin).subject(subjectId(id), file, KIND)
                    .title(title(id))
                    .details(() -> details(plugin, menu, bundled, file.get()))
                    .onApplied(() -> menus.relive(id));
        } catch (RuntimeException unavailable) {
            // No web editor in this runtime (a test without the library enabled): the layout stays editable in game.
            net.exylia.lib.debug.Debug.of(plugin).log("Spatial layout " + id + " not offered to the web editor: "
                    + unavailable.getMessage());
        }
    }

    private static String title(String id) {
        String words = id.replace('-', ' ').replace('_', ' ').strip();
        return (words.isEmpty() ? id : Character.toUpperCase(words.charAt(0)) + words.substring(1)) + " (3D layout)";
    }

    static Map<String, Object> details(Plugin plugin, SpatialMenu menu, @Nullable Supplier<InputStream> bundled,
                                       SpatialLayout layout) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("menu", menu.id());
        // What this library's board understands, for an editor drawing a layout from an older one.
        out.put("features", List.of("visible", "samples"));
        out.put("defaults", defaults(plugin, bundled));
        Map<String, String> samples = samples();
        samples.putAll(own(plugin, menu, layout));
        out.put("samples", samples);
        SpatialCatalog.Words words = new SpatialCatalog.Words();
        out.put("tabs", List.of("All", "Meme", "Void", "Cataclysm", "Lovestruck", "Cosmic", words.tabFavourites()));
        out.put("tabFormat", words.tab());
        out.put("tabActiveFormat", words.tabActive());
        List<String> sources = new ArrayList<>(menu.sources.keySet());
        if (menu.catalog && !sources.contains(SpatialCatalog.TABS)) sources.add(SpatialCatalog.TABS);
        out.put("sources", sources);
        LinkedHashSet<String> actions = new LinkedHashSet<>(menu.actions.keySet());
        actions.addAll(List.of(MenuSession.SAVE, MenuSession.REVERT, MenuSession.UNDO, MenuSession.LEAVE, MenuSession.INTRO));
        out.put("actions", new ArrayList<>(actions));
        out.put("catalog", menu.catalog);
        out.put("ranges", RANGES);
        out.put("rules", rules());
        return out;
    }

    /** The plugin's samples for its own placeholders ({@link SpatialMenu#samples}), keyed {@code %name%}. */
    private static Map<String, String> own(Plugin plugin, SpatialMenu menu, SpatialLayout layout) {
        Map<String, String> out = new LinkedHashMap<>();
        var supplier = menu.samples;
        if (supplier == null) return out;
        try {
            Map<String, ?> given = supplier.apply(layout);
            if (given == null) return out;
            given.forEach((key, value) -> {
                if (key == null || key.isBlank() || value == null) return;
                String token = key.startsWith("%") && key.endsWith("%") && key.length() > 2 ? key : "%" + key + "%";
                out.put(token, String.valueOf(value));
            });
        } catch (RuntimeException failure) {
            net.exylia.lib.debug.Debug.of(plugin).warn("The samples of spatial menu " + menu.id()
                    + " could not be read: " + failure.getMessage());
        }
        return out;
    }

    /** The bundled layout as the record reads it, shaped like the subject's values; the library's wardrobe without one. */
    private static Object defaults(Plugin plugin, @Nullable Supplier<InputStream> bundled) {
        if (bundled != null) {
            try (InputStream in = bundled.get()) {
                if (in != null) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    return RecordJson.fromYaml(SpatialLayout.class, plugin.getName(), text, new SpatialLayout());
                }
            } catch (IOException | org.bukkit.configuration.InvalidConfigurationException | RuntimeException unreadable) {
                // Falls through to the library's own.
            }
        }
        JsonObject library = RecordJson.write(SpatialLayout.class, plugin.getName(), new SpatialLayout());
        return library;
    }

    /**
     * What the catalogue's words read in English, with a made-up offer (Lightning Strike, Epic, $500.00), so the
     * board previews as players see it. A placeholder the plugin fills itself is not here: the editor shows it as
     * written.
     */
    static Map<String, String> samples() {
        SpatialCatalog.Words words = new SpatialCatalog.Words();
        Map<String, String> fill = Map.of("%name%", "Lightning Strike", "%price%", "$500.00", "%count%", "1",
                "%keys%", "3", "%category%", "All", "%part%", "Lightning Strike", "%unlock%", words.unlock());
        Map<String, String> out = new LinkedHashMap<>();
        out.put("%catalog_card_name%", "{highlight}Lightning Strike");
        out.put("%catalog_card_title%", "{letters}&lLightning Strike");
        out.put("%catalog_card_meta%", "{accent}Epic {letters_black}· " + words.cardEquipped());
        out.put("%catalog_card_subtitle%", "{accent}Epic");
        out.put("%catalog_card_status%", words.cardEquipped());
        out.put("%catalog_card_unlock%", "");
        out.put("%catalog_action%", words.actionEquip());
        out.put("%catalog_hint%", words.hintIdle());
        out.put("%catalog_save%", words.save());
        out.put("%catalog_status%", words.statusSaved());
        out.put("%catalog_total%", "$500.00");
        out.put("%catalog_name%", "Lightning Strike");
        out.put("%catalog_revert%", words.revert());
        out.put("%catalog_unequip%", words.unequip());
        out.put("%catalog_favourite%", words.favourite());
        out.put("%catalog_filter%", words.filter());
        out.put("%catalog_crate%", words.crate());
        out.put("%catalog_leave%", words.leave());
        out.put("%catalog_help%", words.help());
        out.put("%catalog_intro_shelf%", words.introShelf());
        out.put("%catalog_intro_preview%", words.introPreview());
        out.put("%catalog_intro_action%", words.introAction());
        out.put("%catalog_intro_more%", words.introMore());
        out.put("%page%", "1");
        out.put("%pages%", "14");
        out.put("%dots%", "{secondary_light}● {letters_black}○ ○ ○");
        out.replaceAll((key, value) -> {
            String filled = value;
            for (Map.Entry<String, String> entry : fill.entrySet()) filled = filled.replace(entry.getKey(), entry.getValue());
            return filled;
        });
        return out;
    }

    /**
     * The bounds the editor keeps a value in, by dotted path ({@code *} any panel), {@code null} for none: what the
     * record clamps to (alphas, rings, slots), and limits past which a board no longer reads (distance, scales).
     */
    private static final Map<String, List<Double>> RANGES = ranges();

    private static Map<String, List<Double>> ranges() {
        Map<String, List<Double>> out = new LinkedHashMap<>();
        java.util.function.BiConsumer<String, double[]> put = (path, bounds) -> {
            List<Double> pair = new ArrayList<>(2);
            pair.add(Double.isNaN(bounds[0]) ? null : bounds[0]);
            pair.add(Double.isNaN(bounds[1]) ? null : bounds[1]);
            out.put(path, pair);
        };
        double none = Double.NaN;
        put.accept("anchor.distance", new double[]{2, 12});
        put.accept("anchor.lift", new double[]{-3, 3});
        put.accept("backdrop.alpha", new double[]{0, 255});
        put.accept("backdrop.rings", new double[]{0, 32});
        put.accept("backdrop.feather", new double[]{0, none});
        put.accept("theme.glass-alpha", new double[]{0, 255});
        put.accept("theme.backing-alpha", new double[]{0, 255});
        put.accept("theme.glass-tint-strength", new double[]{0, 1});
        put.accept("theme.title-scale", new double[]{0.05, 4});
        put.accept("theme.text-scale", new double[]{0.05, 4});
        put.accept("theme.small-scale", new double[]{0.05, 4});
        put.accept("theme.min-glyph-degrees", new double[]{0, 5});
        put.accept("theme.hover-grow", new double[]{1, 3});
        put.accept("preview.scale", new double[]{0.05, 4});
        put.accept("preview.effect-scale", new double[]{0.01, 16});
        put.accept("preview.turn-pause", new double[]{0, none});
        put.accept("carousel.slots", new double[]{1, 24});
        put.accept("carousel.rows", new double[]{1, 6});
        put.accept("panels.*.content.columns", new double[]{1, 16});
        put.accept("panels.*.content.rows", new double[]{1, 16});
        put.accept("panels.*.turn", new double[]{-90, 90});
        return out;
    }

    /** The numbers the library's own layout tests check a board against ({@code CentreStageLayoutTest}). */
    private static Map<String, Object> rules() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eyes", 1.62);
        out.put("floorMargin", MenuSession.FLOOR_MARGIN);
        out.put("viewAcross", 80.0);
        out.put("viewTall", 47.0);
        out.put("readableDegrees", 1.06);
        out.put("capitalPixels", net.exylia.lib.spatial.internal.Geometry.CAPITAL_PIXELS);
        out.put("smallCapitalPixels", net.exylia.lib.spatial.internal.Geometry.SMALL_CAPITAL_PIXELS);
        out.put("pixel", net.exylia.lib.spatial.internal.Geometry.PIXEL);
        out.put("textHeight", net.exylia.lib.stage.Stage.TEXT_HEIGHT);
        out.put("minContrast", 3.0);
        out.put("sky", "#8ab4ff");
        out.put("ground", "#4d4d50");
        out.put("minRings", net.exylia.lib.spatial.internal.Geometry.MIN_RINGS);
        return out;
    }
}
