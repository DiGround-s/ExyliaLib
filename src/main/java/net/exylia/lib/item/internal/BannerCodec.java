package net.exylia.lib.item.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.exylia.lib.item.Banner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The base64 form of a banner design.
 *
 * <p>What a shield editor saves when a player finishes building one, and what
 * the menu that lists their shields reads back. The encoding is
 * ExyliaCommons's, unchanged and deliberately so: there are saved designs in
 * live databases, and a new format would lose every one of them.
 *
 * <p>Compact on purpose — {@code base} and a {@code p} array of
 * {@code {p, c}} — because these strings go into configuration files and
 * database columns by the hundred.
 *
 * <p>Gson ships with the server, so it is used rather than hand-rolled.
 */
public final class BannerCodec {

    private BannerCodec() {
    }

    /**
     * Reads a design.
     *
     * @param base64 the encoded design
     * @return the design, or {@code null} when the string is not one
     */
    public static Banner decode(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        try {
            String json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject root = parsed.getAsJsonObject();
            String base = root.has("base") ? root.get("base").getAsString() : null;
            List<Banner.Layer> layers = new ArrayList<>();
            JsonElement written = root.get("p");
            if (written != null && written.isJsonArray()) {
                for (JsonElement element : written.getAsJsonArray()) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject layer = element.getAsJsonObject();
                    if (layer.has("p") && layer.has("c")) {
                        layers.add(new Banner.Layer(layer.get("p").getAsString(),
                                layer.get("c").getAsString()));
                    }
                }
            }
            Banner banner = new Banner(base, layers);
            return banner.isEmpty() ? null : banner;
        } catch (RuntimeException malformed) {
            // A design that will not decode is a shield that does not render,
            // not an exception in whatever menu was listing it.
            return null;
        }
    }

    /**
     * Writes a design.
     *
     * @param banner the design
     * @return the encoded string
     */
    public static String encode(Banner banner) {
        JsonObject root = new JsonObject();
        if (banner.baseColor() != null) {
            root.addProperty("base", banner.baseColor());
        }
        JsonArray layers = new JsonArray();
        for (Banner.Layer layer : banner.patterns()) {
            JsonObject written = new JsonObject();
            written.addProperty("p", layer.pattern());
            written.addProperty("c", layer.colour());
            layers.add(written);
        }
        root.add("p", layers);
        return Base64.getEncoder()
                .encodeToString(root.toString().getBytes(StandardCharsets.UTF_8));
    }
}
