package net.exylia.lib.model.internal;

import net.exylia.lib.model.Model;
import net.exylia.lib.model.ModelOptions;
import net.exylia.lib.model.ModelState;
import net.exylia.lib.model.Models;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Blockbench project read and posed: the numbers Blockbench shows, the cube
 * the client is sent.
 */
class ModelReadTest {

    private static final double EPSILON = 1e-6;

    /** One arm: a group pivoting at the shoulder, turned 90 degrees about Z, and a cube lying along +X. */
    private static String arm(String cubeName, String texture) {
        return """
                {"meta":{"format_version":"4.10","model_format":"free","box_uv":false},
                 "resolution":{"width":16,"height":16},
                 "elements":[{"name":"%s","from":[4,22,-1],"to":[12,26,1],"origin":[4,24,0],"rotation":[0,0,0],
                   "inflate":0,"uuid":"c1","faces":{%s}}],
                 "outliner":[{"name":"arm","origin":[4,24,0],"rotation":[0,0,90],"uuid":"g1","children":["c1"]}],
                 "textures":[%s],
                 "animations":[{"name":"animation.test.swing","loop":"loop","length":1.0,"animators":{
                   "g1":{"name":"arm","type":"bone","keyframes":[
                     {"channel":"rotation","time":0,"interpolation":"linear","data_points":[{"x":0,"y":0,"z":0}]},
                     {"channel":"rotation","time":1,"interpolation":"linear","data_points":[{"x":"10","y":0,"z":0}]},
                     {"channel":"position","time":0.5,"interpolation":"linear","data_points":[{"x":"math.sin(q.anim_time)","y":0,"z":0}]}
                   ]},
                   "effects":{"name":"Effects","type":"effect","keyframes":[
                     {"channel":"timeline","time":0.5,"data_points":[{"script":"impact"}]}]}}}]}
                """.formatted(cubeName, faces(texture.isEmpty() ? -1 : 0), texture);
    }

    private static String faces(int texture) {
        if (texture < 0) {
            return "";
        }
        StringBuilder faces = new StringBuilder();
        for (String side : new String[]{"north", "east", "south", "west", "up", "down"}) {
            if (!faces.isEmpty()) faces.append(',');
            faces.append('"').append(side).append("\":{\"uv\":[0,0,8,8],\"texture\":").append(texture).append('}');
        }
        return faces.toString();
    }

