package net.exylia.lib.util.crate;

import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The crate's section nested in a plugin's own file, the way every host carries it. */
class CrateSettingsTest {

    /** A host's configuration, carrying the crate. */
    public record Host(CrateSettings crate) {
        public Host() {
            this(new CrateSettings());
        }

        Host withCrateBlocks(List<String> blocks) {
            return new Host(crate.withBlocks(blocks));
        }
    }

    @TempDir
    Path folder;

    private Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getDataFolder" -> folder.toFile();
                    case "getLogger" -> Logger.getLogger(name);
                    case "isEnabled" -> true;
                    case "toString" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    @Test
    void aBoundBlockSurvivesARestart() throws Exception {
        Plugin plugin = plugin("CrateHost");
        String entry = "-,world,10.0,64.0,10.0,0.0,0.0";

        ConfigFile<Host> first = Configs.define(plugin, "config", Host.class).load();
        first.update(current -> current.withCrateBlocks(List.of(entry)));

        String written = Files.readString(folder.resolve("config.yml"));
        assertTrue(written.contains("legendary"), "the rarities were not written");
        assertTrue(written.contains("key-item"), "the key item was not written");

        Configs.release(plugin);
        ConfigFile<Host> reopened = Configs.define(plugin, "config", Host.class).load();
        assertEquals(List.of(entry), reopened.get().crate().blocks());
        assertEquals(CrateTier.defaults(), reopened.get().crate().tiers());
        assertEquals(CrateReward.UNLOCK, reopened.get().crate().reward());
        Configs.release(plugin);
    }
}
