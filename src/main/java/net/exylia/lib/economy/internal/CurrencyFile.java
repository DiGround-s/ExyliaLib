package net.exylia.lib.economy.internal;

import net.exylia.lib.economy.CurrencyInfo;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * {@code plugins/ExyliaLib/currencies.yml}: the currencies the library itself
 * provides, and how every currency looks.
 *
 * <h2>Why this is not a record file</h2>
 * Every other setting in the library is a {@code Configs} record. This one is
 * a <em>map</em> — a block per currency, named whatever the owner called it —
 * and a record schema describes a fixed set of keys. So it is read by hand,
 * written once with defaults, and never overwritten: what is on disk is what
 * the owner edited.
 *
 * <h2>What is in it</h2>
 * <ul>
 *   <li>{@code stored} — currencies the library keeps in its own table.</li>
 *   <li>{@code items} — currencies that are items in the player's inventory.</li>
 *   <li>{@code experience} — whether XP levels and points are currencies.</li>
 *   <li>{@code display} — how any currency looks, the built-in ones included.</li>
 *   <li>{@code vault} — which stored currency, if any, is published as the
 *       server's Vault economy.</li>
 * </ul>
 */
public final class CurrencyFile {

    public static final String FILE = "currencies.yml";

    /** One currency the library stores. */
    public record Stored(
            @NotNull String id,
            @NotNull CurrencyInfo info,
            @NotNull List<String> aliases,
            @NotNull BigDecimal start,
            @NotNull BigDecimal max,
            @NotNull String permission,
            boolean transferable,
            @NotNull BigDecimal minimumTransfer,
            double transferTaxPercent,
            boolean exchangeable,
            @NotNull Map<String, BigDecimal> rates,
            boolean leaderboard,
            boolean networked,
            boolean commands) {

        /** Whether balances are capped. */
        public boolean isCapped() {
            return max.signum() > 0;
        }
    }

    /** One currency that is a kind of item. */
    public record Item(
            @NotNull String id,
            @NotNull CurrencyInfo info,
            @NotNull String item,
            @NotNull List<String> aliases) {
    }

    /** What the file said, all of it. */
    public record Contents(
            @NotNull Map<String, Stored> stored,
            @NotNull Map<String, Item> items,
            @NotNull Map<String, CurrencyInfo> display,
            boolean experienceLevels,
            boolean experiencePoints,
            @NotNull String vaultProvide,
            boolean vaultForce,
            boolean ledger) {

        /** The overlay for a currency, or {@code null} when the file has none. */
        public @Nullable CurrencyInfo overlay(@NotNull String id) {
            return display.get(id.toLowerCase(Locale.ROOT));
        }
    }

    private CurrencyFile() {
    }

    /**
     * Reads the file, writing the defaults first when it is missing.
     *
     * @param plugin the library
     * @param logger where a bad block is reported
     * @return what it says
     */
    public static @NotNull Contents load(@NotNull Plugin plugin, @NotNull Logger logger) {
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.exists()) {
            writeDefaults(file, logger);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        Map<String, CurrencyInfo> display = new LinkedHashMap<>();
        ConfigurationSection displaySection = yaml.getConfigurationSection("display");
        if (displaySection != null) {
            for (String id : displaySection.getKeys(false)) {
                ConfigurationSection block = displaySection.getConfigurationSection(id);
                if (block != null) display.put(id.toLowerCase(Locale.ROOT), info(id, block, -1));
            }
        }

        Map<String, Stored> stored = new LinkedHashMap<>();
        ConfigurationSection storedSection = yaml.getConfigurationSection("stored");
        if (storedSection != null) {
            for (String id : storedSection.getKeys(false)) {
                ConfigurationSection block = storedSection.getConfigurationSection(id);
                if (block == null) continue;
                String clean = id.toLowerCase(Locale.ROOT);
                if (!clean.matches("[a-z0-9_]{1,32}")) {
                    logger.warning("currencies.yml: '" + id + "' is not a currency id (letters, digits"
                            + " and underscores, up to 32); the block is skipped.");
                    continue;
                }
                stored.put(clean, stored(clean, block));
            }
        }

        Map<String, Item> items = new LinkedHashMap<>();
        ConfigurationSection itemSection = yaml.getConfigurationSection("items");
        if (itemSection != null) {
            for (String id : itemSection.getKeys(false)) {
                ConfigurationSection block = itemSection.getConfigurationSection(id);
                if (block == null) continue;
                String clean = id.toLowerCase(Locale.ROOT);
                String item = block.getString("item", "");
                if (item == null || item.isBlank()) {
                    logger.warning("currencies.yml: item currency '" + id + "' names no item; skipped.");
                    continue;
                }
                items.put(clean, new Item(clean, info(clean, block, 0), item.trim(),
                        lowered(block.getStringList("aliases"))));
            }
        }

        return new Contents(stored, items, display,
                yaml.getBoolean("experience.levels", true),
                yaml.getBoolean("experience.points", true),
                yaml.getString("vault.provide", "") == null ? "" : yaml.getString("vault.provide", "").trim(),
                yaml.getBoolean("vault.force", false),
                yaml.getBoolean("ledger.enabled", true));
    }

