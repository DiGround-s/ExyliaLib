package net.exylia.lib.ragdoll;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.exylia.lib.FakeServer;
import net.exylia.lib.ragdoll.internal.RagdollRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Blockbench projects ({@code .bbmodel}), since 1.401.0: that a project plays the
 * frames its own Bedrock export plays, and that the shipped templates open and play.
 */
class BlockbenchProjectTest {

    private static final int ARM_ROLL = RagdollRig.of(RagdollRig.ARM_RIGHT, RagdollRig.ROLL);

    @AfterEach
    void tearDown() {
        FakeServer.reset();
    }

    private static Map<String, RagdollAnimation> read(String json, String file) {
        List<String> problems = new ArrayList<>();
        Map<String, RagdollAnimation> read = RagdollAnimations.read(json, file, problems::add);
        assertEquals(List.of(), problems, "the file should read cleanly");
        return read;
    }

    private static String key(double time, String interpolation, String... points) {
        StringBuilder data = new StringBuilder();
        for (String point : points) {
            String[] xyz = point.split(",");
            if (!data.isEmpty()) {
                data.append(',');
            }
            data.append("{\"x\":\"").append(xyz[0]).append("\",\"y\":\"").append(xyz[1])
                    .append("\",\"z\":\"").append(xyz[2]).append("\"}");
        }
        return "{\"channel\":\"%s\",\"time\":" + time + ",\"interpolation\":\"" + interpolation
                + "\",\"data_points\":[" + data + "]}";
    }

    private static String rotation(double time, String interpolation, String... points) {
        return key(time, interpolation, points).formatted("rotation");
    }

    /** A small project touching every way a key is written: step, spline, two data points, bend, scale. */
    private static final String PROJECT = """
            {"meta": {"format_version": "4.10", "model_format": "bedrock", "box_uv": true},
             "name": "dance", "elements": [], "textures": [],
             "outliner": [{"name": "body", "uuid": "g-body", "origin": [0, 0, 0], "children": [
                 {"name": "right_arm", "uuid": "g-arm", "origin": [5, 22, 0], "children": [
                     {"name": "right_arm_bend", "uuid": "g-bend", "origin": [6, 18, 0], "children": []}]},
                 {"name": "head", "uuid": "g-head", "origin": [0, 24, 0], "children": []},
                 {"name": "left_leg", "uuid": "g-leg", "origin": [-1.9, 12, 0], "children": []}]}],
             "animations": [{"name": "animation.player.dance", "loop": "loop", "length": 2, "snapping": 20,
               "animators": {
                 "g-arm": {"name": "right_arm", "type": "bone", "keyframes": [%s, %s, %s, %s, %s]},
                 "g-bend": {"name": "right_arm_bend", "type": "bone", "keyframes": [%s, %s]},
                 "g-head": {"name": "head", "type": "bone", "keyframes": [%s]},
                 "g-body": {"name": "body", "type": "bone", "keyframes": [%s, %s, %s]},
                 "g-leg": {"name": "renamed in the outliner since", "type": "bone", "keyframes": [%s, %s]},
                 "effects": {"name": "Effects", "type": "effect", "keyframes": [
                     {"channel": "sound", "time": 0.5, "data_points": [{"effect": "random.pop"}]}]}
               }}]}
            """.formatted(
            rotation(0, "linear", "0,0,0"),
            rotation(0.5, "step", "-30,20,10"),
            rotation(1.0, "linear", "-90,45,30"),
            rotation(1.5, "catmullrom", "-60,10,0"),
            rotation(2.0, "catmullrom", "0,0,0"),
            rotation(0, "linear", "0,0,0"),
            rotation(1.0, "linear", "45,30,0"),
            rotation(0.25, "linear", "10,-20,5"),
            key(0, "linear", "0,0,0").formatted("position"),
            key(1.0, "linear", "2,4,-3", "1,4,-3").formatted("position"),
            key(2.0, "linear", "0,0,0").formatted("position"),
            key(0, "linear", "1,1,1").formatted("scale"),
            key(1.0, "linear", "1,1.2,1").formatted("scale"));

