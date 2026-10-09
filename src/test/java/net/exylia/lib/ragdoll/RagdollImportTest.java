package net.exylia.lib.ragdoll;

import net.exylia.lib.ragdoll.internal.RagdollRig;
import net.exylia.lib.util.internal.Ease;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Animations made elsewhere: that a Blockbench or Emotecraft file is read the
 * way the tool that made it plays it.
 */
class RagdollImportTest {

    private static final int ARM_PITCH = RagdollRig.of(RagdollRig.ARM_RIGHT, RagdollRig.PITCH);

    private static Map<String, RagdollAnimation> read(String json, String file) {
        List<String> problems = new ArrayList<>();
        Map<String, RagdollAnimation> read = RagdollAnimations.read(json, file, problems::add);
        assertEquals(List.of(), problems, "the file should read cleanly");
        return read;
    }

    @Test
    @DisplayName("the curves Blockbench and Emotecraft name are curves here too")
    void curvesAreNamedTheirWay() {
        assertSame(Ease.SINE_IN_OUT, Ease.of("easeInOutSine"));
        assertSame(Ease.QUAD_IN_OUT, Ease.of("EASEINOUTQUAD"));
        assertSame(Ease.BACK_OUT, Ease.of("back_out"));
        assertSame(Ease.SMOOTH, Ease.of("catmullrom"));
        assertSame(Ease.BACK, Ease.of("back"));
        for (Ease ease : Ease.values()) {
            assertEquals(0, ease.at(0), 1e-6, ease + " starts at the start");
            assertEquals(1, ease.at(1), 1e-6, ease + " ends at the end");
            assertTrue(ease.peak() >= 1, ease + " has a peak");
        }
        assertEquals(0, Ease.HOLD.at(0.99), 1e-9, "a held key waits");
    }

    @Test
    @DisplayName("a Blockbench animation keys each part on its own beat, with its own curves")
    void blockbenchIsRead() {
        Map<String, RagdollAnimation> read = read("""
                {"format_version": "1.8.0", "animations": {"animation.player.wave": {
                  "loop": true, "animation_length": 2.0,
                  "bones": {
                    "right_arm": {"rotation": {
                      "0.0": [0, 0, 0],
                      "1.0": {"post": [-90, 0, 0], "lerp_mode": "linear", "easing": "easeOutSine"},
                      "2.0": [0, 0, 0]}},
                    "right_arm_bend": {"rotation": [45, 30, 0]},
                    "body": {"position": {"0.5": [0, 8, 0]}},
                    "head": {"rotation": {"0.25": {"pre": [10, 0, 0], "post": [20, 0, 0]}}}
                  }}}}""", "wave.animation.json");
        RagdollAnimation wave = read.get("wave");
        assertNotNull(wave);
        assertSame(wave, read.get("animation.player.wave"), "named in full as well");
        assertTrue(wave.vanilla());
        assertTrue(wave.bends());
        assertEquals(2000, wave.durationMillis());
        assertEquals(0, wave.loopFromMillis(), "a looping animation loops from its start");

        assertEquals(-90, wave.at(1000)[ARM_PITCH], 1e-9);
        // The curve on the second key is how it is arrived at.
        assertEquals(-90 * Math.sin(Math.PI / 4), wave.at(500)[ARM_PITCH], 1e-6);
        assertEquals(45, wave.at(0)[RagdollRig.of(RagdollRig.ARM_RIGHT, RagdollRig.BEND)], 1e-9);
        assertEquals(-30, wave.at(0)[RagdollRig.of(RagdollRig.ARM_RIGHT, RagdollRig.BEND_AXIS)], 1e-9);
        // The whole body is moved in the renderer's blocks, grown to the model.
        assertEquals(0.5 / RagdollRig.VANILLA_SCALE, wave.at(600)[RagdollRig.UP], 1e-9);
        assertEquals(wave.at(600)[RagdollRig.UP], wave.at(0)[RagdollRig.UP], 1e-9,
                "a channel holds its first key until it is reached");
        // A keyframe that jumps is arrived at on one side and left from the other.
        int head = RagdollRig.of(RagdollRig.HEAD, RagdollRig.PITCH);
        assertEquals(10, wave.at(200)[head], 1e-9);
        assertEquals(20, wave.at(250)[head], 1e-9);
    }

