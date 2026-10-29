package net.exylia.lib.ragdoll.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Turns a Blockbench project's clips into the Bedrock animations its own export
 * would write, so a {@code .bbmodel} is read by exactly the code that reads an
 * exported {@code .animation.json}.
 *
 * <h2>What the export does, and so what this does</h2>
 * Mirrors Blockbench's {@code Keyframe.compileBedrockKeyframe} and the Bedrock
 * codec's {@code compileAnimation}:
 * <ul>
 *   <li>A project keeps its numbers the way its viewport shows them; the export
 *       negates the {@code x} of a position and the {@code x} and {@code y} of a
 *       rotation. Scale is written as kept.</li>
 *   <li>A key after a {@code step} key is written as {@code pre} (the step's
 *       value) and {@code post}, so the step holds until it.</li>
 *   <li>A {@code catmullrom} key carries {@code lerp_mode}, with a {@code pre}
 *       when the key before it is not a spline (or there is none and it is not at
 *       the start).</li>
 *   <li>A key of two data points is a {@code pre} and a {@code post}.</li>
 *   <li>Bedrock has no bezier, so the export bakes a bezier span into straight
 *       keys at the clip's snapping (24 a second unless the project says
 *       otherwise); so does this, from the same handles. The curve is solved for
 *       the moment exactly where Blockbench picks the nearest of 200 points, which
 *       differs by less than either can show. Blockbench also drops baked keys
 *       that sit on a straight line; this keeps them, which plays the same.</li>
 *   <li>{@code loop} {@code "loop"} is {@code loop: true}, {@code "hold"} is
 *       {@code hold_on_last_frame}, {@code "once"} is nothing; {@code length} is
 *       {@code animation_length}.</li>
 * </ul>
 * A bone is named by its group in the outliner, as the export names it, or by
 * the animator's own name when the group is gone. Effect and timeline animators
 * (sounds, particles, scripts) have nothing for a body to play and are left out.
 * Molang stays Molang and is reported by the Bedrock reader, keyframe left out.
 *
 * <p>Pure: JSON in, JSON out.
 *
 * @since 1.401.0
 */
@ApiStatus.Internal
public final class BlockbenchProject {

    private BlockbenchProject() {
    }

    /** One keyframe of one channel, as the project keeps it. */
    private record Frame(double time, String interpolation, JsonElement[][] points,
                         double[] leftTime, double[] leftValue, double[] rightTime, double[] rightValue) {

        /** A data point's three entries; the first point for one that has no second. */
        JsonElement[] point(int index) {
            return points[Math.min(index, points.length - 1)];
        }

        boolean is(String mode) {
            return interpolation.equals(mode);
        }
    }

    /**
     * The project's clips as a Bedrock {@code animations} object.
     *
     * @param project  the {@code .bbmodel}'s root
     * @param file     its name, for the problems
     * @param problems where whatever could not be read is described
     * @return each clip by its name, as the export writes it
     */
    public static JsonObject toBedrock(JsonObject project, String file, Consumer<String> problems) {
        Map<String, String> groups = new HashMap<>();
        if (project.has("groups") && project.get("groups").isJsonArray()) {
            for (JsonElement group : project.getAsJsonArray("groups")) {
                name(group, groups);
            }
        }
        if (project.has("outliner") && project.get("outliner").isJsonArray()) {
            for (JsonElement node : project.getAsJsonArray("outliner")) {
                outline(node, groups);
            }
        }
        JsonObject animations = new JsonObject();
        for (JsonElement element : project.getAsJsonArray("animations")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject clip = element.getAsJsonObject();
            String name = text(clip, "name", "animation");
            animations.add(name, clip(clip, groups, file + " (" + name + ")", problems));
        }
        return animations;
    }

    private static void name(JsonElement group, Map<String, String> groups) {
        if (group.isJsonObject() && group.getAsJsonObject().has("uuid") && group.getAsJsonObject().has("name")) {
            groups.put(group.getAsJsonObject().get("uuid").getAsString(),
                    group.getAsJsonObject().get("name").getAsString());
        }
    }

    /** Groups in the outliner, which older projects keep there with their names. */
    private static void outline(JsonElement node, Map<String, String> groups) {
        if (!node.isJsonObject()) {
            return;
        }
        name(node, groups);
        JsonObject group = node.getAsJsonObject();
        if (group.has("children") && group.get("children").isJsonArray()) {
            for (JsonElement child : group.getAsJsonArray("children")) {
                outline(child, groups);
            }
        }
    }

