package net.exylia.lib.ragdoll.internal;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.ApiStatus;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The Blockbench template projects {@code RagdollAnimations.writeTemplates} writes:
 * a player rig whose bones are named the way the importer reads them.
 *
 * <p>Built in Blockbench's own coordinates for a Bedrock model (the export negates
 * {@code x}, so the right arm is on {@code +x}), in pixels, pivoted where the
 * vanilla player model turns: the head and the torso at the neck, the arms at the
 * shoulders (half a pixel lower for slim arms), the legs at the hips. Every limb and
 * the torso is cut in two, the half beyond the hinge in its {@code _bend} group; the
 * head and arms hang off {@code body} rather than off the torso, as they do when it
 * plays. Ids are derived from names, so the same template is the same file.
 *
 * @since 1.401.0
 */
@ApiStatus.Internal
public final class RagdollTemplates {

    private RagdollTemplates() {
    }

    /**
     * A template project's text.
     *
     * @param slim 3-pixel arms instead of 4
     * @return the {@code .bbmodel} JSON
     */
    public static String project(boolean slim) {
        String name = slim ? "player_slim" : "player_classic";
        int arm = slim ? 3 : 4;
        double shoulder = slim ? 21.5 : 22;
        JsonObject root = new JsonObject();
        JsonObject meta = new JsonObject();
        meta.addProperty("format_version", "4.10");
        meta.addProperty("model_format", "bedrock");
        meta.addProperty("box_uv", true);
        root.add("meta", meta);
        root.addProperty("name", name);
        root.addProperty("model_identifier", "player");
        JsonObject resolution = new JsonObject();
        resolution.addProperty("width", 64);
        resolution.addProperty("height", 64);
        root.add("resolution", resolution);

        JsonArray elements = new JsonArray();
        JsonObject body = group(name, "body", 0, 0, 0);
        JsonObject torso = group(name, "torso", 0, 24, 0);
        limb(name, elements, torso, "torso", new double[]{-4, 12, -2}, new double[]{8, 12, 4}, 18, 16, 16);
        JsonObject head = group(name, "head", 0, 24, 0);
        cube(name, elements, head, "head", new double[]{-4, 24, -4}, new double[]{8, 8, 8}, 0, 0, 0);
        JsonObject rightArm = group(name, "right_arm", 5, shoulder, 0);
        limb(name, elements, rightArm, "right_arm", new double[]{4, 12, -2}, new double[]{arm, 12, 4}, 18, 40, 16);
        JsonObject leftArm = group(name, "left_arm", -5, shoulder, 0);
        limb(name, elements, leftArm, "left_arm", new double[]{-4 - arm, 12, -2}, new double[]{arm, 12, 4}, 18,
                32, 48);
        JsonObject rightLeg = group(name, "right_leg", 1.9, 12, 0);
        limb(name, elements, rightLeg, "right_leg", new double[]{-0.1, 0, -2}, new double[]{4, 12, 4}, 6, 0, 16);
        JsonObject leftLeg = group(name, "left_leg", -1.9, 12, 0);
        limb(name, elements, leftLeg, "left_leg", new double[]{-3.9, 0, -2}, new double[]{4, 12, 4}, 6, 16, 48);
        for (JsonObject part : new JsonObject[]{torso, head, rightArm, leftArm, rightLeg, leftLeg}) {
            body.getAsJsonArray("children").add(part);
        }
        JsonArray outliner = new JsonArray();
        outliner.add(body);
        root.add("elements", elements);
        root.add("outliner", outliner);
        root.add("textures", new JsonArray());

        JsonArray animations = new JsonArray();
        animations.add(wave(name));
        root.add("animations", animations);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    /** The example: the right arm raised out to the side and waved twice. */
    private static JsonObject wave(String model) {
        JsonObject clip = new JsonObject();
        clip.addProperty("uuid", id(model, "animation.wave"));
        clip.addProperty("name", "wave");
        clip.addProperty("loop", "once");
        clip.addProperty("override", false);
        clip.addProperty("length", 1.6);
        clip.addProperty("snapping", 24);
        JsonArray keyframes = new JsonArray();
        double[][] keys = {{0, 0}, {0.25, 150}, {0.5, 125}, {0.75, 150}, {1.0, 125}, {1.25, 150}, {1.6, 0}};
        for (double[] key : keys) {
            JsonObject frame = new JsonObject();
            frame.addProperty("channel", "rotation");
            JsonArray points = new JsonArray();
            JsonObject point = new JsonObject();
            point.addProperty("x", "0");
            point.addProperty("y", "0");
            point.addProperty("z", Double.toString(key[1]));
            points.add(point);
            frame.add("data_points", points);
            frame.addProperty("uuid", id(model, "wave.key." + key[0]));
            frame.addProperty("time", key[0]);
            frame.addProperty("color", -1);
            frame.addProperty("interpolation", "linear");
            keyframes.add(frame);
        }
        JsonObject animator = new JsonObject();
        animator.addProperty("name", "right_arm");
        animator.addProperty("type", "bone");
        animator.add("keyframes", keyframes);
        JsonObject animators = new JsonObject();
        animators.add(id(model, "group.right_arm"), animator);
        clip.add("animators", animators);
        return clip;
    }

    private static JsonObject group(String model, String name, double x, double y, double z) {
        JsonObject group = new JsonObject();
        group.addProperty("name", name);
        group.add("origin", vector(x, y, z));
        group.addProperty("color", 0);
        group.addProperty("uuid", id(model, "group." + name));
        group.addProperty("export", true);
        group.addProperty("isOpen", true);
        group.addProperty("visibility", true);
        group.add("children", new JsonArray());
        return group;
    }

    /**
     * A part cut at its hinge: the half that turns with the bend in {@code <name>_bend},
     * pivoted at the hinge, the other in the part's own group. The forearm and the shin
     * are below the hinge; for the torso it is the chest, above it.
     *
     * @param hinge the height it bends at, in pixels
     */
    private static void limb(String model, JsonArray elements, JsonObject group, String name, double[] from,
                             double[] size, double hinge, int u, int v) {
        double below = hinge - from[1];
        double above = from[1] + size[1] - hinge;
        boolean chest = name.equals("torso");
        JsonObject bend = group(model, name + "_bend", from[0] + size[0] / 2, hinge, 0);
        double[] top = {from[0], hinge, from[2]};
        double[] topSize = {size[0], above, size[2]};
        double[] bottomSize = {size[0], below, size[2]};
        cube(model, elements, chest ? bend : group, chest ? name + "_bend" : name, top, topSize, u, v, 0);
        cube(model, elements, chest ? group : bend, chest ? name : name + "_bend", from, bottomSize, u, v,
                (int) above);
        group.getAsJsonArray("children").add(bend);
    }

    private static void cube(String model, JsonArray elements, JsonObject group, String name, double[] from,
                             double[] size, int u, int v, int shift) {
        JsonObject cube = new JsonObject();
        String uuid = id(model, "cube." + name);
        cube.addProperty("name", name);
        cube.addProperty("box_uv", true);
        cube.addProperty("rescale", false);
        cube.addProperty("locked", false);
        cube.add("from", vector(from[0], from[1], from[2]));
        cube.add("to", vector(from[0] + size[0], from[1] + size[1], from[2] + size[2]));
        cube.addProperty("autouv", 0);
        cube.addProperty("color", 0);
        cube.add("origin", group.get("origin").deepCopy());
        JsonArray offset = new JsonArray();
        offset.add(u);
        offset.add(v + shift);
        cube.add("uv_offset", offset);
        cube.add("faces", faces(u, v + shift, size[0], size[1], size[2]));
        cube.addProperty("type", "cube");
        cube.addProperty("uuid", uuid);
        elements.add(cube);
        group.getAsJsonArray("children").add(uuid);
    }

    /** Box UV, laid out the way Blockbench lays it out from the offset. */
    private static JsonObject faces(double u, double v, double w, double h, double d) {
        JsonObject faces = new JsonObject();
        faces.add("north", face(u + d, v + d, u + d + w, v + d + h));
        faces.add("east", face(u, v + d, u + d, v + d + h));
        faces.add("south", face(u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h));
        faces.add("west", face(u + d + w, v + d, u + 2 * d + w, v + d + h));
        faces.add("up", face(u + d + w, v + d, u + d, v));
        faces.add("down", face(u + d + 2 * w, v, u + d + w, v + d));
        return faces;
    }

    private static JsonObject face(double u1, double v1, double u2, double v2) {
        JsonObject face = new JsonObject();
        JsonArray uv = new JsonArray();
        uv.add(u1);
        uv.add(v1);
        uv.add(u2);
        uv.add(v2);
        face.add("uv", uv);
        face.add("texture", null);
        return face;
    }

    private static JsonArray vector(double x, double y, double z) {
        JsonArray vector = new JsonArray();
        for (double axis : new double[]{x, y, z}) {
            // -3.9 + 4 is 0.10000000000000009 in doubles; Blockbench writes 0.1.
            vector.add(Math.round(axis * 10_000) / 10_000.0);
        }
        return vector;
    }

    private static String id(String model, String what) {
        return UUID.nameUUIDFromBytes((model + '/' + what).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
