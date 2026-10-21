package net.exylia.lib.model.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.exylia.lib.ragdoll.internal.SkinCubes;
import net.exylia.lib.util.internal.BedrockKeys;
import net.exylia.lib.util.internal.Ease;
import net.exylia.lib.util.internal.KeyTrack;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Reads a Blockbench project, {@code .bbmodel}, and the Bedrock animation files
 * Blockbench exports beside it.
 *
 * <h2>What is read</h2>
 * The outliner's groups become bones, with their pivots and turns; every cube
 * becomes a part hanging off its group, turned about its own pivot, grown by
 * its inflate. The project's own animations are read keyframe for keyframe
 * &mdash; linear, step, catmull-rom and bezier, rotation, position and scale
 * &mdash; and so are its timeline, sound and particle keyframes, as events.
 * Meshes, locators and Molang are reported once each and left out.
 *
 * <h2>What a cube is drawn with, in this order</h2>
 * <ol>
 *   <li>A name ending in {@code @something}: {@code @stone} or
 *       {@code @minecraft:gold_block} is that block, {@code @diamond_sword}
 *       that item, {@code @mainhand}, {@code @offhand} and {@code @helmet} what
 *       the carrier holds or wears there, {@code @head} its own texture as a
 *       head, {@code @hide} nothing. A group's {@code @} applies to every cube
 *       in it that has none of its own.</li>
 *   <li>Its texture: one block when every texel is the same block, a textured
 *       head (drawn as its dominant block until the head is ready) when it has a
 *       design and textured heads are allowed, else the block most of its
 *       texels are.</li>
 *   <li>No texture at all: light grey concrete.</li>
 * </ol>
 *
 * <p>Pure apart from decoding images: text in, rig and clips out.
 */
@ApiStatus.Internal
public final class BbModelReader {

    /** What a cube with no texture at all is drawn in. */
    private static final Material UNTEXTURED = Material.LIGHT_GRAY_CONCRETE;

    /** The sides of a head, in {@link SkinCubes.Painter} order, and the Blockbench face each one shows. */
    private static final String[] HEAD_FACES = {"up", "down", "west", "south", "east", "north"};

    private BbModelReader() {
    }

    /**
     * Everything one file holds.
     *
     * @param rig   its bones and cubes
     * @param clips its animations by name, lower case
     */
    public record Read(Rig rig, Map<String, Clip> clips) {
    }

