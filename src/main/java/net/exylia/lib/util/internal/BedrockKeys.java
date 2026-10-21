package net.exylia.lib.util.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reads one channel of a Bedrock or GeckoLib animation, the format Blockbench
 * exports: a fixed vector, or keyframes by time in seconds, each a vector or
 * {@code pre} and {@code post} vectors with a {@code lerp_mode} and an
 * {@code easing}.
 *
 * <p>Shared by the ragdoll importer, whose bones are a player's, and the model
 * module, whose bones are whatever the model has: the keys are read once, the
 * same way, and each decides what a bone is. Molang only Bedrock can play is
 * reported and its keyframe left out.
 */
@ApiStatus.Internal
public final class BedrockKeys {

    private BedrockKeys() {
    }

    /** Where each key of a channel goes: its moment, its three numbers, its curve and that curve's argument. */
    @FunctionalInterface
    public interface Keyed {

        void key(long millis, double[] vector, Ease ease, double arg);
    }

    /**
     * Walks one channel of a bone: a fixed vector, or keyframes by time.
     *
     * <p>A keyframe is a vector, or {@code pre} and {@code post} vectors with a
     * {@code lerp_mode} and an {@code easing}. The two sides of a keyframe that
     * jumps are two keys at one moment: the first arrived at, the second left
     * from.
     */
    public static void timeline(JsonElement channel, String where, Consumer<String> problems, Keyed keyed) {
        if (channel == null || channel.isJsonNull()) {
            return;
        }
        if (channel.isJsonArray() || channel.isJsonPrimitive()) {
            double[] vector = vector(channel, where, problems);
            if (vector != null) {
                keyed.key(0, vector, Ease.LINEAR, Double.NaN);
            }
            return;
        }
        if (!channel.isJsonObject()) {
            return;
        }
        List<Map.Entry<Double, JsonElement>> frames = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : channel.getAsJsonObject().entrySet()) {
            if (entry.getKey().equals("vector")) {
                double[] vector = vector(entry.getValue(), where, problems);
                if (vector != null) {
                    keyed.key(0, vector, Ease.LINEAR, Double.NaN);
                }
                continue;
            }
            if (entry.getKey().equals("easing") || entry.getKey().equals("easingArgs")) {
                continue;
            }
            try {
                frames.add(Map.entry(Double.parseDouble(entry.getKey()), entry.getValue()));
            } catch (NumberFormatException notATime) {
                problems.accept(where + ": \"" + entry.getKey() + "\" is not a time in seconds");
            }
        }
        frames.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Double, JsonElement> frame : frames) {
            long millis = Math.round(frame.getKey() * 1000);
            JsonElement value = frame.getValue();
            if (!value.isJsonObject()) {
                double[] vector = vector(value, where, problems);
                if (vector != null) {
                    keyed.key(millis, vector, Ease.LINEAR, Double.NaN);
                }
                continue;
            }
            JsonObject key = value.getAsJsonObject();
            Ease ease = Ease.LINEAR;
            if (key.has("lerp_mode")) {
                String mode = key.get("lerp_mode").getAsString().trim().toLowerCase(Locale.ROOT);
                ease = switch (mode) {
                    case "catmullrom" -> Ease.SMOOTH;
                    case "step" -> Ease.HOLD;
                    default -> Ease.LINEAR;
                };
            }
            if (key.has("easing")) {
                Ease named = Ease.of(key.get("easing").getAsString());
                if (named == null) {
                    problems.accept(where + ": there is no curve called \"" + key.get("easing").getAsString()
                            + "\"; it is played straight");
                } else {
                    ease = named;
                }
            }
            double arg = Double.NaN;
            if (key.has("easingArgs") && key.get("easingArgs").isJsonArray()
                    && !key.getAsJsonArray("easingArgs").isEmpty()) {
                arg = number(key.getAsJsonArray("easingArgs").get(0), Double.NaN);
            }
            double[] pre = key.has("pre") ? vector(unwrap(key.get("pre")), where, problems) : null;
            double[] vector = key.has("vector") ? vector(key.get("vector"), where, problems) : null;
            double[] post = key.has("post") ? vector(unwrap(key.get("post")), where, problems) : null;
            if (pre != null) {
                keyed.key(millis, pre, ease, arg);
            }
            if (vector != null) {
                keyed.key(millis, vector, pre != null ? Ease.LINEAR : ease, arg);
            }
            if (post != null && (pre == null && vector == null || !same(post, vector != null ? vector : pre))) {
                // Left from rather than arrived at: the moment is already reached.
                keyed.key(millis, post, pre == null && vector == null ? ease : Ease.LINEAR, arg);
            }
        }
    }

    private static JsonElement unwrap(JsonElement side) {
        return side.isJsonObject() && side.getAsJsonObject().has("vector") ? side.getAsJsonObject().get("vector") : side;
    }

    private static boolean same(double[] one, double[] other) {
        return one[0] == other[0] && one[1] == other[1] && one[2] == other[2];
    }

    /** Three numbers, or one standing for all three; {@code null} when they cannot be read. */
    private static double[] vector(JsonElement element, String where, Consumer<String> problems) {
        if (element.isJsonPrimitive()) {
            double one = number(element, Double.NaN);
            if (Double.isNaN(one)) {
                molang(element, where, problems);
                return null;
            }
            return new double[]{one, one, one};
        }
        if (!element.isJsonArray()) {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        double[] vector = new double[3];
        for (int index = 0; index < 3; index++) {
            if (index >= array.size()) {
                return null;
            }
            vector[index] = number(array.get(index), Double.NaN);
            if (Double.isNaN(vector[index])) {
                molang(array.get(index), where, problems);
                return null;
            }
        }
        return vector;
    }

    private static void molang(JsonElement element, String where, Consumer<String> problems) {
        problems.accept(where + ": \"" + element.getAsString() + "\" is Molang, which only Bedrock can play;"
                + " that keyframe is left out");
    }

    public static double number(JsonElement element, double otherwise) {
        try {
            return element.getAsDouble();
        } catch (RuntimeException notANumber) {
            return otherwise;
        }
    }
}
