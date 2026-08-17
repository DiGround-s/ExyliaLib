package net.exylia.lib.economy.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.economy.CurrencyProvider;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.EconomySettings;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file a server owner actually opens.
 *
 * <p>Every other economy test goes through {@link EconomySettings} as a record,
 * which proves the resolution and nothing about the YAML. The owner never sees
 * the record: they see {@code plugins/ExyliaLib/economy.yml}, and if a key is
 * named differently there than {@code docs/economy.md} says, the module is
 * wrong in the only place that is hard to notice — the owner edits a key that
 * does nothing, the default currency never changes, and the shop keeps charging
 * the economy they were trying to move off.
 */
class EconomyFileTest {

    @TempDir
    Path folder;

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Configs.releaseAll();
        CurrencyRegistry.clearForTests();
        BalanceCache.apply(new EconomySettings());
        plugin = FakeServer.newPlugin("ExyliaLib", folder.toFile());
    }

    @AfterEach
    void tearDown() {
        CurrencyRegistry.clearForTests();
        BalanceCache.invalidateAll();
        Configs.releaseAll();
    }

    private String generate() throws IOException {
        Configs.define(plugin, "economy", EconomySettings.class).load();
        return Files.readString(folder.resolve("economy.yml"), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------- keys

    @Test
    @DisplayName("the file is generated with every documented key")
    void generatesEveryKey() throws IOException {
        String yaml = generate();

        for (String key : new String[] {
                "default-currency:", "fallback:", "balance-cache-millis:"}) {
            assertTrue(yaml.contains(key),
                    () -> "docs/economy.md documents " + key + ", generated file:\n" + yaml);
        }
    }

    @Test
    @DisplayName("the defaults on disk are the ones documented")
    void documentedDefaults() throws IOException {
        String yaml = generate();

        assertTrue(yaml.contains("default-currency: vault")
                        || yaml.contains("default-currency: 'vault'")
                        || yaml.contains("default-currency: \"vault\""),
                () -> "expected a vault default:\n" + yaml);
        assertTrue(yaml.contains("- points") || yaml.contains("- 'points'"),
                () -> "expected points as the only fallback:\n" + yaml);
        assertTrue(yaml.contains("balance-cache-millis: 500"),
                () -> "expected a 500 ms cache window:\n" + yaml);
    }

    @Test
    @DisplayName("every key carries a comment for the owner")
    void everyKeyIsDocumented() throws IOException {
        // A setting with no explanation is one a server owner changes by
        // guessing, and then reports the result as a bug. Money is the setting
        // where that guess costs the most.
        String yaml = generate();
        String[] lines = yaml.split("\n");

        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")
                    || !line.contains(":")) {
                continue;
            }
            boolean documented = false;
            for (int back = index - 1; back >= 0; back--) {
                String previous = lines[back].strip();
                if (previous.isEmpty()) {
                    break;
                }
                if (previous.startsWith("#")) {
                    documented = true;
                    break;
                }
                break;
            }
            assertTrue(documented, "undocumented key on line " + (index + 1) + ": " + line);
        }
    }

    @Test
    @DisplayName("the file loads back into the defaults it was written from")
    void roundTrips() throws IOException {
        generate();
        Configs.releaseAll();

        ConfigFile<EconomySettings> reread =
                Configs.define(plugin, "economy", EconomySettings.class).load();

        assertEquals(new EconomySettings(), reread.get(),
                "a freshly generated file must read back as the defaults");
    }

    // ---------------------------------------------------------- behaviour

    @Test
    @DisplayName("an owner's edit to default-currency changes which currency serves")
    void editingDefaultCurrencyChangesWhoServes() throws IOException {
        // The end-to-end promise of the file: edit one key, every shop, kit and
        // reward on the server follows. Everything else here is an
        // implementation detail of that.
        generate();
        Configs.releaseAll();

        Path file = folder.resolve("economy.yml");
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8)
                .replace("default-currency: vault", "default-currency: points")
                .replace("default-currency: 'vault'", "default-currency: 'points'"),
                StandardCharsets.UTF_8);

        ConfigFile<EconomySettings> edited =
                Configs.define(plugin, "economy", EconomySettings.class).load();
        assertEquals("points", edited.get().defaultCurrency(),
                "the edited key must reach the record");

        UUID player = UUID.randomUUID();
        FakeBank vault = new FakeBank("vault", new BigDecimal("100"));
        FakeBank points = new FakeBank("points", new BigDecimal("7"));
        CurrencyRegistry.install("vault", vault);
        CurrencyRegistry.install("points", points);

        CurrencyRegistry.apply(edited.get());
        BalanceCache.apply(edited.get());

        assertEquals(0, new BigDecimal("7").compareTo(Economy.balance(player)),
                "the default view must read the currency the file names");
        assertTrue(Economy.charge(player, new BigDecimal("7")));
        assertEquals(0, new BigDecimal("100").compareTo(vault.balance(player)),
                "the currency the file no longer names must be untouched");
    }

    /** A currency whose balance is one number, enough to tell two apart. */
    private static final class FakeBank implements CurrencyProvider {

        private final String id;
        private BigDecimal balance;

        FakeBank(String id, BigDecimal balance) {
            this.id = id;
            this.balance = balance;
        }

        @Override
        public @NotNull String id() {
            return id;
        }

        @Override
        public @NotNull String displayName() {
            return "Fake " + id;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public @NotNull BigDecimal balance(@NotNull UUID player) {
            return balance;
        }

        @Override
        public @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount) {
            balance = balance.add(amount);
            return EconomyResponse.success(amount, balance);
        }

        @Override
        public @NotNull EconomyResponse withdraw(@NotNull UUID player, @NotNull BigDecimal amount) {
            if (balance.compareTo(amount) < 0) {
                return EconomyResponse.insufficientFunds(amount, balance);
            }
            balance = balance.subtract(amount);
            return EconomyResponse.success(amount, balance);
        }

        @Override
        public @NotNull String currencyName(boolean plural) {
            return plural ? "coins" : "coin";
        }

        @Override
        public @NotNull String symbol() {
            return "C";
        }
    }
}