    /**
     * Reads a project.
     *
     * @param json     the file's text
     * @param file     its name, for the reports
     * @param heads    whether cubes with a design may be drawn as textured heads
     * @param problems where whatever could not be read is described, once each
     * @return what was read, or {@code null} when the file is not a project at all
     */
    public static @Nullable Read read(String json, String file, boolean heads, Consumer<String> problems) {
        Reporter report = new Reporter(file, problems);
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            return parse(root, heads, report);
        } catch (RuntimeException malformed) {
            report.say("could not be read: " + malformed.getMessage());
            return null;
        }
    }

    /** Says each problem once, prefixed with the file it is in. */
    private static final class Reporter {
        private final String file;
        private final Consumer<String> sink;
        private final Set<String> said = new LinkedHashSet<>();

        Reporter(String file, Consumer<String> sink) {
            this.file = file;
            this.sink = sink;
        }

        void say(String problem) {
            if (said.add(problem)) {
                sink.accept(file + ": " + problem);
            }
        }
    }

    // ------------------------------------------------------------------ the project

    private static Read parse(JsonObject root, boolean heads, Reporter report) {
        double resolutionWidth = 16;
        double resolutionHeight = 16;
        if (root.has("resolution") && root.get("resolution").isJsonObject()) {
            JsonObject resolution = root.getAsJsonObject("resolution");
            resolutionWidth = number(resolution.get("width"), 16);
            resolutionHeight = number(resolution.get("height"), 16);
        }
        boolean boxUv = root.has("meta") && root.getAsJsonObject("meta").has("box_uv")
                && root.getAsJsonObject("meta").get("box_uv").getAsBoolean();
        List<Texture> textures = textures(root, resolutionWidth, resolutionHeight, report);
        // A block model is laid out from 0 to 16 with its middle at 8, 0, 8; an entity's middle is its feet.
        String format = root.has("meta") && root.getAsJsonObject("meta").has("model_format")
                ? root.getAsJsonObject("meta").get("model_format").getAsString() : "free";
        double[] shift = format.equals("java_block") ? new double[]{-8, 0, -8} : new double[3];

        Map<String, JsonObject> cubes = new LinkedHashMap<>();
        for (JsonElement element : array(root, "elements")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject cube = element.getAsJsonObject();
            String type = string(cube, "type", "cube");
            if (!type.equals("cube")) {
                report.say(switch (type) {
                    case "mesh" -> "meshes cannot be drawn without a resource pack; they are left out";
                    case "locator" -> "locators are not drawn";
                    case "null_object" -> "null objects are not drawn";
                    default -> "\"" + type + "\" elements are not drawn";
                });
                continue;
            }
            cubes.put(string(cube, "uuid", "cube" + cubes.size()), cube);
        }
        Map<String, JsonObject> groups = new HashMap<>();
        for (JsonElement element : array(root, "groups")) {
            if (element.isJsonObject() && element.getAsJsonObject().has("uuid")) {
                groups.put(element.getAsJsonObject().get("uuid").getAsString(), element.getAsJsonObject());
            }
        }

        List<Rig.Bone> bones = new ArrayList<>();
        List<Rig.Part> parts = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        Builder builder = new Builder(bones, parts, cubes, groups, textures, boxUv, heads, report, placed, shift);
        for (JsonElement child : array(root, "outliner")) {
            builder.child(child, -1, null);
        }
        // Cubes the outliner never mentions are still the author's: they hang off the model itself.
        for (Map.Entry<String, JsonObject> cube : cubes.entrySet()) {
            if (!placed.contains(cube.getKey())) {
                builder.cube(cube.getValue(), builder.loose(), null);
            }
        }
        double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (Rig.Part part : parts) {
            if (part.look().kind() == Look.Kind.HIDDEN) {
                continue;
            }
            for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], Math.min(part.from()[axis], part.to()[axis]));
                max[axis] = Math.max(max[axis], Math.max(part.from()[axis], part.to()[axis]));
            }
        }
        if (min[0] == Double.MAX_VALUE) {
            min = new double[3];
            max = new double[3];
            report.say("has no cube to draw");
        }
        Rig rig = new Rig(List.copyOf(bones), List.copyOf(parts), min, max);
        Map<String, Clip> clips = new LinkedHashMap<>();
        for (JsonElement element : array(root, "animations")) {
            if (element.isJsonObject()) {
                Clip clip = animation(element.getAsJsonObject(), rig, report);
                if (clip != null) {
                    clips.put(clip.name().toLowerCase(Locale.ROOT), clip);
                }
            }
        }
        return new Read(rig, clips);
    }

    /** Walks the outliner, parent before children. */
    private static final class Builder {
        private final List<Rig.Bone> bones;
        private final List<Rig.Part> parts;
        private final Map<String, JsonObject> cubes;
        private final Map<String, JsonObject> groups;
        private final List<Texture> textures;
        private final boolean boxUv;
        private final boolean heads;
        private final Reporter report;
        private final Set<String> placed;
        private final double[] shift;
        private int loose = -1;

        Builder(List<Rig.Bone> bones, List<Rig.Part> parts, Map<String, JsonObject> cubes,
                Map<String, JsonObject> groups, List<Texture> textures, boolean boxUv, boolean heads,
                Reporter report, Set<String> placed, double[] shift) {
            this.bones = bones;
            this.parts = parts;
            this.cubes = cubes;
            this.groups = groups;
            this.textures = textures;
            this.boxUv = boxUv;
            this.heads = heads;
            this.report = report;
            this.placed = placed;
            this.shift = shift;
        }

        private double[] shifted(double[] point) {
            return new double[]{point[0] + shift[0], point[1] + shift[1], point[2] + shift[2]};
        }

        /** The bone cubes outside every group hang off: the model itself, pivoting at its feet. */
        int loose() {
            if (loose < 0) {
                bones.add(new Rig.Bone("root", "", -1, new double[3], new double[3]));
                loose = bones.size() - 1;
            }
            return loose;
        }

        void child(JsonElement child, int parent, @Nullable String inherited) {
            if (child.isJsonPrimitive()) {
                String uuid = child.getAsString();
                JsonObject cube = cubes.get(uuid);
                if (cube != null && placed.add(uuid)) {
                    cube(cube, parent < 0 ? loose() : parent, inherited);
                }
                return;
            }
            if (!child.isJsonObject()) {
                return;
            }
            JsonObject group = child.getAsJsonObject();
            // Newer projects keep a group's data in "groups" and only its place in the outliner.
            if (!group.has("name") && group.has("uuid") && groups.containsKey(group.get("uuid").getAsString())) {
                JsonObject merged = groups.get(group.get("uuid").getAsString()).deepCopy();
                if (group.has("children")) {
                    merged.add("children", group.get("children"));
                }
                group = merged;
            }
            if (group.has("export") && !group.get("export").getAsBoolean()) {
                return;
            }
            String name = string(group, "name", "bone" + bones.size());
            String marker = marker(name);
            String own = marker != null ? marker : inherited;
            if (group.has("visibility") && !group.get("visibility").getAsBoolean()) {
                own = "hide";
            }
            bones.add(new Rig.Bone(plain(name), string(group, "uuid", ""), parent,
                    shifted(vector(group.get("origin"), 0)), vector(group.get("rotation"), 0)));
            int index = bones.size() - 1;
            for (JsonElement grandchild : array(group, "children")) {
                child(grandchild, index, own);
            }
        }

        void cube(JsonObject cube, int bone, @Nullable String inherited) {
            if (cube.has("export") && !cube.get("export").getAsBoolean()) {
                return;
            }
            String name = string(cube, "name", "cube");
            double inflate = number(cube.get("inflate"), 0);
            double[] from = vector(cube.get("from"), 0);
            double[] to = vector(cube.get("to"), 0);
            for (int axis = 0; axis < 3; axis++) {
                double low = Math.min(from[axis], to[axis]) - inflate;
                double high = Math.max(from[axis], to[axis]) + inflate;
                from[axis] = low;
                to[axis] = high;
            }
            String marker = marker(name);
            if (marker == null) {
                marker = inherited;
            }
            if (cube.has("visibility") && !cube.get("visibility").getAsBoolean()) {
                marker = "hide";
            }
            Look look = look(cube, marker, from, to);
            parts.add(new Rig.Part(plain(name), bone, shifted(vector(cube.get("origin"), 0)),
                    vector(cube.get("rotation"), 0), shifted(from), shifted(to), look));
        }

        private Look look(JsonObject cube, @Nullable String marker, double[] from, double[] to) {
            boolean forceHead = false;
            if (marker != null) {
                String word = marker.toLowerCase(Locale.ROOT).replace("minecraft:", "");
                switch (word) {
                    case "hide", "hidden", "none" -> {
                        return Look.HIDDEN;
                    }
                    case "mainhand", "main_hand", "hand" -> {
                        return Look.equipment(EquipmentSlot.HAND);
                    }
                    case "offhand", "off_hand" -> {
                        return Look.equipment(EquipmentSlot.OFF_HAND);
                    }
                    case "helmet", "hat" -> {
                        return Look.equipment(EquipmentSlot.HEAD);
                    }
                    case "head", "skin" -> forceHead = true;
                    default -> {
                        Material material = Material.matchMaterial(word);
                        if (material == null || material == Material.AIR || material == Material.CAVE_AIR
                                || material == Material.VOID_AIR) {
                            report.say("there is no block or item called \"" + marker + "\"; that cube is drawn"
                                    + " from its texture");
                        } else {
                            return Look.named(material);
                        }
                    }
                }
            }
            Faces faces = faces(cube, from, to);
            if (faces == null) {
                return Look.block(UNTEXTURED);
            }
            SkinCubes.Cube head = SkinCubes.painted(faces::head);
            if (head.plain() != null && !forceHead) {
                return Look.block(head.plain());
            }
            if (heads || forceHead) {
                return Look.head(head);
            }
            return Look.block(head.dominant());
        }

        /** The cube's faces, each with its texture and UV rectangle; {@code null} when none is textured. */
        private @Nullable Faces faces(JsonObject cube, double[] from, double[] to) {
            if (textures.isEmpty()) {
                return null;
            }
            JsonObject written = cube.has("faces") && cube.get("faces").isJsonObject()
                    ? cube.getAsJsonObject("faces") : new JsonObject();
            boolean box = cube.has("box_uv") ? cube.get("box_uv").getAsBoolean() : boxUv;
            double[][] boxUvs = box ? boxUv(cube, from, to) : null;
            Face[] faces = new Face[6];
            boolean any = false;
            for (int side = 0; side < 6; side++) {
                String key = HEAD_FACES[side];
                JsonObject face = written.has(key) && written.get(key).isJsonObject()
                        ? written.getAsJsonObject(key) : null;
                Texture texture = null;
                if (face != null && face.has("texture") && !face.get("texture").isJsonNull()) {
                    texture = texture(face.get("texture"));
                } else if (face == null && box) {
                    texture = textures.get(0);
                }
                if (texture == null) {
                    continue;
                }
                double[] uv = face != null && face.has("uv") ? vector4(face.get("uv"))
                        : boxUvs != null ? boxUvs[side] : null;
                if (uv == null) {
                    continue;
                }
                int turn = face != null && face.has("rotation") ? (int) number(face.get("rotation"), 0) : 0;
                faces[side] = new Face(texture, uv, ((turn % 360) + 360) % 360);
                any = true;
            }
            return any ? new Faces(faces) : null;
        }

        private @Nullable Texture texture(JsonElement reference) {
            if (reference.isJsonPrimitive() && reference.getAsJsonPrimitive().isNumber()) {
                int index = reference.getAsInt();
                return index >= 0 && index < textures.size() ? textures.get(index) : null;
            }
            String id = reference.getAsString();
            for (Texture texture : textures) {
                if (texture.id().equals(id) || texture.uuid().equals(id)) {
                    return texture;
                }
            }
            try {
                int index = Integer.parseInt(id);
                return index >= 0 && index < textures.size() ? textures.get(index) : null;
            } catch (NumberFormatException notIndex) {
                return null;
            }
        }

        /**
         * Box UV, the layout Bedrock and older Java models use: the net is laid
         * out from one corner, sides in a row with the top and bottom above.
         */
        private static double[][] boxUv(JsonObject cube, double[] from, double[] to) {
            double[] offset = cube.has("uv_offset") ? vector2(cube.get("uv_offset")) : new double[2];
            double w = Math.floor(to[0] - from[0]);
            double h = Math.floor(to[1] - from[1]);
            double d = Math.floor(to[2] - from[2]);
            double u = offset[0];
            double v = offset[1];
            double[][] uv = new double[6][];
            uv[0] = new double[]{u + d, v, u + d + w, v + d};                 // up
            uv[1] = new double[]{u + d + w, v + d, u + d + 2 * w, v};         // down
            uv[2] = new double[]{u + d + w, v + d, u + 2 * d + w, v + d + h}; // west
            uv[3] = new double[]{u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h}; // south
            uv[4] = new double[]{u, v + d, u + d, v + d + h};                 // east
            uv[5] = new double[]{u + d, v + d, u + d + w, v + d + h};         // north
            if (cube.has("mirror_uv") && cube.get("mirror_uv").getAsBoolean()) {
                for (double[] face : uv) {
                    double swap = face[0];
                    face[0] = face[2];
                    face[2] = swap;
                }
                double[] west = uv[2];
                uv[2] = uv[4];
                uv[4] = west;
            }
            return uv;
        }
    }

    /**
     * One textured face.
     *
     * @param texture the picture
     * @param uv      the rectangle on it, in UV units: left, top, right, bottom
     *                (reversed for a mirrored face)
     * @param turn    how far the texture is turned on the face, clockwise, in degrees
     */
    private record Face(Texture texture, double[] uv, int turn) {

        /** The colour at a point of the face, each axis 0 to 1 as the face is seen from outside. */
        int at(double across, double down) {
            double s = across;
            double t = down;
            switch (turn) {
                case 90 -> { s = down; t = 1 - across; }
                case 180 -> { s = 1 - across; t = 1 - down; }
                case 270 -> { s = 1 - down; t = across; }
                default -> { }
            }
            double u = uv[0] + (uv[2] - uv[0]) * s;
            double v = uv[1] + (uv[3] - uv[1]) * t;
            return texture.pixel(u, v);
        }
    }

    /** A cube's six faces in head order; any may be missing. */
    private record Faces(Face[] faces) {

        /** One texel of the head the cube is painted as. */
        int head(int side, int column, int row) {
            Face face = faces[side];
            if (face == null) {
                return 0;
            }
            double across = (column + 0.5) / 8.0;
            double down = (row + 0.5) / 8.0;
            // The head's bottom keeps its front edge on its last row; Blockbench's
            // down face keeps it on its first.
            if (side == 1) {
                down = 1 - down;
            }
            return face.at(across, down);
        }
    }

    /**
     * A texture of the project, decoded.
     *
     * @param id       the id faces refer to it by
     * @param uuid     its uuid, which newer faces use instead
     * @param image    the picture
     * @param uvWidth  how many UV units its width is
     * @param uvHeight how many UV units its height is
     */
    private record Texture(String id, String uuid, BufferedImage image, double uvWidth, double uvHeight) {

        int pixel(double u, double v) {
            int x = (int) Math.floor(u / uvWidth * image.getWidth());
            int y = (int) Math.floor(v / uvHeight * image.getHeight());
            x = Math.clamp(x, 0, image.getWidth() - 1);
            y = Math.clamp(y, 0, image.getHeight() - 1);
            return image.getRGB(x, y);
        }
    }

    private static List<Texture> textures(JsonObject root, double resolutionWidth, double resolutionHeight,
                                          Reporter report) {
        List<Texture> textures = new ArrayList<>();
        for (JsonElement element : array(root, "textures")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject texture = element.getAsJsonObject();
            String source = string(texture, "source", "");
            int comma = source.indexOf(',');
            BufferedImage image = null;
            if (source.startsWith("data:image") && comma > 0) {
                try {
                    byte[] bytes = Base64.getDecoder().decode(source.substring(comma + 1));
                    image = ImageIO.read(new ByteArrayInputStream(bytes));
                } catch (IOException | IllegalArgumentException broken) {
                    image = null;
                }
            }
            if (image == null) {
                report.say("texture " + string(texture, "name", "?") + " is not embedded in the project;"
                        + " save the project with its textures and its cubes take their colours");
                // Kept as a blank so the indices of the textures after it still match.
                image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            }
            double uvWidth = number(texture.get("uv_width"), resolutionWidth);
            double uvHeight = number(texture.get("uv_height"), resolutionHeight);
            textures.add(new Texture(string(texture, "id", String.valueOf(textures.size())),
                    string(texture, "uuid", ""), image, uvWidth <= 0 ? 16 : uvWidth, uvHeight <= 0 ? 16 : uvHeight));
        }
        return textures;
    }

    // ------------------------------------------------------------------ animations

    /** Collects one animation's keys, bone by bone and channel by channel. */
    private static final class Keys {
        private final Map<Integer, Map<Integer, List<double[]>>> keys = new LinkedHashMap<>();

        /** One key: time, value, ease ordinal, ease argument, bezier flag, then four handle numbers. */
        void add(int bone, int channel, double[] key) {
            keys.computeIfAbsent(bone, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(channel, ignored -> new ArrayList<>())
                    .add(key);
        }

        KeyTrack[][] build(int boneCount) {
            KeyTrack[][] bones = new KeyTrack[boneCount][];
            for (Map.Entry<Integer, Map<Integer, List<double[]>>> bone : keys.entrySet()) {
                KeyTrack[] channels = new KeyTrack[Clip.CHANNELS];
                for (Map.Entry<Integer, List<double[]>> channel : bone.getValue().entrySet()) {
                    List<double[]> list = channel.getValue();
                    // Stable: two keys at one moment keep the order they were read in.
                    list.sort((one, other) -> Double.compare(one[0], other[0]));
                    int size = list.size();
                    long[] times = new long[size];
                    double[] values = new double[size];
                    Ease[] eases = new Ease[size];
                    double[] args = new double[size];
                    double[] handles = new double[size * 4];
                    boolean[] bezier = new boolean[size];
                    boolean anyBezier = false;
                    for (int index = 0; index < size; index++) {
                        double[] key = list.get(index);
                        times[index] = Math.round(key[0]);
                        values[index] = key[1];
                        eases[index] = Ease.values()[(int) key[2]];
                        args[index] = key[3];
                        bezier[index] = key[4] > 0;
                        anyBezier |= bezier[index];
                        System.arraycopy(key, 5, handles, index * 4, 4);
                    }
                    channels[channel.getKey()] = anyBezier
                            ? new KeyTrack(times, values, eases, args, handles, bezier)
                            : new KeyTrack(times, values, eases, args);
                }
                bones[bone.getKey()] = channels;
            }
            return bones;
        }
    }

    private static @Nullable Clip animation(JsonObject animation, Rig rig, Reporter report) {
        String name = string(animation, "name", "animation");
        String where = "animation " + name;
        Clip.Loop loop = switch (string(animation, "loop", "once")) {
            case "loop" -> Clip.Loop.LOOP;
            case "hold" -> Clip.Loop.HOLD;
            default -> Clip.Loop.ONCE;
        };
        for (String unsupported : new String[]{"anim_time_update", "blend_weight", "start_delay", "loop_delay"}) {
            JsonElement value = animation.get(unsupported);
            if (value != null && value.isJsonPrimitive() && !value.getAsString().isBlank()) {
                report.say(where + ": " + unsupported + " is Molang, which only Bedrock plays; it is ignored");
            }
        }
        Keys keys = new Keys();
        List<Clip.Event> events = new ArrayList<>();
        JsonObject animators = animation.has("animators") && animation.get("animators").isJsonObject()
                ? animation.getAsJsonObject("animators") : new JsonObject();
        long last = 0;
        for (Map.Entry<String, JsonElement> entry : animators.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject animator = entry.getValue().getAsJsonObject();
            String type = string(animator, "type", "bone");
            JsonArray frames = array(animator, "keyframes");
            if (type.equals("effect")) {
                for (JsonElement element : frames) {
                    if (element.isJsonObject()) {
                        last = Math.max(last, event(element.getAsJsonObject(), events));
                    }
                }
                continue;
            }
            if (!type.equals("bone")) {
                continue;
            }
            int bone = rig.bone(entry.getKey());
            if (bone < 0) {
                bone = rig.bone(string(animator, "name", ""));
            }
            if (bone < 0) {
                report.say(where + ": there is no bone called \"" + string(animator, "name", entry.getKey())
                        + "\"; its keys are left out");
                continue;
            }
            for (JsonElement element : frames) {
                if (element.isJsonObject()) {
                    last = Math.max(last, keyframe(element.getAsJsonObject(), bone, keys, where, report));
                }
            }
        }
        long length = animation.has("length")
                ? Math.round(number(animation.get("length"), last / 1000.0) * 1000) : last;
        events.sort((one, other) -> Long.compare(one.millis(), other.millis()));
        return new Clip(name, Math.max(length, 1), loop, keys.build(rig.bones().size()), List.copyOf(events));
    }

    private static long keyframe(JsonObject frame, int bone, Keys keys, String where, Reporter report) {
        int base = switch (string(frame, "channel", "")) {
            case "rotation" -> Clip.ROT_X;
            case "position" -> Clip.POS_X;
            case "scale" -> Clip.SCALE_X;
            default -> -1;
        };
        if (base < 0) {
            return 0;
        }
        long millis = Math.round(number(frame.get("time"), 0) * 1000);
        String interpolation = string(frame, "interpolation", "linear");
        Ease ease = switch (interpolation) {
            case "catmullrom" -> Ease.SMOOTH;
            case "step" -> Ease.HOLD;
            default -> Ease.LINEAR;
        };
        boolean bezier = interpolation.equals("bezier");
        double[] leftTime = vector(frame.get("bezier_left_time"), -0.1);
        double[] leftValue = vector(frame.get("bezier_left_value"), 0);
        double[] rightTime = vector(frame.get("bezier_right_time"), 0.1);
        double[] rightValue = vector(frame.get("bezier_right_value"), 0);
        JsonArray points = array(frame, "data_points");
        for (JsonElement element : points) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject point = element.getAsJsonObject();
            double[] value = new double[3];
            String[] axes = {"x", "y", "z"};
            boolean molang = false;
            for (int axis = 0; axis < 3; axis++) {
                JsonElement written = point.get(axes[axis]);
                double read = written == null ? Clip.rest(base + axis) : number(written, Double.NaN);
                if (Double.isNaN(read)) {
                    String text = written.getAsString().trim();
                    read = text.isEmpty() ? Clip.rest(base + axis) : Double.NaN;
                }
                if (Double.isNaN(read)) {
                    report.say(where + ": \"" + written.getAsString() + "\" is Molang, which only Bedrock can"
                            + " play; that keyframe is left out");
                    molang = true;
                    break;
                }
                value[axis] = read;
            }
            if (molang) {
                continue;
            }
            for (int axis = 0; axis < 3; axis++) {
                keys.add(bone, base + axis, new double[]{millis, value[axis], ease.ordinal(), Double.NaN,
                        bezier ? 1 : 0,
                        leftTime[axis] * 1000, leftValue[axis], rightTime[axis] * 1000, rightValue[axis]});
            }
        }
        return millis;
    }

    /** A key of the effects animator: a timeline script, a sound or a particle, as an event. */
    private static long event(JsonObject frame, List<Clip.Event> events) {
        long millis = Math.round(number(frame.get("time"), 0) * 1000);
        String channel = string(frame, "channel", "");
        for (JsonElement element : array(frame, "data_points")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject point = element.getAsJsonObject();
            String text = channel.equals("timeline") ? string(point, "script", "") : string(point, "effect", "");
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    events.add(new Clip.Event(millis, trimmed));
                }
            }
        }
        return millis;
    }

    // ------------------------------------------------------------------ bedrock exports

    /**
     * Every animation of a Bedrock or GeckoLib animation file, keyed against a
     * model's bones by name.
     *
     * @param json     the file's text
     * @param file     its name, for the reports
     * @param rig      the model whose bones it moves
     * @param problems where whatever could not be read is described
     * @return the animations by name, lower case: in full and by the part after the last dot
     */
    public static Map<String, Clip> animations(String json, String file, Rig rig, Consumer<String> problems) {
        Reporter report = new Reporter(file, problems);
        Map<String, Clip> clips = new LinkedHashMap<>();
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException broken) {
            report.say("is not JSON: " + broken.getMessage());
            return clips;
        }
        if (!root.has("animations") || !root.get("animations").isJsonObject()) {
            report.say("has no \"animations\"");
            return clips;
        }
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("animations").entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            String name = entry.getKey();
            String where = "animation " + name;
            JsonObject node = entry.getValue().getAsJsonObject();
            Clip.Loop loop = Clip.Loop.ONCE;
            JsonElement written = node.get("loop");
            if (written != null && written.isJsonPrimitive()) {
                if (written.getAsJsonPrimitive().isBoolean()) {
                    loop = written.getAsBoolean() ? Clip.Loop.LOOP : Clip.Loop.ONCE;
                } else if (written.getAsString().equals("hold_on_last_frame")) {
                    loop = Clip.Loop.HOLD;
                }
            }
            Keys keys = new Keys();
            long[] last = {0};
            JsonObject bones = node.has("bones") && node.get("bones").isJsonObject()
                    ? node.getAsJsonObject("bones") : new JsonObject();
            for (Map.Entry<String, JsonElement> bone : bones.entrySet()) {
                int index = rig.bone(bone.getKey());
                if (index < 0 || !bone.getValue().isJsonObject()) {
                    report.say(where + ": there is no bone called \"" + bone.getKey() + "\"; its keys are left out");
                    continue;
                }
                JsonObject channels = bone.getValue().getAsJsonObject();
                String[] names = {"rotation", "position", "scale"};
                for (int group = 0; group < 3; group++) {
                    int base = group * 3;
                    BedrockKeys.timeline(channels.get(names[group]), where, report::say,
                            (millis, vector, ease, arg) -> {
                                last[0] = Math.max(last[0], millis);
                                for (int axis = 0; axis < 3; axis++) {
                                    keys.add(index, base + axis, new double[]{millis, vector[axis], ease.ordinal(),
                                            arg, 0, 0, 0, 0, 0});
                                }
                            });
                }
            }
            List<Clip.Event> events = new ArrayList<>();
            bedrockEvents(node.get("timeline"), events, null);
            bedrockEvents(node.get("sound_effects"), events, "effect");
            bedrockEvents(node.get("particle_effects"), events, "effect");
            for (Clip.Event event : events) {
                last[0] = Math.max(last[0], event.millis());
            }
            events.sort((one, other) -> Long.compare(one.millis(), other.millis()));
            long length = node.has("animation_length")
                    ? Math.round(BedrockKeys.number(node.get("animation_length"), last[0] / 1000.0) * 1000) : last[0];
            Clip clip = new Clip(name, Math.max(length, 1), loop, keys.build(rig.bones().size()), List.copyOf(events));
            String key = name.toLowerCase(Locale.ROOT);
            clips.put(key, clip);
        }
        return clips;
    }

    private static void bedrockEvents(@Nullable JsonElement timeline, List<Clip.Event> events, @Nullable String field) {
        if (timeline == null || !timeline.isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : timeline.getAsJsonObject().entrySet()) {
            long millis;
            try {
                millis = Math.round(Double.parseDouble(entry.getKey()) * 1000);
            } catch (NumberFormatException notATime) {
                continue;
            }
            List<JsonElement> values = new ArrayList<>();
            if (entry.getValue().isJsonArray()) {
                entry.getValue().getAsJsonArray().forEach(values::add);
            } else {
                values.add(entry.getValue());
            }
            for (JsonElement value : values) {
                String text = value.isJsonPrimitive() ? value.getAsString()
                        : value.isJsonObject() && field != null ? string(value.getAsJsonObject(), field, "") : "";
                for (String line : text.split(";|\\R")) {
                    if (!line.isBlank()) {
                        events.add(new Clip.Event(millis, line.trim()));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** What follows the last {@code @} of a name, or {@code null}. */
    static @Nullable String marker(String name) {
        int at = name.lastIndexOf('@');
        if (at < 0 || at == name.length() - 1) {
            return null;
        }
        return name.substring(at + 1).trim();
    }

    /** A name without its marker. */
    static String plain(String name) {
        int at = name.lastIndexOf('@');
        return at < 0 ? name : name.substring(0, at).trim();
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static String string(JsonObject object, String key, String otherwise) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : otherwise;
    }

    private static double number(@Nullable JsonElement element, double otherwise) {
        return element == null ? otherwise : BedrockKeys.number(element, otherwise);
    }

    private static double[] vector(@Nullable JsonElement element, double fill) {
        double[] vector = {fill, fill, fill};
        if (element != null && element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int axis = 0; axis < 3 && axis < array.size(); axis++) {
                vector[axis] = number(array.get(axis), fill);
            }
        }
        return vector;
    }

    private static double @Nullable [] vector4(JsonElement element) {
        if (!element.isJsonArray() || element.getAsJsonArray().size() < 4) {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        return new double[]{number(array.get(0), 0), number(array.get(1), 0), number(array.get(2), 0),
                number(array.get(3), 0)};
    }

    private static double[] vector2(JsonElement element) {
        if (!element.isJsonArray() || element.getAsJsonArray().size() < 2) {
            return new double[2];
        }
        JsonArray array = element.getAsJsonArray();
        return new double[]{number(array.get(0), 0), number(array.get(1), 0)};
    }
}