    private static String texture(int... colours) throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                image.setRGB(x, y, colours[(x + y) % colours.length]);
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return "{\"name\":\"t.png\",\"id\":\"0\",\"uv_width\":16,\"uv_height\":16,\"source\":\"data:image/png;base64,"
                + Base64.getEncoder().encodeToString(bytes.toByteArray()) + "\"}";
    }

    private static BbModelReader.Read read(String json, List<String> problems) {
        BbModelReader.Read read = BbModelReader.read(json, "arm.bbmodel", true, problems::add);
        assertNotNull(read);
        return read;
    }

    @Test
    void bonesAndCubesAreReadAsBlockbenchKeepsThem() throws IOException {
        List<String> problems = new ArrayList<>();
        Rig rig = read(arm("hand", texture(0xFFFF0000)), problems).rig();
        assertEquals(1, rig.bones().size());
        assertEquals("arm", rig.bones().get(0).name());
        assertEquals(90, rig.bones().get(0).rotation()[2], EPSILON);
        assertEquals(1, rig.parts().size());
        assertEquals(0, rig.parts().get(0).bone());
        assertEquals(-1, rig.head(), "no bone is a head");
    }

    @Test
    void aTurnedBoneCarriesItsCubeAboutItsPivot() throws IOException {
        Rig rig = read(arm("hand", texture(0xFFFF0000)), new ArrayList<>()).rig();
        Poser poser = new Poser(rig);
        double[][] out = new double[1][Poser.FIELDS];
        poser.pose(null, -1, 0, 0, 1, 0, out);
        // The cube's middle is 4 px along +X from the shoulder; turned 90 degrees about Z
        // it points up, so it sits at (4, 28, 0) px. Turned half round to face south:
        // (-4, 28, 0) px, which is (-0.25, 1.75, 0) blocks.
        assertEquals(-0.25, out[0][Poser.TX], EPSILON);
        assertEquals(1.75, out[0][Poser.TY], EPSILON);
        assertEquals(0, out[0][Poser.TZ], EPSILON);
        assertEquals(0.5, out[0][Poser.SX], EPSILON);
        assertEquals(0.25, out[0][Poser.SY], EPSILON);
        assertEquals(0.125, out[0][Poser.SZ], EPSILON);
    }

    @Test
    void theCarriersYawTurnsTheModel() throws IOException {
        Rig rig = read(arm("hand", texture(0xFFFF0000)), new ArrayList<>()).rig();
        Poser poser = new Poser(rig);
        double[][] out = new double[1][Poser.FIELDS];
        // Facing west, the model's right side (+X in Blockbench) is to the north.
        poser.pose(null, -1, 0, 0, 1, 90, out);
        assertEquals(0, out[0][Poser.TX], EPSILON);
        assertEquals(-0.25, out[0][Poser.TZ], EPSILON);
        // Twice the size moves it twice as far from the feet.
        poser.pose(null, -1, 0, 0, 2, 0, out);
        assertEquals(-0.5, out[0][Poser.TX], EPSILON);
        assertEquals(3.5, out[0][Poser.TY], EPSILON);
    }

    @Test
    void anAnimatedRotationTurnsTheOtherWayInXAsBedrockDoes() throws IOException {
        Rig rig = read(arm("hand", texture(0xFFFF0000)), new ArrayList<>()).rig();
        Poser poser = new Poser(rig);
        double[][] offsets = new double[1][Clip.CHANNELS];
        for (int channel = 0; channel < Clip.CHANNELS; channel++) offsets[0][channel] = Clip.rest(channel);
        double[][] rest = new double[1][Poser.FIELDS];
        poser.pose(offsets, -1, 0, 0, 1, 0, rest);
        // A keyed Z turns the same way as the bone's own Z: +90 more lays the arm along -X.
        offsets[0][Clip.ROT_Z] = 90;
        double[][] turned = new double[1][Poser.FIELDS];
        poser.pose(offsets, -1, 0, 0, 1, 0, turned);
        // (4,24,0) + (-4,0,0) = (0,24,0) px, flipped: still 0 on X.
        assertEquals(0, turned[0][Poser.TX], EPSILON);
        assertEquals(1.5, turned[0][Poser.TY], EPSILON);
        // A keyed position moves X the other way: +16 px keyed is one block towards -X in Blockbench,
        // and +X once turned half round.
        offsets[0][Clip.ROT_Z] = 0;
        offsets[0][Clip.POS_X] = 16;
        double[][] moved = new double[1][Poser.FIELDS];
        poser.pose(offsets, -1, 0, 0, 1, 0, moved);
        assertEquals(rest[0][Poser.TX] + 1, moved[0][Poser.TX], EPSILON);
    }

    @Test
    void animationsAreSampledAndMolangIsReported() throws IOException {
        List<String> problems = new ArrayList<>();
        BbModelReader.Read read = read(arm("hand", texture(0xFFFF0000)), problems);
        Clip swing = read.clips().get("animation.test.swing");
        assertNotNull(swing);
        assertEquals(1000, swing.length());
        assertEquals(Clip.Loop.LOOP, swing.loop());
        double[] out = new double[Clip.CHANNELS];
        boolean[] keyed = new boolean[Clip.CHANNELS];
        assertTrue(swing.sample(0, 500, out, keyed));
        assertEquals(5, out[Clip.ROT_X], EPSILON);
        assertTrue(keyed[Clip.ROT_X]);
        // The Molang position key is left out entirely.
        assertTrue(!keyed[Clip.POS_X]);
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("Molang")), problems.toString());
        assertEquals(List.of(new Clip.Event(500, "impact")), swing.events());
        assertEquals(1500 % 1000, swing.at(1500));
    }

    @Test
    void aBezierKeyFollowsItsHandles() {
        String json = """
                {"meta":{"model_format":"free"},"elements":[{"name":"c","from":[0,0,0],"to":[1,1,1],"origin":[0,0,0],"uuid":"c"}],
                 "outliner":[{"name":"b","origin":[0,0,0],"rotation":[0,0,0],"uuid":"b","children":["c"]}],
                 "animations":[{"name":"ease","loop":"once","length":1,"animators":{"b":{"name":"b","type":"bone","keyframes":[
                   {"channel":"rotation","time":0,"interpolation":"bezier","data_points":[{"x":0,"y":0,"z":0}],
                    "bezier_right_time":[0.5,0.1,0.1],"bezier_right_value":[0,0,0],"bezier_left_time":[-0.1,-0.1,-0.1],"bezier_left_value":[0,0,0]},
                   {"channel":"rotation","time":1,"interpolation":"bezier","data_points":[{"x":100,"y":0,"z":0}],
                    "bezier_left_time":[-0.5,-0.1,-0.1],"bezier_left_value":[0,0,0],"bezier_right_time":[0.1,0.1,0.1],"bezier_right_value":[0,0,0]}
                 ]}}}]}
                """;
        BbModelReader.Read read = BbModelReader.read(json, "b.bbmodel", true, problem -> { });
        assertNotNull(read);
        Clip clip = read.clips().get("ease");
        double[] out = new double[Clip.CHANNELS];
        boolean[] keyed = new boolean[Clip.CHANNELS];
        clip.sample(0, 500, out, keyed);
        // Symmetric flat handles: halfway in time is halfway in value, and the ends are flat.
        assertEquals(50, out[Clip.ROT_X], 0.5);
        clip.sample(0, 100, out, keyed);
        assertTrue(out[Clip.ROT_X] < 10, "an ease-in starts slow: " + out[Clip.ROT_X]);
    }

    @Test
    void markersChooseWhatACubeIsDrawnWith() throws IOException {
        String texture = texture(0xFFFF0000);
        assertEquals(new Look(Look.Kind.NAMED, Material.GOLD_BLOCK, null, null),
                read(arm("hand@gold_block", texture), new ArrayList<>()).rig().parts().get(0).look());
        assertEquals(new Look(Look.Kind.NAMED, Material.DIAMOND_SWORD, null, null),
                read(arm("blade@minecraft:diamond_sword", texture), new ArrayList<>()).rig().parts().get(0).look());
        Look held = read(arm("grip@mainhand", texture), new ArrayList<>()).rig().parts().get(0).look();
        assertEquals(Look.Kind.EQUIPMENT, held.kind());
        assertEquals(EquipmentSlot.HAND, held.slot());
        assertEquals(Look.Kind.HIDDEN, read(arm("helper@hide", texture), new ArrayList<>())
                .rig().parts().get(0).look().kind());
        List<String> problems = new ArrayList<>();
        assertEquals(Look.Kind.BLOCK, read(arm("odd@not_a_block", texture), problems).rig().parts().get(0).look().kind());
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("not_a_block")));
        // The marker is not part of the name.
        assertEquals("hand", read(arm("hand@stone", texture), new ArrayList<>()).rig().parts().get(0).name());
    }

    @Test
    void aTextureOfOneColourIsOneBlockAndADesignIsAHead() throws IOException {
        // Red concrete's own measured colour.
        Look plain = read(arm("hand", texture(0xFF8E2121)), new ArrayList<>()).rig().parts().get(0).look();
        assertEquals(Look.Kind.BLOCK, plain.kind());
        assertEquals(Material.RED_CONCRETE, plain.material());

        Look design = read(arm("hand", texture(0xFFFFFFFF, 0xFF000000)), new ArrayList<>()).rig().parts().get(0).look();
        assertEquals(Look.Kind.HEAD, design.kind());
        assertNotNull(design.head());
        assertNotNull(design.material(), "drawn as its dominant block until the head is ready");

        Look bare = read(arm("hand", ""), new ArrayList<>()).rig().parts().get(0).look();
        assertEquals(Material.LIGHT_GRAY_CONCRETE, bare.material());
    }

    @Test
    void statesMatchAnimationsByName() throws IOException {
        Model model = Models.read(arm("hand", texture(0xFFFF0000)), "arm", problem -> { });
        assertNotNull(model);
        ModelOptions options = ModelOptions.DEFAULT;
        assertEquals("animation.test.swing", model.animationFor("swing", options));
        assertNull(model.animationFor(ModelState.WALK, options));
        // A cast with nothing of its own and no attack has nothing to play.
        assertNull(model.animationFor(ModelState.cast("slam"), options));
        // The options' choice wins.
        assertEquals("animation.test.swing",
                model.animationFor(ModelState.ATTACK, options.withState(ModelState.ATTACK, "swing")));
        assertEquals("animation.test.swing",
                model.animationFor(ModelState.cast("slam"), options.withState(ModelState.cast("slam"), "swing")));
        assertEquals(List.of("arm"), model.bones());
        assertEquals(1, model.parts());
    }

    @Test
    void wordsInAnimationNamesMatchStates() {
        Rig rig = new Rig(List.of(), List.of(), new double[3], new double[3]);
        Clip walk = new Clip("golem_walk_cycle", 1000, Clip.Loop.LOOP, new net.exylia.lib.util.internal.KeyTrack[0][],
                List.of());
        Clip cast = new Clip("animation.golem.cast", 1000, Clip.Loop.ONCE, new net.exylia.lib.util.internal.KeyTrack[0][],
                List.of());
        Model model = new Model("golem", rig, Map.of(walk.name(), walk, cast.name(), cast), List.of());
        assertEquals("golem_walk_cycle", model.animationFor(ModelState.WALK, ModelOptions.DEFAULT));
        // No run of its own: it walks.
        assertEquals("golem_walk_cycle", model.animationFor(ModelState.RUN, ModelOptions.DEFAULT));
        assertEquals("animation.golem.cast", model.animationFor(ModelState.cast("fireball"), ModelOptions.DEFAULT));
    }

    @Test
    void aBlockModelIsCentredOnItsFeet() {
        String json = """
                {"meta":{"model_format":"java_block"},"elements":[{"name":"c","from":[0,0,0],"to":[16,16,16],"origin":[8,8,8],"uuid":"c"}],
                 "outliner":["c"]}
                """;
        BbModelReader.Read read = BbModelReader.read(json, "block.bbmodel", true, problem -> { });
        assertNotNull(read);
        double[][] out = new double[1][Poser.FIELDS];
        new Poser(read.rig()).pose(null, -1, 0, 0, 1, 0, out);
        assertEquals(0, out[0][Poser.TX], EPSILON);
        assertEquals(0.5, out[0][Poser.TY], EPSILON);
        assertEquals(0, out[0][Poser.TZ], EPSILON);
    }

    @Test
    void meshesAndBrokenFilesAreReported() {
        List<String> problems = new ArrayList<>();
        BbModelReader.read("{\"elements\":[{\"type\":\"mesh\",\"uuid\":\"m\"}],\"outliner\":[]}", "m.bbmodel", true,
                problems::add);
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("meshes")), problems.toString());
        problems.clear();
        assertNull(BbModelReader.read("not json", "x.bbmodel", true, problems::add));
        assertEquals(1, problems.size());
    }

    @Test
    void aBedrockExportAddsAnimationsByBoneName() throws IOException {
        Rig rig = read(arm("hand", texture(0xFFFF0000)), new ArrayList<>()).rig();
        String bedrock = """
                {"format_version":"1.8.0","animations":{"animation.arm.wave":{"loop":true,"animation_length":2,
                  "bones":{"arm":{"rotation":{"0.0":[0,0,0],"1.0":{"post":[0,0,40],"lerp_mode":"catmullrom"},"2.0":[0,0,0]}}},
                  "timeline":{"1.0":"wave_peak"}}}}
                """;
        Map<String, Clip> clips = BbModelReader.animations(bedrock, "arm.animation.json", rig, problem -> { });
        Clip wave = clips.get("animation.arm.wave");
        assertNotNull(wave);
        assertEquals(Clip.Loop.LOOP, wave.loop());
        assertEquals(2000, wave.length());
        double[] out = new double[Clip.CHANNELS];
        wave.sample(0, 1000, out, new boolean[Clip.CHANNELS]);
        assertEquals(40, out[Clip.ROT_Z], EPSILON);
        assertEquals(List.of(new Clip.Event(1000, "wave_peak")), wave.events());
    }

    /**
     * Blockbench 5 keeps keys the way its preview applies them and flips X and Y
     * of a turn, and X of a move, only on a Bedrock export (Blockbench
     * {@code js/formats/bbmodel.js} at {@code ec0b282}, the {@code < 5.0}
     * migration, and {@code Keyframe.compileBedrockKeyframe}). The same pose
     * saved by 4.10 and by 5.0 must read the same.
     */
    @Test
    void aBlockbenchFiveProjectReadsItsKeysAsFourWouldHaveSavedThem() throws IOException {
        String four = arm("hand", texture(0xFFFF0000));
        String five = four.replace("\"format_version\":\"4.10\"", "\"format_version\":\"5.0\"")
                .replace("{\"x\":\"10\",\"y\":0,\"z\":0}", "{\"x\":\"-10\",\"y\":0,\"z\":0}");
        assertTrue(five.contains("5.0") && five.contains("-10"));
        Clip old = read(four, new ArrayList<>()).clips().get("animation.test.swing");
        Clip modern = read(five, new ArrayList<>()).clips().get("animation.test.swing");
        double[] a = new double[Clip.CHANNELS];
        double[] b = new double[Clip.CHANNELS];
        old.sample(0, 1000, a, new boolean[Clip.CHANNELS]);
        modern.sample(0, 1000, b, new boolean[Clip.CHANNELS]);
        assertEquals(10, a[Clip.ROT_X], EPSILON);
        assertEquals(a[Clip.ROT_X], b[Clip.ROT_X], EPSILON);

        assertTrue(BbModelReader.unflipped(com.google.gson.JsonParser.parseString(
                "{\"meta\":{\"format_version\":\"5.1\"}}").getAsJsonObject()));
        assertTrue(!BbModelReader.unflipped(com.google.gson.JsonParser.parseString(
                "{\"meta\":{\"format_version\":\"4.10\"}}").getAsJsonObject()));
        assertTrue(!BbModelReader.unflipped(com.google.gson.JsonParser.parseString("{}").getAsJsonObject()));
    }

    /**
     * Blockbench turns a group by X, then Y, then Z about the fixed axes: three.js
     * Euler order {@code ZYX}, the default of every Blockbench format
     * ({@code js/io/format.ts}, {@code euler_order} defaults to {@code 'ZYX'}).
     * The quaternion must turn a vector exactly as the three matrices do.
     */
    @Test
    void groupsTurnInBlockbenchsOrder() {
        double x = Math.toRadians(30);
        double y = Math.toRadians(-50);
        double z = Math.toRadians(70);
        double[] q = new double[Poser.FIELDS];
        Poser.euler(x, y, z, q);
        double[] v = {1, 2, 3};
        // Rx first.
        double[] rx = {v[0], v[1] * Math.cos(x) - v[2] * Math.sin(x), v[1] * Math.sin(x) + v[2] * Math.cos(x)};
        double[] ry = {rx[0] * Math.cos(y) + rx[2] * Math.sin(y), rx[1], -rx[0] * Math.sin(y) + rx[2] * Math.cos(y)};
        double[] rz = {ry[0] * Math.cos(z) - ry[1] * Math.sin(z), ry[0] * Math.sin(z) + ry[1] * Math.cos(z), ry[2]};
        double[] turned = Poser.rotate(q[Poser.QX], q[Poser.QY], q[Poser.QZ], q[Poser.QW], v[0], v[1], v[2]);
        assertEquals(rz[0], turned[0], EPSILON);
        assertEquals(rz[1], turned[1], EPSILON);
        assertEquals(rz[2], turned[2], EPSILON);
    }

    /** The front is north: facing south (yaw 0) a model's front, its -Z, looks towards +Z. */
    @Test
    void aModelFacesTheWayItsCarrierWalks() throws IOException {
        String json = """
                {"meta":{"format_version":"4.10","model_format":"free"},
                 "elements":[{"name":"nose","from":[-1,0,-8],"to":[1,2,-6],"origin":[0,0,0],"uuid":"n"}],
                 "outliner":["n"]}
                """;
        Rig rig = read(json, new ArrayList<>()).rig();
        Poser poser = new Poser(rig);
        double[][] out = new double[1][Poser.FIELDS];
        poser.pose(null, -1, 0, 0, 1, 0, out);
        assertEquals(7 / 16.0, out[0][Poser.TZ], EPSILON, "facing south, the nose is to the south");
        poser.pose(null, -1, 0, 0, 1, 90, out);
        assertEquals(-7 / 16.0, out[0][Poser.TX], EPSILON, "facing west, the nose is to the west");
        poser.pose(null, -1, 0, 0, 1, 180, out);
        assertEquals(-7 / 16.0, out[0][Poser.TZ], EPSILON, "facing north, to the north");
    }

    @Test
    void aFightPhasePlaysItsOwnAnimationsAndOnlyThose() throws IOException {
        Rig rig = read(arm("hand", texture(0xFFFF0000)), new ArrayList<>()).rig();
        java.util.Map<String, Clip> clips = new java.util.LinkedHashMap<>();
        for (String name : List.of("animation.golem.walk", "walk_p2", "golem_attack_p3", "attack", "slam")) {
            clips.put(name, new Clip(name, 1000, Clip.Loop.LOOP, new net.exylia.lib.util.internal.KeyTrack[1][],
                    List.of()));
        }
        Model model = new Model("golem", rig, clips, List.of());
        ModelOptions options = ModelOptions.DEFAULT;
        assertEquals("animation.golem.walk", model.animationFor(ModelState.WALK, options));
        assertEquals("animation.golem.walk", model.animationFor(ModelState.WALK, options, 1));
        assertEquals("walk_p2", model.animationFor(ModelState.WALK, options, 2));
        assertEquals("animation.golem.walk", model.animationFor(ModelState.WALK, options, 3),
                "no walk of its own in phase 3: the walk");
        assertEquals("golem_attack_p3", model.animationFor(ModelState.ATTACK, options, 3));
        assertEquals("attack", model.animationFor(ModelState.ATTACK, options, 2));
        assertEquals("slam", model.animationFor(ModelState.cast("slam"), options, 2));
        assertEquals("slam", model.animationFor(ModelState.cast("slam"),
                options.withState("p2:cast:slam", "attack").withState("p3:cast:slam", "slam"), 3));
        assertEquals("attack", model.animationFor(ModelState.cast("slam"),
                options.withState("p2:cast:slam", "attack"), 2));
    }
}