    private static JsonObject clip(JsonObject clip, Map<String, String> groups, String where,
                                   Consumer<String> problems) {
        JsonObject out = new JsonObject();
        String loop = text(clip, "loop", "once").toLowerCase(Locale.ROOT);
        double length = number(clip.get("length"), 0);
        double snapping = number(clip.get("snapping"), 24);
        if (!(snapping > 0)) {
            snapping = 24;
        }
        JsonObject bones = new JsonObject();
        double latest = 0;
        JsonObject animators = clip.has("animators") && clip.get("animators").isJsonObject()
                ? clip.getAsJsonObject("animators") : new JsonObject();
        for (Map.Entry<String, JsonElement> entry : animators.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject animator = entry.getValue().getAsJsonObject();
            String type = text(animator, "type", "bone");
            if (!type.equals("bone")) {
                continue;
            }
            String bone = groups.getOrDefault(entry.getKey(), text(animator, "name", entry.getKey()));
            Map<String, List<Frame>> channels = new HashMap<>();
            JsonArray keyframes = animator.has("keyframes") && animator.get("keyframes").isJsonArray()
                    ? animator.getAsJsonArray("keyframes") : new JsonArray();
            for (JsonElement key : keyframes) {
                Frame frame = key.isJsonObject() ? frame(key.getAsJsonObject()) : null;
                String channel = key.isJsonObject() ? text(key.getAsJsonObject(), "channel", "") : "";
                if (frame == null || !(channel.equals("rotation") || channel.equals("position")
                        || channel.equals("scale"))) {
                    continue;
                }
                channels.computeIfAbsent(channel, ignored -> new ArrayList<>()).add(frame);
                latest = Math.max(latest, frame.time());
            }
            JsonObject tag = new JsonObject();
            for (String channel : new String[]{"rotation", "position", "scale"}) {
                List<Frame> frames = channels.get(channel);
                if (frames != null) {
                    frames.sort((one, other) -> Double.compare(one.time(), other.time()));
                    tag.add(channel, channel(channel, frames, snapping, where, problems));
                }
            }
            if (!tag.isEmpty()) {
                bones.add(bone, tag);
            }
        }
        if (loop.equals("hold")) {
            out.addProperty("loop", "hold_on_last_frame");
        } else if (loop.equals("loop") || Math.max(length, latest) == 0) {
            out.addProperty("loop", true);
        }
        if (length > 0) {
            out.addProperty("animation_length", length);
        }
        out.add("bones", bones);
        return out;
    }

    private static Frame frame(JsonObject key) {
        if (!key.has("data_points") || !key.get("data_points").isJsonArray()) {
            return null;
        }
        List<JsonElement[]> points = new ArrayList<>();
        for (JsonElement point : key.getAsJsonArray("data_points")) {
            if (point.isJsonObject()) {
                JsonObject axes = point.getAsJsonObject();
                points.add(new JsonElement[]{axes.get("x"), axes.get("y"), axes.get("z")});
            }
        }
        if (points.isEmpty()) {
            return null;
        }
        return new Frame(number(key.get("time"), 0), text(key, "interpolation", "linear").toLowerCase(Locale.ROOT),
                points.toArray(new JsonElement[0][]),
                vector(key.get("bezier_left_time"), -0.1), vector(key.get("bezier_left_value"), 0),
                vector(key.get("bezier_right_time"), 0.1), vector(key.get("bezier_right_value"), 0));
    }

    /** One channel's keys, written the way {@code compileBedrockKeyframe} writes them. */
    private static JsonObject channel(String channel, List<Frame> frames, double snapping, String where,
                                      Consumer<String> problems) {
        JsonObject out = new JsonObject();
        for (int index = 0; index < frames.size(); index++) {
            Frame frame = frames.get(index);
            Frame previous = index > 0 ? frames.get(index - 1) : null;
            JsonElement written;
            if (frame.is("catmullrom")) {
                boolean pre = previous == null ? frame.time() > 0 : !previous.is("catmullrom");
                JsonObject key = new JsonObject();
                if (pre) {
                    key.add("pre", array(channel, frame.point(0)));
                }
                key.add("post", array(channel, frame.point(pre ? 1 : 0)));
                key.addProperty("lerp_mode", "catmullrom");
                written = key;
            } else if (frame.points().length == 1) {
                if (previous != null && previous.is("step")) {
                    JsonObject key = new JsonObject();
                    key.add("pre", array(channel, previous.point(1)));
                    key.add("post", array(channel, frame.point(0)));
                    written = key;
                } else {
                    written = array(channel, frame.point(0));
                }
            } else {
                JsonObject key = new JsonObject();
                key.add("pre", array(channel, frame.point(0)));
                key.add("post", array(channel, frame.point(1)));
                written = key;
            }
            out.add(time(frame.time()), written);
            Frame next = index + 1 < frames.size() ? frames.get(index + 1) : null;
            if (next != null && (frame.is("bezier") || next.is("bezier"))) {
                bake(channel, frame, next, snapping, out, where, problems);
            }
        }
        return out;
    }