    /**
     * What Blockbench's Bedrock export writes for {@link #PROJECT}: position x and
     * rotation x and y negated, a step's value as the next key's {@code pre}, a spline
     * after a straight key with a {@code pre}, two data points as pre and post.
     */
    private static final String EXPORT = """
            {"format_version": "1.8.0", "animations": {"animation.player.dance": {
              "loop": true, "animation_length": 2,
              "bones": {
                "right_arm": {"rotation": {
                  "0.0": [0, 0, 0],
                  "0.5": [30, -20, 10],
                  "1.0": {"pre": [30, -20, 10], "post": [90, -45, 30]},
                  "1.5": {"pre": [60, -10, 0], "post": [60, -10, 0], "lerp_mode": "catmullrom"},
                  "2.0": {"post": [0, 0, 0], "lerp_mode": "catmullrom"}}},
                "right_arm_bend": {"rotation": {"0.0": [0, 0, 0], "1.0": [-45, -30, 0]}},
                "head": {"rotation": {"0.25": [-10, 20, 5]}},
                "body": {"position": {
                  "0.0": [0, 0, 0],
                  "1.0": {"pre": [-2, 4, -3], "post": [-1, 4, -3]},
                  "2.0": [0, 0, 0]}},
                "left_leg": {"scale": {"0.0": [1, 1, 1], "1.0": [1, 1.2, 1]}}
              }}}}
            """;

    @Test
    @DisplayName("a project plays exactly the frames its own Bedrock export plays")
    void projectMatchesItsExport() {
        RagdollAnimation project = read(PROJECT, "dance.bbmodel").get("dance");
        RagdollAnimation export = read(EXPORT, "dance.animation.json").get("dance");
        assertNotNull(project);
        assertNotNull(export);
        assertEquals(export.durationMillis(), project.durationMillis());
        assertEquals(export.loopFromMillis(), project.loopFromMillis());
        assertEquals(export.bends(), project.bends());
        for (long millis = 0; millis <= 2000; millis += 10) {
            assertArrayEquals(export.at(millis), project.at(millis), 1e-9, "at " + millis + " ms");
        }
        // And they are not trivially equal: the step holds, then the arm moves.
        assertEquals(export.at(600)[RagdollRig.of(RagdollRig.ARM_RIGHT, RagdollRig.PITCH)],
                export.at(900)[RagdollRig.of(RagdollRig.ARM_RIGHT, RagdollRig.PITCH)], 1e-9, "a step holds");
    }

    @Test
    @DisplayName("one clip answers to the file and to itself; several each to their own names")
    void naming() {
        Map<String, RagdollAnimation> one = read(PROJECT, "Dance_Project.bbmodel");
        assertSame(one.get("dance_project"), one.get("animation.player.dance"));
        assertSame(one.get("dance"), one.get("animation.player.dance"));

        String two = """
                {"meta": {"format_version": "4.10"}, "animations": [
                  {"name": "wave", "loop": "once", "length": 1, "animators": {"a": {"name": "head", "type": "bone",
                    "keyframes": [%s]}}},
                  {"name": "animation.player.bow", "loop": "hold", "length": 1, "animators": {}}]}
                """.formatted(rotation(0.5, "linear", "0,0,10"));
        Map<String, RagdollAnimation> read = read(two, "moves.bbmodel");
        assertNotNull(read.get("wave"));
        assertNotNull(read.get("bow"));
        assertNotNull(read.get("animation.player.bow"));
        assertFalse(read.containsKey("moves"), "several clips: the file names none of them");
        assertTrue(read.get("wave").loopFromMillis() < 0, "once does not loop");
        assertTrue(read.get("animation.player.bow").loopFromMillis() < 0, "hold does not loop");
    }

    @Test
    @DisplayName("a bezier span is solved from its handles; the curve eases in and out")
    void bezier() {
        String project = """
                {"meta": {"format_version": "4.10"}, "animations": [{"name": "lift", "length": 1, "snapping": 20,
                  "animators": {"a": {"name": "right_arm", "type": "bone", "keyframes": [
                    {"channel": "rotation", "time": 0, "interpolation": "bezier",
                     "data_points": [{"x": 0, "y": 0, "z": 0}]},
                    {"channel": "rotation", "time": 1, "interpolation": "bezier",
                     "data_points": [{"x": 0, "y": 0, "z": 90}]}]}}}]}
                """;
        RagdollAnimation lift = read(project, "lift.bbmodel").get("lift");
        assertEquals(45, lift.at(500)[ARM_ROLL], 0.5, "the default handles are symmetric");
        assertTrue(lift.at(250)[ARM_ROLL] < 22.5 - 2, "and ease in: " + lift.at(250)[ARM_ROLL]);
        assertTrue(lift.at(750)[ARM_ROLL] > 67.5 + 2, "and out: " + lift.at(750)[ARM_ROLL]);
        assertEquals(90, lift.at(1000)[ARM_ROLL], 1e-9);
    }

