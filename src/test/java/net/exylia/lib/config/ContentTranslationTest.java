package net.exylia.lib.config;

import net.exylia.lib.FakeServer;
import net.exylia.lib.config.internal.DefaultUpdates;
import net.exylia.lib.debug.DebugCapture;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packaged files are written into this class's build output directory, which persists across runs,
 * so every test packages a catalogue of its own.
 */
class ContentTranslationTest {

    private static final String SHIPPED = """
            name: 'Explorer'
            icon: COMPASS
            description:
              - 'Be first to walk {highlight}%land%{letters}.'
              - 'Rare biomes pay more.'
            rewards:
              10:
                - type: ECONOMY
                  value: '250'
                  name: '{info}250 coins'
            perks:
              10:
                a:
                  name: 'Wanderlust'
                  trigger: JOB_EXPLORE
            """;

    private static final String SPANISH = """
            explorer:
              name: 'Explorador'
              description:
                - 'Pisa primero {highlight}%land%{letters}.'
                - 'Los biomas raros'
                - 'pagan más.'
              rewards.10.0.name: '{info}250 monedas'
              perks.10.a.name: 'Vagabundo'
            """;

    @TempDir
    Path folder;

    private Plugin plugin;
    private Path packagedRoot;

    @BeforeEach
    void setUp() throws Exception {
        FakeServer.install();
        FakeServer.reset();
        DefaultUpdates.releaseAll();
        plugin = FakeServer.newPlugin("Content", folder.toFile());
        packagedRoot = Path.of(ContentTranslationTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Languages.use(plugin, "es");
    }

    @AfterEach
    void tearDown() {
        Languages.use(plugin, null);
        DebugCapture.stop();
        DefaultUpdates.releaseAll();
        FakeServer.reset();
    }

    private void pack(String path, String text) throws Exception {
        Path file = packagedRoot.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private void onDisk(String path, String text) throws Exception {
        Path file = folder.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    /** Packages catalogue {@code name} with the shipped job and its table, and returns the job as a server read it. */
    private YamlConfiguration translated(String name, String table, String serverText) throws Exception {
        pack(name + "/explorer.yml", SHIPPED);
        if (table != null) pack("lang/es/content/" + name + ".yml", table);
        YamlConfiguration yaml = yaml(serverText);
        Languages.translate(plugin, ContentTranslationTest.class, name + "/explorer.yml", yaml);
        return yaml;
    }

    @Test
    void shippedTextIsReplacedEverywhereInTheFile() throws Exception {
        YamlConfiguration job = translated("replaced", SPANISH, SHIPPED);

        assertEquals("Explorador", job.getString("name"));
        assertEquals(List.of("Pisa primero {highlight}%land%{letters}.", "Los biomas raros", "pagan más."),
                job.getStringList("description"), "a list may change its number of lines");
        assertEquals("Vagabundo", job.getString("perks.10.a.name"));
        Map<?, ?> reward = (Map<?, ?>) job.getList("rewards.10").get(0);
        assertEquals("{info}250 monedas", reward.get("name"), "text inside a list of maps is reached by index");
        assertEquals("250", reward.get("value"));
        assertEquals("COMPASS", job.getString("icon"), "what the table does not name is untouched");
    }

    @Test
    void textTheOwnerEditedStays() throws Exception {
        YamlConfiguration job = translated("edited", SPANISH, SHIPPED
                .replace("'Explorer'", "'Nomad'")
                .replace("'Rare biomes pay more.'", "'Rare biomes pay a lot.'"));

        assertEquals("Nomad", job.getString("name"));
        assertEquals(List.of("Be first to walk {highlight}%land%{letters}.", "Rare biomes pay a lot."),
                job.getStringList("description"), "one edited line makes the whole list the owner's");
        assertEquals("Vagabundo", job.getString("perks.10.a.name"), "other keys still follow the language");
    }

    @Test
    void aMissingTranslationIsTheServersText() throws Exception {
        YamlConfiguration partial = translated("partial", "explorer:\n  name: 'Explorador'\n", SHIPPED);
        assertEquals("Explorador", partial.getString("name"));
        assertEquals("Wanderlust", partial.getString("perks.10.a.name"));

        YamlConfiguration none = translated("untranslated", null, SHIPPED);
        assertEquals("Explorer", none.getString("name"));

        Languages.use(plugin, "en");
        YamlConfiguration english = translated("english", SPANISH, SHIPPED);
        assertEquals("Explorer", english.getString("name"), "a language with no table is the file as written");
    }

    @Test
    void aTranslationThatLosesAPlaceholderOrTokenIsRefused() throws Exception {
        DebugCapture.start();
        YamlConfiguration job = translated("dropped", """
                explorer:
                  description:
                    - 'Pisa primero.'
                  rewards.10.0.name: '250 monedas'
                  perks.10.a.name:
                    - 'Vagabundo'
                """, SHIPPED);

        assertEquals("Be first to walk {highlight}%land%{letters}.", job.getStringList("description").get(0));
        assertEquals("{info}250 coins", ((Map<?, ?>) job.getList("rewards.10").get(0)).get("name"));
        assertEquals("Wanderlust", job.getString("perks.10.a.name"), "a list cannot stand in for one line");
        assertEquals(3, Languages.translationProblems(ContentTranslationTest.class, "es", "dropped/explorer.yml").size());
    }

    @Test
    void tokensAndPlaceholdersSurviveWhereTheTranslationPutsThem() throws Exception {
        YamlConfiguration job = translated("kept", """
                explorer:
                  description:
                    - '{letters}Pisa {highlight}%land% {highlight}primero.'
                """, SHIPPED);

        assertEquals(List.of("{letters}Pisa {highlight}%land% {highlight}primero."), job.getStringList("description"));
        assertTrue(Languages.translationProblems(ContentTranslationTest.class, "es", "kept/explorer.yml").isEmpty());
    }

    @Test
    void aKeyTheServerDoesNotHaveIsNotCreated() throws Exception {
        YamlConfiguration job = translated("removed", SPANISH, SHIPPED.replace("name: 'Explorer'\n", ""));

        assertFalse(job.contains("name"), "a deleted name is the owner's choice");
    }

    @Test
    void theServersFileIsNeverWritten() throws Exception {
        pack("untouched/explorer.yml", SHIPPED);
        pack("lang/es/content/untouched.yml", SPANISH);
        onDisk("untouched/explorer.yml", SHIPPED);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(folder.resolve("untouched/explorer.yml").toFile());

        Languages.translate(plugin, ContentTranslationTest.class, "untouched/explorer.yml", yaml);

        assertEquals("Explorador", yaml.getString("name"));
        assertEquals(SHIPPED, Files.readString(folder.resolve("untouched/explorer.yml")));
        assertFalse(Files.exists(folder.resolve("lang")), "nothing is installed for it either");
    }

    @Test
    void anOwnFileWithNothingShippedIsLeftAlone() throws Exception {
        pack("lang/es/content/owned.yml", "custom:\n  name: 'Explorador'\n");
        YamlConfiguration yaml = yaml("name: 'Explorer'\n");

        Languages.translate(plugin, ContentTranslationTest.class, "owned/custom.yml", yaml);

        assertEquals("Explorer", yaml.getString("name"));
    }

    @Test
    void aDefaultRewordedLaterIsStillNotTheOwners() throws Exception {
        String old = SHIPPED.replace("'Explorer'", "'Wayfarer'");
        onDisk(".defaults/files/reworded/explorer.yml", old);

        YamlConfiguration job = translated("reworded", SPANISH, old);

        assertEquals("Explorador", job.getString("name"), "the file still holds what an earlier version shipped");
    }

    @Test
    void aSingleFileCatalogueIsKeyedByItsOwnPaths() throws Exception {
        pack("flat.yml", "kits:\n  starter:\n    name: 'Starter'\n");
        pack("lang/es/content/flat.yml", "kits.starter.name: 'Inicial'\n");
        YamlConfiguration yaml = yaml("kits:\n  starter:\n    name: 'Starter'\n");

        Languages.translate(plugin, ContentTranslationTest.class, "flat.yml", yaml);

        assertEquals("Inicial", yaml.getString("kits.starter.name"));
    }

    @Test
    void anOwnerCanAddOrCorrectATranslationInTheDataFolder() throws Exception {
        onDisk("lang/es/content/override.yml", "explorer:\n  name: 'Trotamundos'\n");

        YamlConfiguration job = translated("override", SPANISH, SHIPPED);

        assertEquals("Trotamundos", job.getString("name"));
        assertEquals("Vagabundo", job.getString("perks.10.a.name"), "the rest still comes from the jar");
    }

    @Test
    void aBrokenTableIsTheServersTextNotACrash() throws Exception {
        DebugCapture.start();
        YamlConfiguration job = translated("broken", "explorer: [unclosed\n", SHIPPED);

        assertEquals("Explorer", job.getString("name"));
        assertEquals(1, Languages.translationProblems(ContentTranslationTest.class, "es", "broken/explorer.yml").size());
    }

    @Test
    void aKeyTheCatalogueDoesNotHaveIsAProblemInTests() throws Exception {
        pack("stale/explorer.yml", SHIPPED);
        pack("lang/es/content/stale.yml", "explorer:\n  perks.99.a.name: 'Viejo'\n");

        List<String> problems = Languages.translationProblems(ContentTranslationTest.class, "es", "stale/explorer.yml");

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("perks.99.a.name"), problems.get(0));
    }
}
