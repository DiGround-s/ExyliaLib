package net.exylia.lib.spatial.crate;

import net.exylia.lib.FakeServer;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.util.crate.CrateOpening;
import net.exylia.lib.util.crate.CrateSettings;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reel lands where the server decided, and the scene's file and switch read back. */
class CrateSceneTest {

    @TempDir
    Path folder;

    @Test
    void theReelStopsOnThePrize() {
        for (int faces : List.of(8, 32, 80)) {
            for (int slots : List.of(3, 7, 11)) {
                ReelPath path = new ReelPath(faces, slots, 4600);
                List<Integer> strip = path.strip(i -> 100 + i, 7, -1);
                assertEquals(7, strip.get(path.landing()), "the prize sits where the reel stops");
                assertEquals(path.landing(), path.onMarker(path.millis()), "the reel stops on the prize");
                assertEquals(0.0, path.position(path.landing(), path.millis()), 1e-9);
                assertEquals(path.half(), path.onMarker(0), "it starts with the window full");
                // Full both sides when it stops: the last face is at the right edge.
                assertEquals(path.half(), path.position(strip.size() - 1, path.millis()), 1e-9);
            }
        }
    }

    @Test
    void itSlowsDownAndNeverRunsBack() {
        ReelPath path = new ReelPath(32, 7, 4600);
        double last = -1;
        double lastStep = Double.MAX_VALUE;
        for (long at = 0; at <= 4600; at += 50) {
            double now = path.offset(at);
            assertTrue(now >= last, "never backwards at " + at);
            if (last >= 0) {
                assertTrue(now - last <= lastStep + 1e-9, "never faster than before at " + at);
                lastStep = now - last;
            }
            last = now;
        }
        assertEquals(path.landing(), path.offset(99_999), 1e-9, "past the end it stays put");
    }

    @Test
    void aNearMissIsTheFaceAfterThePrizeOnly() {
        ReelPath path = new ReelPath(20, 7, 3000);
        List<Integer> strip = path.strip(i -> 0, 5, 9);
        assertEquals(5, strip.get(path.landing()));
        assertEquals(9, strip.get(path.landing() + 1));
        Set<Integer> rest = new HashSet<>(strip);
        rest.removeAll(Set.of(5, 9));
        assertEquals(Set.of(0), rest, "nothing else is touched");
    }

    @Test
    void facesShrinkOffTheEdgesAndAreLargestOnTheMarker() {
        ReelPath path = new ReelPath(20, 7, 3000);
        assertEquals(1.0, path.size(0), 1e-9);
        assertEquals(ReelPath.EDGE, path.size(3.5), 1e-9);
        assertTrue(path.size(3.75) < ReelPath.EDGE && path.size(3.75) > 0);
        assertEquals(0.0, path.size(4.0), 1e-9);
        assertTrue(path.size(1) > path.size(2));
    }

    @Test
    void theLayoutRoundTrips() throws Exception {
        FakeServer.install();
        Configs.releaseAll();
        Plugin plugin = FakeServer.newPlugin("CrateSceneTestPlugin", folder.toFile());
        Configs.define(plugin, "spatial/crate", CrateSceneLayout.class).load();
        String yaml = Files.readString(folder.resolve("spatial/crate.yml"));
        assertTrue(yaml.contains("near-miss") && yaml.contains("hold-seconds") && yaml.contains("OPEN ANOTHER"), yaml);
        Configs.releaseAll();
        ConfigFile<CrateSceneLayout> again = Configs.define(plugin, "spatial/crate", CrateSceneLayout.class).load();
        assertTrue(again.issues().isEmpty(), "a generated file reads back clean: " + again.issues());
        assertEquals(new CrateSceneLayout(), again.get());
    }

    @Test
    void theSwitchDefaultsToTheInventoryAndSurvivesABlockBind() {
        CrateSettings old = new CrateSettings();
        assertEquals(CrateOpening.INVENTORY, old.opening());
        CrateSettings before = new CrateSettings(old.enabled(), old.startKeys(), old.duplicateRefund(), old.reward(),
                old.blocks(), old.maxAtOnce(), old.spinFrames(), old.staggerSeconds(), old.onSpin(), old.onWin(),
                old.onDuplicate(), old.keyItem(), old.tiers());
        assertEquals(CrateOpening.INVENTORY, before.opening(), "the constructor plugins already call");
        CrateSettings spatial = old.withOpening(CrateOpening.SPATIAL).withBlocks(List.of("-,world,1,2,3,0,0"));
        assertEquals(CrateOpening.SPATIAL, spatial.opening());
    }
}