    /**
     * Straight keys along a bezier span, at the clip's snapping, as the export bakes
     * them; the key that ends the span is written after and wins its moment.
     */
    private static void bake(String channel, Frame from, Frame to, double snapping, JsonObject out, String where,
                             Consumer<String> problems) {
        double[] start = new double[3];
        double[] end = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            start[axis] = number(from.point(1)[axis], Double.NaN);
            end[axis] = number(to.point(0)[axis], Double.NaN);
            if (Double.isNaN(start[axis]) || Double.isNaN(end[axis])) {
                problems.accept(where + ": a bezier curve between Molang keys at " + from.time()
                        + "s is played straight");
                return;
            }
        }
        double interval = 1 / snapping;
        double gap = to.time() - from.time();
        for (double time = from.time() + interval; time < to.time() + interval / 2; time += interval) {
            double moment = Math.round(time * snapping) / snapping;
            JsonArray value = new JsonArray();
            for (int axis = 0; axis < 3; axis++) {
                double rightTime = Math.clamp(from.rightTime()[axis], 0, gap);
                double leftTime = Math.clamp(to.leftTime()[axis], -gap, 0);
                double solved = bezier(from.time(), start[axis], from.time() + rightTime,
                        start[axis] + from.rightValue()[axis], to.time() + leftTime,
                        end[axis] + to.leftValue()[axis], to.time(), end[axis], moment);
                value.add(flips(channel, axis) ? -solved : solved);
            }
            out.add(time(moment), value);
        }
    }

    /**
     * The value of a cubic bezier through four points of (time, value) at one
     * moment: the curve's parameter is found by bisection on its time.
     */
    static double bezier(double t0, double v0, double t1, double v1, double t2, double v2, double t3, double v3,
                         double moment) {
        double low = 0;
        double high = 1;
        double at = 0.5;
        for (int step = 0; step < 40; step++) {
            at = (low + high) / 2;
            if (cubic(t0, t1, t2, t3, at) < moment) {
                low = at;
            } else {
                high = at;
            }
        }
        return cubic(v0, v1, v2, v3, at);
    }

    private static double cubic(double p0, double p1, double p2, double p3, double at) {
        double rest = 1 - at;
        return rest * rest * rest * p0 + 3 * rest * rest * at * p1 + 3 * rest * at * at * p2 + at * at * at * p3;
    }

    /** Whether the export negates this axis of this channel. */
    private static boolean flips(String channel, int axis) {
        return axis == 0 && (channel.equals("position") || channel.equals("rotation"))
                || axis == 1 && channel.equals("rotation");
    }

    /** A data point as the export writes it: numbers negated where it negates them, Molang as it is. */
    private static JsonArray array(String channel, JsonElement[] point) {
        JsonArray array = new JsonArray();
        for (int axis = 0; axis < 3; axis++) {
            JsonElement entry = point[axis];
            double value = number(entry, Double.NaN);
            if (Double.isNaN(value)) {
                // Molang: the Bedrock reader names it and leaves the keyframe out.
                array.add(entry == null ? new JsonPrimitive(0) : entry);
            } else {
                array.add(flips(channel, axis) && value != 0 ? -value : value);
            }
        }
        return array;
    }

    private static String time(double seconds) {
        return Double.toString(Math.round(seconds * 10_000) / 10_000.0);
    }

    private static double[] vector(JsonElement element, double otherwise) {
        double[] vector = {otherwise, otherwise, otherwise};
        if (element != null && element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int axis = 0; axis < 3 && axis < array.size(); axis++) {
                vector[axis] = number(array.get(axis), otherwise);
            }
        }
        return vector;
    }

    private static String text(JsonObject object, String key, String otherwise) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : otherwise;
    }

    /** A number, a number written as text, or blank for zero, as Blockbench reads one. */
    private static double number(JsonElement element, double otherwise) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return element == null ? 0 : otherwise;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            return primitive.getAsDouble();
        }
        String text = primitive.getAsString().trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException molang) {
            return otherwise;
        }
    }
}
