package net.exylia.lib.ragdoll.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.exylia.lib.ragdoll.RagdollAnimation;
import net.exylia.lib.util.internal.Ease;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reads the player animations other tools make.
 *
 * <h2>Two formats</h2>
 * <ul>
 *   <li><b>Blockbench</b>, as its Bedrock or GeckoLib animation export: an
 *       {@code animations} object, each animation a set of {@code bones}
 *       keyed by time. This is what Emotecraft and its player animation
 *       library read too, so the template they publish for Blockbench works
 *       unchanged: {@code head}, {@code torso}, {@code body} (the whole body),
 *       {@code right_arm}, {@code left_arm}, {@code right_leg},
 *       {@code left_leg}, and a {@code _bend} bone beside any of them but the
 *       head.</li>
 *   <li><b>Emotecraft's own JSON</b>: an {@code emote} object with
 *       {@code moves} keyed by tick.</li>
 * </ul>
 *
 * <h2>Read as their own tools play them</h2>
 * Every number is first read the way Emotecraft's library reads it, and then
 * moved into the rig's channels for a body posed the vanilla way, so nothing is
 * re-interpreted: a turn is the model's turn in the model's order, and a bend is
 * an amount about an axis. Where Blockbench and Emotecraft disagree about a
 * Blockbench file the author can see, Blockbench wins: a curve named on a
 * keyframe is how that keyframe is arrived at, a channel holds its first value
 * until its first keyframe, and {@code catmullrom} is a real spline.
 *
 * <p>Pure: text in, animations out, so every conversion can be asserted.
 */
@ApiStatus.Internal
public final class RagdollImport {

    private RagdollImport() {
    }

    /** One of Emotecraft's parts, and where the vanilla model keeps it. */
    private enum Part {
        BODY(-1, 0, 0, 0),
        HEAD(RagdollRig.HEAD, 0, 0, 0),
        TORSO(RagdollRig.BODY, 0, 0, 0),
        RIGHT_ARM(RagdollRig.ARM_RIGHT, -5, 2, 0),
        LEFT_ARM(RagdollRig.ARM_LEFT, 5, 2, 0),
        RIGHT_LEG(RagdollRig.LEG_RIGHT, -1.9, 12, 0.1),
        LEFT_LEG(RagdollRig.LEG_LEFT, 1.9, 12, 0.1);

        final int joint;
        final double[] rest;

        Part(int joint, double x, double y, double z) {
            this.joint = joint;
            this.rest = new double[]{x, y, z};
        }

        static Part of(String name) {
            String plain = name.toLowerCase(Locale.ROOT).replace("_", "");
            return switch (plain) {
                case "body" -> BODY;
                case "head" -> HEAD;
                case "torso" -> TORSO;
                case "rightarm" -> RIGHT_ARM;
                case "leftarm" -> LEFT_ARM;
                case "rightleg" -> RIGHT_LEG;
                case "leftleg" -> LEFT_LEG;
                default -> null;
            };
        }
    }

    /** What one of Emotecraft's numbers is about. */
    private enum Field {
        X, Y, Z, PITCH, YAW, ROLL, SCALE_X, SCALE_Y, SCALE_Z, BEND, AXIS
    }

    /** One keyframe of one number, in Emotecraft's own units: degrees, pixels, blocks. */
    private record Key(long millis, double value, Ease ease, double arg) {
    }

    /** Every key of one animation, by part and number, in the order they were read. */
    private static final class Keys {

        private final Map<Part, Map<Field, List<Key>>> keys = new LinkedHashMap<>();

        void add(Part part, Field field, Key key) {
            keys.computeIfAbsent(part, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(field, ignored -> new ArrayList<>())
                    .add(key);
        }

        List<Key> of(Part part, Field field) {
            Map<Field, List<Key>> fields = keys.get(part);
            return fields == null ? null : fields.get(field);
        }
    }