    @Test
    @DisplayName("Molang is reported with the file and the clip, and that keyframe left out")
    void molangIsReported() {
        String project = """
                {"meta": {"format_version": "4.10"}, "animations": [{"name": "spin", "length": 1,
                  "animators": {"a": {"name": "head", "type": "bone", "keyframes": [
                    {"channel": "rotation", "time": 0, "interpolation": "linear",
                     "data_points": [{"x": "math.sin(q.anim_time * 90)", "y": 0, "z": 0}]}]}}}]}
                """;
        List<String> problems = new ArrayList<>();
        RagdollAnimations.read(project, "spin.bbmodel", problems::add);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("spin.bbmodel (spin): "), problems.get(0));
        assertTrue(problems.get(0).contains("Molang"));

        problems.clear();
        RagdollAnimations.read("{\"meta\": {\"format_version\": \"4.10\"}, \"elements\": []}", "rig.bbmodel",
                problems::add);
        assertEquals(List.of("rig.bbmodel is a Blockbench project with no animations"), problems);
    }

    @Test
    @DisplayName("a file lists its clips with the names they answer to, whether they loop and how long")
    void clipsAreListed() {
        List<RagdollAnimations.Clip> clips = RagdollAnimations.clips(PROJECT, "dance.bbmodel", problem -> { });
        assertEquals(1, clips.size());
        RagdollAnimations.Clip clip = clips.get(0);
        assertEquals("animation.player.dance", clip.name());
        assertEquals(List.of("animation.player.dance", "dance"), clip.answersTo());
        assertTrue(clip.loops());
        assertEquals(Duration.ofSeconds(2), clip.length());
        assertEquals(Duration.ZERO, clip.loopFrom());
    }

    @Test
    @DisplayName("the templates are written once, open as projects, and their wave plays")
    void templates(@TempDir Path data) throws IOException {
        File folder = data.resolve("animations").toFile();
        List<File> written = RagdollAnimations.writeTemplates(folder);
        assertEquals(2, written.size());
        assertEquals(List.of(), RagdollAnimations.writeTemplates(folder), "never over a file that is there");

        for (File file : written) {
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            assertEquals("4.10", root.getAsJsonObject("meta").get("format_version").getAsString());
            assertEquals(11, root.getAsJsonArray("elements").size(), "a head and two halves of five parts");
            for (String bone : List.of("body", "torso", "torso_bend", "head", "right_arm", "right_arm_bend",
                    "left_arm", "left_arm_bend", "right_leg", "right_leg_bend", "left_leg", "left_leg_bend")) {
                assertTrue(text.contains("\"name\": \"" + bone + "\""), file.getName() + " has " + bone);
            }
            Map<String, RagdollAnimation> read = read(text, file.getName());
            RagdollAnimation wave = read.get("wave");
            assertNotNull(wave);
            assertSame(wave, read.get(file.getName().replace(".bbmodel", "")));
            assertEquals(1600, wave.durationMillis());
            assertEquals(150, wave.at(250)[ARM_ROLL], 1e-9, "the arm is up");
            assertEquals(125, wave.at(500)[ARM_ROLL], 1e-9, "and waves");
            assertEquals(0, wave.at(1600)[ARM_ROLL], 1e-9, "and comes down");
        }
        String slim = Files.readString(folder.toPath().resolve("player_slim.bbmodel"));
        assertTrue(slim.contains("21.5"), "slim shoulders sit half a pixel lower");
    }

    @Test
    @DisplayName("a plugin's folder reads projects, lists them per file, and reads again when one is added")
    void folderIsListedAndReloaded(@TempDir Path data) throws IOException {
        FakeServer.install();
        FakeServer.plugins(FakeServer.newPlugin("BlockbenchProjectTest", data.toFile()));
        File folder = data.resolve("animations").toFile();
        RagdollAnimations.writeTemplates(folder);

        Map<String, List<RagdollAnimations.Clip>> files = RagdollAnimations.files("BlockbenchProjectTest");
        assertEquals(List.of("player_classic.bbmodel", "player_slim.bbmodel"), List.copyOf(files.keySet()));
        assertEquals("wave", files.get("player_classic.bbmodel").get(0).name());
        assertNotNull(RagdollAnimations.find("BlockbenchProjectTest", "player_slim"));

        Files.writeString(folder.toPath().resolve("dance.bbmodel"), PROJECT);
        assertNotNull(RagdollAnimations.find("BlockbenchProjectTest", "dance"), "a new file is read");
        assertEquals(3, RagdollAnimations.files("BlockbenchProjectTest").size());
    }
}