    private static Stored stored(String id, ConfigurationSection block) {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        ConfigurationSection rateSection = block.getConfigurationSection("exchange.rates");
        if (rateSection != null) {
            for (String other : rateSection.getKeys(false)) {
                double rate = rateSection.getDouble(other, 0);
                if (rate > 0) rates.put(other.toLowerCase(Locale.ROOT), BigDecimal.valueOf(rate));
            }
        }
        return new Stored(id,
                info(id, block, 0),
                lowered(block.getStringList("aliases")),
                decimal(block, "start", 0),
                decimal(block, "max", -1),
                block.getString("permission", "") == null ? "" : block.getString("permission", "").trim(),
                block.getBoolean("transfer.enabled", true),
                decimal(block, "transfer.minimum", 1),
                Math.max(0, block.getDouble("transfer.tax-percent", 0)),
                block.getBoolean("exchange.enabled", true),
                rates,
                block.getBoolean("leaderboard", true),
                block.getBoolean("networked", true),
                block.getBoolean("commands", true));
    }

    /**
     * The look of one block.
     *
     * @param defaultDecimals what {@code decimals} means when absent; {@code -1}
     *                        for an overlay, which then keeps the provider's
     */
    private static CurrencyInfo info(String id, ConfigurationSection block, int defaultDecimals) {
        return new CurrencyInfo(id,
                block.getString("name", ""),
                block.getString("plural", ""),
                block.getString("symbol", ""),
                block.getString("icon", ""),
                block.getInt("decimals", defaultDecimals),
                block.getString("format", ""),
                block.getString("compact-format", ""));
    }

    private static BigDecimal decimal(ConfigurationSection block, String key, double fallback) {
        return BigDecimal.valueOf(block.getDouble(key, fallback));
    }

    private static List<String> lowered(List<String> values) {
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) out.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(out);
    }

    private static void writeDefaults(File file, Logger logger) {
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            java.nio.file.Files.writeString(file.toPath(), DEFAULTS);
        } catch (IOException failure) {
            logger.warning("Could not write " + FILE + ": " + failure.getMessage());
        }
    }

    /** The file a fresh server gets. Written once and never touched again. */
    static final String DEFAULTS = """
            # The currencies of this server, and how every currency looks.
            #
            # Written once, when it is missing, and never overwritten. Run
            # /exylialib reload after editing.
            #
            # A plugin asks for money by id: Economy.of("shards"). Which id answers
            # when a plugin does not name one is economy.yml's default-currency.
            #
            # ---------------------------------------------------------------------
            # stored: currencies the library keeps itself, in its own table.
            #
            #   name / plural / symbol / icon   how it looks everywhere
            #   decimals        0 for a whole-number currency
            #   format          how an amount is written. %amount% %symbol% %name%
            #   compact-format  the same, for a scoreboard: %amount% is 1.2k
            #   aliases         commands that open this currency: /shards, /shards pay ...
            #   start           what a new player begins with
            #   max             a ceiling on a balance. -1 = none
            #   permission      needed to use the currency's commands. blank = nobody needs one
            #   transfer        whether players can /pay each other, the least they may
            #                   send, and a percentage kept back from every transfer
            #   exchange        whether it can be swapped for other currencies, and at
            #                   what rate: 'shards: 0.01' means 1 of this = 0.01 shards
            #   leaderboard     whether /shards top and the top placeholders work
            #   networked       whether balances follow the player across servers that
            #                   share this database. Off = each server keeps its own
            #   commands        whether the alias commands exist at all
            # ---------------------------------------------------------------------
            stored:
              shards:
                name: Shard
                plural: Shards
                symbol: "\\u2726"
                icon: AMETHYST_SHARD
                decimals: 0
                format: "%amount% %symbol%"
                compact-format: "%amount%%symbol%"
                aliases: [shards, shard]
                start: 0
                max: -1
                permission: ""
                transfer:
                  enabled: true
                  minimum: 1
                  tax-percent: 0
                exchange:
                  enabled: false
                  rates: {}
                leaderboard: true
                networked: true
                commands: true

            # ---------------------------------------------------------------------
            # items: currencies that are items. The balance is how many the player
            # is carrying; paying takes them out of the inventory. Only for players
            # who are online here.
            #
            #   item   a material name, or a 'bytes:' item snapshot for a custom item
            # ---------------------------------------------------------------------
            items:
              netherite_ingots:
                item: NETHERITE_INGOT
                name: Netherite Ingot
                plural: Netherite Ingots
                symbol: ""
                icon: NETHERITE_INGOT
                format: "%amount% %name%"
                aliases: []

            # ---------------------------------------------------------------------
            # experience: whether a player's XP is a currency. 'xp_levels' and
            # 'xp_points' become ids a shop can price in.
            # ---------------------------------------------------------------------
            experience:
              levels: false
              points: true

            # ---------------------------------------------------------------------
            # display: how a currency the library did not invent looks. Anything
            # left out keeps what the provider says.
            # ---------------------------------------------------------------------
            display:
              vault:
                name: Dollar
                plural: Dollars
                symbol: "$"
                icon: GOLD_INGOT
                decimals: 2
                format: "%symbol%%amount%"
                compact-format: "%symbol%%amount%"
              points:
                name: Point
                plural: Points
                symbol: ""
                icon: NETHER_STAR
                decimals: 0
                format: "%amount% %name%"

            # ---------------------------------------------------------------------
            # vault: publish one stored currency as the server's Vault economy, so
            # every plugin that only speaks Vault uses it. Blank = never. Only when
            # no other economy plugin has registered one, unless 'force' is on.
            # ---------------------------------------------------------------------
            vault:
              provide: ""
              force: false

            # ---------------------------------------------------------------------
            # ledger: whether every operation on a stored currency is written down,
            # with its reason, for /economy history.
            # ---------------------------------------------------------------------
            ledger:
              enabled: true
            """;
}