    /**
     * Every animation in one file.
     *
     * @param json     the file's text
     * @param file     its name, without the folder; what a file of one
     *                 animation is called
     * @param problems where whatever could not be read is described
     * @return each animation by the names it can be asked for, lower case
     */
    public static Map<String, RagdollAnimation> read(String json, String file, Consumer<String> problems) {
        try {
            return parse(json, file, problems);
        } catch (RuntimeException malformed) {
            // A number where an object belongs, a tick that is text: the file is
            // somebody's mistake, and a mistake in one animation must not stop
            // every other effect of the plugin from loading.
            problems.accept(file + " could not be read: " + malformed);
            return new LinkedHashMap<>();
        }
    }

    private static Map<String, RagdollAnimation> parse(String json, String file, Consumer<String> problems) {
        Map<String, RagdollAnimation> read = new LinkedHashMap<>();
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException broken) {
            problems.accept(file + " is not JSON: " + broken.getMessage());
            return read;
        }
        String base = file.toLowerCase(Locale.ROOT);
        for (String suffix : new String[]{".animation.json", ".json"}) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
                break;
            }
        }
        if (root.has("emote")) {
            RagdollAnimation animation = emotecraft(root, file, problems);
            if (animation != null) {
                read.put(base, animation);
                if (root.has("name") && root.get("name").isJsonPrimitive()) {
                    read.putIfAbsent(root.get("name").getAsString().trim().toLowerCase(Locale.ROOT), animation);
                }
            }
            return read;
        }
        if (!root.has("animations") || !root.get("animations").isJsonObject()) {
            problems.accept(file + " has neither \"animations\" (Blockbench) nor \"emote\" (Emotecraft)");
            return read;
        }
        JsonObject animations = root.getAsJsonObject("animations");
        for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            String name = entry.getKey();
            RagdollAnimation animation = blockbench(entry.getValue().getAsJsonObject(), file + " (" + name + ")",
                    problems);
            String key = name.toLowerCase(Locale.ROOT);
            read.put(key, animation);
            int dot = key.lastIndexOf('.');
            if (dot >= 0 && dot < key.length() - 1) {
                read.putIfAbsent(key.substring(dot + 1), animation);
            }
        }
        if (animations.size() == 1 && !read.isEmpty()) {
            read.putIfAbsent(base, read.values().iterator().next());
        }
        return read;
    }

    // ------------------------------------------------------------ blockbench

    private static RagdollAnimation blockbench(JsonObject node, String where, Consumer<String> problems) {
        Keys keys = new Keys();
        boolean loops = false;
        if (node.has("loop") && node.get("loop").isJsonPrimitive()) {
            JsonPrimitive loop = node.getAsJsonPrimitive("loop");
            loops = loop.isBoolean() && loop.getAsBoolean();
        }
        JsonObject bones = node.has("bones") && node.get("bones").isJsonObject()
                ? node.getAsJsonObject("bones") : new JsonObject();
        boolean torsoBend = bones.has("torso_bend");
        for (Map.Entry<String, JsonElement> bone : bones.entrySet()) {
            if (!bone.getValue().isJsonObject()) {
                continue;
            }
            String name = bone.getKey();
            boolean bend = name.toLowerCase(Locale.ROOT).endsWith("_bend");
            String partName = bend ? name.substring(0, name.length() - 5) : name;
            Part part = Part.of(partName);
            if (part == null) {
                if (!partName.toLowerCase(Locale.ROOT).replace("_", "").endsWith("item")) {
                    problems.accept(where + ": there is no part called \"" + name + "\"; the parts are"
                            + " head, torso, body, right_arm, left_arm, right_leg and left_leg");
                }
                continue;
            }
            JsonObject channels = bone.getValue().getAsJsonObject();
            if (bend) {
                // The whole body's bend is the torso's, as Emotecraft draws it.
                if (part == Part.HEAD) {
                    problems.accept(where + ": the head does not bend");
                    continue;
                }
                if (part == Part.BODY) {
                    if (torsoBend) {
                        continue;
                    }
                    part = Part.TORSO;
                }
                boolean torso = part == Part.TORSO && name.toLowerCase(Locale.ROOT).startsWith("torso");
                Part bent = part;
                timeline(channels.get("rotation"), where, problems, (millis, vector, ease, arg) -> {
                    keys.add(bent, Field.BEND, new Key(millis, vector[0], ease, arg));
                    keys.add(bent, Field.AXIS, new Key(millis, vector[1] * (torso ? 1 : -1), ease, arg));
                });
                continue;
            }
            Part moved = part;
            timeline(channels.get("rotation"), where, problems, (millis, vector, ease, arg) -> {
                boolean whole = moved == Part.BODY;
                keys.add(moved, Field.PITCH, new Key(millis, whole ? -vector[0] : vector[0], ease, arg));
                keys.add(moved, Field.YAW, new Key(millis, whole ? -vector[1] : vector[1], ease, arg));
                keys.add(moved, Field.ROLL, new Key(millis, vector[2], ease, arg));
            });
            timeline(channels.get("position"), where, problems, (millis, vector, ease, arg) -> {
                if (moved == Part.BODY) {
                    keys.add(moved, Field.X, new Key(millis, -vector[0] / 16, ease, arg));
                    keys.add(moved, Field.Y, new Key(millis, vector[1] / 16, ease, arg));
                    keys.add(moved, Field.Z, new Key(millis, vector[2] / 16, ease, arg));
                } else {
                    keys.add(moved, Field.X, new Key(millis, moved.rest[0] + vector[0], ease, arg));
                    keys.add(moved, Field.Y, new Key(millis, moved.rest[1] - vector[1], ease, arg));
                    keys.add(moved, Field.Z, new Key(millis, moved.rest[2] + vector[2], ease, arg));
                }
            });
            timeline(channels.get("scale"), where, problems, (millis, vector, ease, arg) -> {
                keys.add(moved, Field.SCALE_X, new Key(millis, vector[0], ease, arg));
                keys.add(moved, Field.SCALE_Y, new Key(millis, vector[1], ease, arg));
                keys.add(moved, Field.SCALE_Z, new Key(millis, vector[2], ease, arg));
            });
        }
        long last = 0;
        for (Map<Field, List<Key>> fields : keys.keys.values()) {
            for (List<Key> list : fields.values()) {
                for (Key key : list) {
                    last = Math.max(last, key.millis());
                }
            }
        }
        long duration = node.has("animation_length")
                ? Math.round(number(node.get("animation_length"), last / 1000.0) * 1000) : last;
        return build(keys, Math.max(duration, 1), loops ? 0 : -1, where, problems);
    }

    @FunctionalInterface
    private interface Keyed {

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
    private static void timeline(JsonElement channel, String where, Consumer<String> problems, Keyed keyed) {
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

    private static double number(JsonElement element, double otherwise) {
        try {
            return element.getAsDouble();
        } catch (RuntimeException notANumber) {
            return otherwise;
        }
    }

    // ------------------------------------------------------------ emotecraft

    private static RagdollAnimation emotecraft(JsonObject root, String where, Consumer<String> problems) {
        JsonObject emote = root.getAsJsonObject("emote");
        int version = root.has("version") ? root.get("version").getAsInt() : 1;
        int begin = emote.has("beginTick") ? emote.get("beginTick").getAsInt() : 0;
        int end = emote.has("endTick") ? emote.get("endTick").getAsInt() : 0;
        if (end <= 0) {
            problems.accept(where + ": endTick must be above zero");
            return null;
        }
        int stop = emote.has("stopTick") ? emote.get("stopTick").getAsInt() : end;
        boolean loops = emote.has("isLoop") && emote.get("isLoop").getAsBoolean();
        int back = emote.has("returnTick") ? emote.get("returnTick").getAsInt() : 0;
        if (loops && (back > end || back < 0)) {
            problems.accept(where + ": returnTick must be between 0 and endTick; it loops from the start");
            back = 0;
        }
        boolean degrees = !emote.has("degrees") || emote.get("degrees").getAsBoolean();
        boolean easeBefore = emote.has("easeBeforeKeyframe") && emote.get("easeBeforeKeyframe").getAsBoolean();
        // Read as Emotecraft keys them: each curve belongs to the key it is
        // written on, and leaves it, unless the file says it arrives instead.
        Map<Part, Map<Field, List<Key>>> written = new LinkedHashMap<>();
        JsonArray moves = emote.has("moves") ? emote.getAsJsonArray("moves") : new JsonArray();
        for (JsonElement element : moves) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject move = element.getAsJsonObject();
            long millis = (long) move.get("tick").getAsInt() * 50L;
            Ease ease = move.has("easing") ? Ease.of(move.get("easing").getAsString()) : Ease.LINEAR;
            if (ease == null) {
                problems.accept(where + ": there is no curve called \"" + move.get("easing").getAsString()
                        + "\"; it is played straight");
                ease = Ease.LINEAR;
            }
            double arg = move.has("easingArg") ? number(move.get("easingArg"), Double.NaN) : Double.NaN;
            int turn = move.has("turn") ? move.get("turn").getAsInt() : 0;
            for (Map.Entry<String, JsonElement> entry : move.entrySet()) {
                String name = entry.getKey();
                if (name.equals("tick") || name.equals("comment") || name.equals("easing")
                        || name.equals("turn") || name.equals("easingArg") || !entry.getValue().isJsonObject()) {
                    continue;
                }
                if (version < 3 && name.equals("torso")) {
                    name = "body";
                }
                Part part = Part.of(name);
                if (part == null) {
                    if (!name.toLowerCase(Locale.ROOT).endsWith("item")) {
                        problems.accept(where + ": there is no part called \"" + name + "\"");
                    }
                    continue;
                }
                JsonObject values = entry.getValue().getAsJsonObject();
                for (Field field : Field.values()) {
                    String key = switch (field) {
                        case X -> "x";
                        case Y -> "y";
                        case Z -> "z";
                        case PITCH -> "pitch";
                        case YAW -> "yaw";
                        case ROLL -> "roll";
                        case SCALE_X -> "scaleX";
                        case SCALE_Y -> "scaleY";
                        case SCALE_Z -> "scaleZ";
                        case BEND -> "bend";
                        case AXIS -> "axis";
                    };
                    if (!values.has(key)) {
                        continue;
                    }
                    double value = values.get(key).getAsDouble();
                    boolean angle = field == Field.PITCH || field == Field.YAW || field == Field.ROLL
                            || field == Field.BEND || field == Field.AXIS;
                    if (angle && !degrees) {
                        value = Math.toDegrees(value);
                    }
                    // A whole body's bend is the torso's.
                    Part target = part == Part.BODY && (field == Field.BEND || field == Field.AXIS) ? Part.TORSO : part;
                    List<Key> list = written.computeIfAbsent(target, ignored -> new LinkedHashMap<>())
                            .computeIfAbsent(field, ignored -> new ArrayList<>());
                    list.add(new Key(millis, value, ease, arg));
                    if (angle && turn != 0) {
                        list.add(new Key(millis, value + 360.0 * turn, ease, arg));
                    }
                }
            }
        }
        Keys keys = new Keys();
        long duration = (long) Math.max(end, stop) * 50L;
        long cycle = (end + 1L) * 50L;
        for (Map.Entry<Part, Map<Field, List<Key>>> part : written.entrySet()) {
            for (Map.Entry<Field, List<Key>> field : part.getValue().entrySet()) {
                List<Key> list = new ArrayList<>(field.getValue());
                list.sort((one, other) -> Long.compare(one.millis(), other.millis()));
                double rest = rest(part.getKey(), field.getKey());
                List<Key> arriving = new ArrayList<>(list.size() + 3);
                // Before its first key a channel comes from the vanilla pose.
                Key start = list.get(0).millis() > begin * 50L
                        ? new Key(begin * 50L, rest, Ease.LINEAR, Double.NaN) : null;
                if (start != null) {
                    arriving.add(start);
                }
                for (int index = 0; index < list.size(); index++) {
                    Key key = list.get(index);
                    Key leaving = easeBefore ? key : index > 0 ? list.get(index - 1) : start;
                    arriving.add(leaving == null ? key
                            : new Key(key.millis(), key.value(), leaving.ease(), leaving.arg()));
                }
                Key last = list.get(list.size() - 1);
                if (loops) {
                    // Emotecraft runs the last key into the first one after the
                    // return tick, across the wrap; a key one tick past the end
                    // with the value the cycle goes back to does the same.
                    double from = valueAt(arriving, back * 50L);
                    arriving.add(new Key(cycle, from, last.ease(), last.arg()));
                } else if (stop > end) {
                    // Past the end it lets go: back to the vanilla pose by stopTick.
                    arriving.add(new Key(end * 50L, valueAt(arriving, end * 50L), Ease.LINEAR, Double.NaN));
                    arriving.add(new Key(stop * 50L, rest, last.ease(), last.arg()));
                }
                for (Key key : arriving) {
                    keys.add(part.getKey(), field.getKey(), key);
                }
            }
        }
        return build(keys, loops ? cycle : duration, loops ? back * 50L : -1, where, problems);
    }

    /** What a part's number is when nothing has moved it. */
    private static double rest(Part part, Field field) {
        return switch (field) {
            case X -> part == Part.BODY ? 0 : part.rest[0];
            case Y -> part == Part.BODY ? 0 : part.rest[1];
            case Z -> part == Part.BODY ? 0 : part.rest[2];
            case SCALE_X, SCALE_Y, SCALE_Z -> 1;
            default -> 0;
        };
    }

    /**
     * A list of keys read at one moment, which is all a loop's seam needs.
     *
     * <p>Of several keys at that very moment the last is the one that counts:
     * it is the one the channel leaves from.
     */
    private static double valueAt(List<Key> keys, long millis) {
        int before = -1;
        for (int index = 0; index < keys.size(); index++) {
            if (keys.get(index).millis() <= millis) {
                before = index;
            }
        }
        if (before < 0) {
            return keys.get(0).value();
        }
        Key from = keys.get(before);
        if (from.millis() == millis || before == keys.size() - 1) {
            return from.value();
        }
        Key to = keys.get(before + 1);
        double span = to.millis() - from.millis();
        return from.value() + (to.value() - from.value()) * to.ease().at((millis - from.millis()) / span, to.arg());
    }

    // ------------------------------------------------------------ the rig

    /**
     * Moves Emotecraft's numbers into the rig's channels.
     *
     * <p>The model's {@code y} points down and its {@code z} out of the back,
     * where the rig's point up and forward; a part's place is kept as how far
     * it has moved from where the model keeps it. The whole body is moved and
     * turned in the renderer's axes, which face the other way round.
     */
    private static RagdollAnimation build(Keys keys, long duration, long loopFrom, String where,
                                          Consumer<String> problems) {
        long[][] times = new long[RagdollRig.COUNT][];
        double[][] values = new double[RagdollRig.COUNT][];
        Ease[][] eases = new Ease[RagdollRig.COUNT][];
        double[][] args = new double[RagdollRig.COUNT][];
        for (Map.Entry<Part, Map<Field, List<Key>>> entry : keys.keys.entrySet()) {
            Part part = entry.getKey();
            for (Map.Entry<Field, List<Key>> written : entry.getValue().entrySet()) {
                Field field = written.getKey();
                int channel;
                double scale = 1;
                double shift = 0;
                if (part == Part.BODY) {
                    switch (field) {
                        case X -> { channel = RagdollRig.RIGHT; scale = 1 / RagdollRig.VANILLA_SCALE; }
                        case Y -> { channel = RagdollRig.UP; scale = 1 / RagdollRig.VANILLA_SCALE; }
                        case Z -> { channel = RagdollRig.FORWARD; scale = -1 / RagdollRig.VANILLA_SCALE; }
                        case PITCH -> { channel = RagdollRig.FLIP; scale = -1; }
                        case YAW -> channel = RagdollRig.TURN;
                        case ROLL -> { channel = RagdollRig.LEAN; scale = -1; }
                        case SCALE_Y -> channel = RagdollRig.SIZE;
                        case SCALE_X, SCALE_Z -> channel = keys.of(part, Field.SCALE_Y) == null ? RagdollRig.SIZE : -1;
                        default -> channel = -1;
                    }
                } else {
                    int joint = part.joint;
                    switch (field) {
                        case X -> { channel = RagdollRig.of(joint, RagdollRig.OUT); scale = 1 / 16.0; shift = -part.rest[0]; }
                        case Y -> { channel = RagdollRig.of(joint, RagdollRig.RAISE); scale = -1 / 16.0; shift = -part.rest[1]; }
                        case Z -> { channel = RagdollRig.of(joint, RagdollRig.AHEAD); scale = -1 / 16.0; shift = -part.rest[2]; }
                        case PITCH -> channel = RagdollRig.of(joint, RagdollRig.PITCH);
                        case YAW -> channel = RagdollRig.of(joint, RagdollRig.YAW);
                        case ROLL -> channel = RagdollRig.of(joint, RagdollRig.ROLL);
                        case SCALE_Y -> channel = RagdollRig.of(joint, RagdollRig.SCALE);
                        case SCALE_X, SCALE_Z -> channel = keys.of(part, Field.SCALE_Y) == null
                                ? RagdollRig.of(joint, RagdollRig.SCALE) : -1;
                        case BEND -> channel = RagdollRig.of(joint, RagdollRig.BEND);
                        case AXIS -> channel = RagdollRig.of(joint, RagdollRig.BEND_AXIS);
                        default -> channel = -1;
                    }
                }
                if (channel < 0 || times[channel] != null) {
                    continue;
                }
                List<Key> list = written.getValue();
                times[channel] = new long[list.size()];
                values[channel] = new double[list.size()];
                eases[channel] = new Ease[list.size()];
                args[channel] = new double[list.size()];
                for (int index = 0; index < list.size(); index++) {
                    Key key = list.get(index);
                    times[channel][index] = key.millis();
                    values[channel][index] = (key.value() + shift) * scale;
                    eases[channel][index] = key.ease();
                    args[channel][index] = key.arg();
                }
            }
        }
        warnFast(times, values, eases, where, problems);
        return RagdollAnimation.keyed(times, values, eases, args, duration, true, loopFrom);
    }

    /** The fastest a part may be asked to turn in one tick and still be drawn the right way round. */
    private static final double MAX_DEGREES_PER_TICK = 160;

    private static void warnFast(long[][] times, double[][] values, Ease[][] eases, String where,
                                 Consumer<String> problems) {
        for (int channel = 0; channel < RagdollRig.COUNT; channel++) {
            if (times[channel] == null || !RagdollRig.isAngle(channel)) {
                continue;
            }
            for (int index = 1; index < times[channel].length; index++) {
                long span = times[channel][index] - times[channel][index - 1];
                if (span <= 0 || eases[channel][index] == Ease.HOLD || eases[channel][index] == Ease.SNAP) {
                    continue;
                }
                double perTick = Math.abs(values[channel][index] - values[channel][index - 1]) / span * 50
                        * eases[channel][index].peak();
                if (perTick > MAX_DEGREES_PER_TICK) {
                    problems.accept(where + ": a part turns too fast at " + times[channel][index] / 1000.0
                            + "s for the client to follow it the right way round");
                    return;
                }
            }
        }
    }
}