    @Test
    @DisplayName("an Emotecraft animation keeps its ticks, its turns and its way of letting go")
    void emotecraftIsRead() {
        RagdollAnimation clap = read("""
                {"name": "Clap", "version": 3, "emote": {
                  "beginTick": 0, "endTick": 20, "stopTick": 30, "degrees": false,
                  "moves": [
                    {"tick": 10, "easing": "EASEINQUAD", "rightArm": {"pitch": -1.5707963}},
                    {"tick": 20, "easing": "LINEAR", "turn": 1, "body": {"yaw": 0}}
                  ]}}""", "clap.json").get("clap");
        assertNotNull(clap);
        assertEquals(1500, clap.durationMillis());
        assertEquals(-1, clap.loopFromMillis());
        // Before its first key a part comes from the vanilla pose, straight.
        assertEquals(-45, clap.at(250)[ARM_PITCH], 1e-4);
        assertEquals(-90, clap.at(500)[ARM_PITCH], 1e-4);
        // Held to the end, then let go by stopTick on the curve its last key named.
        assertEquals(-90, clap.at(1000)[ARM_PITCH], 1e-4);
        assertEquals(-90 * (1 - 0.25), clap.at(1250)[ARM_PITCH], 1e-4);
        assertEquals(0, clap.at(1500)[ARM_PITCH], 1e-9);
        // A turn is a whole turn more, left from the moment the key is reached.
        assertEquals(360, clap.at(1000)[RagdollRig.TURN], 1e-6);
    }

    @Test
    @DisplayName("an Emotecraft loop runs its last key into the one it returns to")
    void emotecraftLoops() {
        RagdollAnimation sway = read("""
                {"name": "Sway", "emote": {"endTick": 19, "isLoop": true, "returnTick": 0,
                  "moves": [
                    {"tick": 0, "head": {"roll": 0}},
                    {"tick": 10, "head": {"roll": 20}}
                  ]}}""", "sway.json").get("sway");
        assertEquals(1000, sway.durationMillis());
        assertEquals(0, sway.loopFromMillis());
        int roll = RagdollRig.of(RagdollRig.HEAD, RagdollRig.ROLL);
        assertEquals(sway.at(0)[roll], sway.at(1000)[roll], 1e-9, "the cycle closes");
        assertEquals(10, sway.at(750)[roll], 1e-6);
    }

    @Test
    @DisplayName("what cannot be read is said, and the rest still plays")
    void problemsAreNamed() {
        List<String> problems = new ArrayList<>();
        Map<String, RagdollAnimation> read = RagdollAnimations.read("""
                {"animations": {"a": {"bones": {
                  "tail": {"rotation": [0, 0, 0]},
                  "head": {"rotation": {"0.0": ["math.sin(q.anim_time) * 10", 0, 0], "1.0": [30, 0, 0]}}
                }}}}""", "odd.json", problems::add);
        assertEquals(2, problems.size(), "a part that is not there, and Molang: " + problems);
        assertFalse(read.isEmpty());
        assertEquals(30, read.get("a").at(1000)[RagdollRig.of(RagdollRig.HEAD, RagdollRig.PITCH)], 1e-9);

        problems.clear();
        assertTrue(RagdollAnimations.read("{\"emote\": {\"endTick\": 10, \"moves\": [{\"tick\": \"soon\"}]}}",
                "broken.json", problems::add).isEmpty());
        assertEquals(1, problems.size(), "a broken file is one problem, not a crash: " + problems);
    }
}
