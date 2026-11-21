package net.exylia.lib.web.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Menu;
import net.exylia.lib.config.internal.BundledResources;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A plugin's own settings screens, as the web editor shows them: the hub, its pages and every
 * card, with the names and explanations the plugin wrote, in every language it ships.
 *
 * <p>Nothing is registered for this. The screens are found in the plugin's jar under
 * {@code lang/<code>/}: a hub is a menu whose buttons run {@code <namespace>:settings_page <page>},
 * a card one that runs {@code <namespace>:setting <page> <key>} (the shape
 * {@code tools/settings-cards} writes and {@code ConfigEdits} reads). Which file a page edits is
 * not written in the menus, so it is found among the plugin's record subjects: the one that holds
 * the most of the page's keys, a tie settled by the page's name in the file's path, and a page
 * still ambiguous left out rather than guessed.
 */
final class SettingsScreens {

    private static final Pattern HUB_ACTION = Pattern.compile("([a-z0-9_-]+):settings_page\\s+(\\S+)");
    private static final Pattern CARD_ACTION = Pattern.compile("([a-z0-9_-]+):setting\\s+(\\S+)\\s+([A-Za-z0-9_.-]+)");
    private static final Pattern LOCALE = Pattern.compile("[a-z]{2}(?:_[a-z]{2})?");
    private static final Pattern GRADIENT = Pattern.compile("<gradient:[^>]*>(.*?)</gradient>");
    private static final Pattern STAT = Pattern.compile("\\{icon:([a-z0-9_:/.-]+)(?:\\|[^}]*)?}\\s*\\{letters}(.*?)\\{letters_black}:\\s*%([^%\\s]+)%");
    private static final Pattern TAGS = Pattern.compile("\\{[a-z_]+}|<[^>]*>|&[0-9a-fk-or]");

    /** What was read from each plugin's jar, kept for as long as that plugin instance lives. */
    private static final Map<Plugin, Screens> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private SettingsScreens() {
    }

    /** A text in every language that has it. */
    record Text(Map<String, String> byLocale) {
        JsonObject json() {
            JsonObject out = new JsonObject();
            byLocale.forEach(out::addProperty);
            return out;
        }
    }

    /** Lines in every language that has them. */
    record Lines(Map<String, List<String>> byLocale) {
        JsonObject json() {
            JsonObject out = new JsonObject();
            byLocale.forEach((locale, lines) -> {
                JsonArray array = new JsonArray();
                lines.forEach(array::add);
                out.add(locale, array);
            });
            return out;
        }
    }

    record Stat(String placeholder, String icon, Text label) {
    }

    record Card(String key, String material, String icon, Text title, Lines lines, Text label) {
    }

    record Page(String id, String material, Text title, Lines summary, Lines about, List<Stat> stats, List<Card> cards) {
    }

    /** The screens as the jar holds them, before any page is matched to a file. */
    record Screens(Lines about, List<Page> pages) {
        static final Screens NONE = new Screens(new Lines(Map.of()), List.of());
    }

    /** A record file the session may edit: where a page's keys are looked for. */
    record Candidate(String key, String file, JsonObject values, @Nullable ConfigFile<?> config) {
    }

    // ------------------------------------------------------------------ reading the jar

    /** The plugin's screens, read once per plugin instance; none when it ships no settings hub. */
    static Screens of(Plugin plugin) {
        Screens known = CACHE.get(plugin);
        if (known != null) return known;
        Screens read = Screens.NONE;
        Path staging = null;
        try {
            staging = Files.createTempDirectory("exylia-settings-");
            BundledResources.extract(plugin.getClass(), Path.of("lang"), staging);
            read = read(staging);
        } catch (IOException | java.net.URISyntaxException | RuntimeException none) {
            // No lang folder, or one that cannot be read: the plugin has no screens to show.
        } finally {
            BundledResources.deleteTree(staging);
        }
        CACHE.put(plugin, read);
        return read;
    }

    /** Reads every language folder under {@code lang}: {@code lang/en/menus/admin/settings.yml}. */
    static Screens read(Path lang) throws IOException {
        Map<String, Map<String, YamlConfiguration>> locales = new LinkedHashMap<>();
        try (Stream<Path> dirs = Files.list(lang)) {
            for (Path dir : dirs.sorted().toList()) {
                String code = dir.getFileName().toString();
                if (!Files.isDirectory(dir) || !LOCALE.matcher(code).matches()) continue;
                Map<String, YamlConfiguration> files = new LinkedHashMap<>();
                try (Stream<Path> walk = Files.walk(dir)) {
                    for (Path file : walk.filter(path -> path.toString().endsWith(".yml")).sorted().toList()) {
                        String text = Files.readString(file);
                        if (!text.contains(":setting")) continue;
                        YamlConfiguration yaml = new YamlConfiguration();
                        try {
                            yaml.loadFromString(text);
                        } catch (org.bukkit.configuration.InvalidConfigurationException broken) {
                            continue;
                        }
                        files.put(dir.relativize(file).toString().replace('\\', '/'), yaml);
                    }
                }
                if (!files.isEmpty()) locales.put(code, files);
            }
        }
        if (locales.isEmpty()) return Screens.NONE;
        String reference = locales.containsKey("en") ? "en" : locales.keySet().iterator().next();
        Map<String, YamlConfiguration> base = locales.get(reference);

        // The hub: its header, and one button per page, in slot order.
        String namespace = null;
        String hubFile = null;
        Map<String, String> pageButtons = new LinkedHashMap<>();
        for (var file : base.entrySet()) {
            for (String item : items(file.getValue())) {
                Matcher hub = firstAction(file.getValue(), item, HUB_ACTION);
                if (hub == null || pageButtons.containsKey(hub.group(2))) continue;
                if (namespace == null) {
                    namespace = hub.group(1);
                    hubFile = file.getKey();
                }
                if (hub.group(1).equals(namespace)) pageButtons.put(hub.group(2), file.getKey() + "\n" + item);
            }
        }
        if (namespace == null) return Screens.NONE;

        // The cards of each page, file by file, in slot order.
        Map<String, List<String[]>> cards = new LinkedHashMap<>();
        Map<String, String> pageFile = new LinkedHashMap<>();
        for (var file : base.entrySet()) {
            for (String item : items(file.getValue())) {
                Matcher card = firstAction(file.getValue(), item, CARD_ACTION);
                if (card == null || !card.group(1).equals(namespace) || !pageButtons.containsKey(card.group(2))) continue;
                cards.computeIfAbsent(card.group(2), id -> new ArrayList<>()).add(new String[]{file.getKey(), item, card.group(3)});
                pageFile.putIfAbsent(card.group(2), file.getKey());
            }
        }

        List<Page> pages = new ArrayList<>();
        for (var button : pageButtons.entrySet()) {
            String id = button.getKey();
            List<String[]> own = cards.get(id);
            if (own == null) continue;
            String[] at = button.getValue().split("\n", 2);
            ConfigurationSection hubItem = base.get(at[0]).getConfigurationSection("items." + at[1]);
            List<Card> parsed = new ArrayList<>();
            for (String[] card : own) {
                ConfigurationSection item = base.get(card[0]).getConfigurationSection("items." + card[1]);
                String placeholder = placeholder(id, card[2]);
                parsed.add(new Card(card[2], material(item), statIcon(item, placeholder),
                        text(locales, card[0], card[1], SettingsScreens::title),
                        lines(locales, card[0], card[1]),
                        text(locales, card[0], card[1], section -> statLabel(section, placeholder))));
            }
            List<Stat> stats = new ArrayList<>();
            for (String line : lore(hubItem)) {
                Matcher stat = STAT.matcher(line);
                if (!stat.find() || !stat.group(3).startsWith(id + "_") || stat.group(3).equals(id + "_state")) continue;
                String name = stat.group(3);
                stats.add(new Stat(name, stat.group(1), text(locales, at[0], at[1], section -> statLabel(section, name))));
            }
            pages.add(new Page(id, material(hubItem), text(locales, at[0], at[1], SettingsScreens::title),
                    lines(locales, at[0], at[1]), lines(locales, pageFile.get(id), "header"), stats, parsed));
        }
        return new Screens(lines(locales, hubFile, "header"), pages);
    }

    private static List<String> items(YamlConfiguration yaml) {
        ConfigurationSection items = yaml.getConfigurationSection("items");
        return items == null ? List.of() : List.copyOf(items.getKeys(false));
    }

    private static @Nullable Matcher firstAction(YamlConfiguration yaml, String item, Pattern pattern) {
        for (String action : yaml.getStringList("items." + item + ".actions")) {
            Matcher matcher = pattern.matcher(action);
            if (matcher.find()) return matcher;
        }
        return null;
    }

    private static String material(@Nullable ConfigurationSection item) {
        String material = item == null ? null : item.getString("material");
        return material == null || material.startsWith("basehead-") ? "" : material.trim().toUpperCase(Locale.ROOT);
    }

    private static List<String> lore(@Nullable ConfigurationSection item) {
        return item == null ? List.of() : item.getStringList("lore");
    }

    /** What the name says the button is: the gradient's text, or the whole name without colours. */
    static @Nullable String title(ConfigurationSection item) {
        String name = item.getString("name");
        if (name == null) return null;
        Matcher gradient = GRADIENT.matcher(name);
        String text = gradient.find() ? gradient.group(1) : name;
        text = TAGS.matcher(text).replaceAll("").trim();
        return text.isEmpty() ? null : text;
    }

    /** The lines under the card's {@code ✎} label, up to the blank line, with their indent and lead colour taken off. */
    static List<String> information(ConfigurationSection item) {
        List<String> out = new ArrayList<>();
        boolean in = false;
        for (String line : lore(item)) {
            if (!in) {
                in = line.contains("✎");
                continue;
            }
            if (line.isBlank()) break;
            String trimmed = line.strip();
            if (trimmed.startsWith("{letters}")) trimmed = trimmed.substring("{letters}".length());
            out.add(trimmed);
        }
        return out;
    }

    private static @Nullable String statLabel(ConfigurationSection item, String placeholder) {
        for (String line : lore(item)) {
            Matcher stat = STAT.matcher(line);
            if (stat.find() && stat.group(3).equals(placeholder)) {
                String label = TAGS.matcher(stat.group(2)).replaceAll("").trim();
                return label.isEmpty() ? null : label;
            }
        }
        return null;
    }

    private static String statIcon(@Nullable ConfigurationSection item, String placeholder) {
        for (String line : lore(item)) {
            Matcher stat = STAT.matcher(line);
            if (stat.find() && stat.group(3).equals(placeholder)) return stat.group(1);
        }
        return "";
    }

    private static Text text(Map<String, Map<String, YamlConfiguration>> locales, String file, String item,
                             java.util.function.Function<ConfigurationSection, String> read) {
        Map<String, String> out = new LinkedHashMap<>();
        locales.forEach((code, files) -> {
            YamlConfiguration yaml = files.get(file);
            ConfigurationSection section = yaml == null ? null : yaml.getConfigurationSection("items." + item);
            String value = section == null ? null : read.apply(section);
            if (value != null) out.put(code, value);
        });
        return new Text(out);
    }

    private static Lines lines(Map<String, Map<String, YamlConfiguration>> locales, @Nullable String file, String item) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (file == null) return new Lines(out);
        locales.forEach((code, files) -> {
            YamlConfiguration yaml = files.get(file);
            ConfigurationSection section = yaml == null ? null : yaml.getConfigurationSection("items." + item);
            List<String> value = section == null ? List.of() : information(section);
            if (!value.isEmpty()) out.put(code, value);
        });
        return new Lines(out);
    }

    /** The menu placeholder a key's value is shown with on a page: {@code ah_settings_listing_fee_percent}. */
    static String placeholder(String page, String key) {
        return page + "_" + key.replace('.', '_').replace('-', '_');
    }

    // ------------------------------------------------------------------ matching to files

    /**
     * The screens as the editor reads them, each page pointed at the subject whose file it edits.
     * A card whose key no subject holds is left out, and so is a page with none left.
     *
     * @return {@code null} when the plugin has no screens, or none of them matched a subject
     */
    static @Nullable JsonObject json(Screens screens, List<Candidate> candidates, String pluginName) {
        if (screens.pages().isEmpty() || candidates.isEmpty()) return null;
        JsonArray pages = new JsonArray();
        for (Page page : screens.pages()) {
            Candidate subject = match(page, candidates);
            if (subject == null) continue;
            JsonArray cards = new JsonArray();
            for (Card card : page.cards()) {
                Candidate owner = has(subject.values(), card.key()) ? subject : only(card.key(), candidates);
                if (owner == null) continue;
                JsonObject out = new JsonObject();
                out.addProperty("key", card.key());
                out.addProperty("subject", owner.key());
                out.addProperty("material", card.material());
                out.addProperty("icon", card.icon());
                out.add("title", card.title().json());
                out.add("lines", card.lines().json());
                out.add("label", card.label().json());
                if (owner.config() != null) control(owner.config(), card.key(), pluginName, out);
                cards.add(out);
            }
            if (cards.isEmpty()) continue;
            JsonArray stats = new JsonArray();
            for (Stat stat : page.stats()) {
                String key = keyOf(stat.placeholder(), page, subject.values());
                if (key == null) continue;
                JsonObject out = new JsonObject();
                out.addProperty("key", key);
                out.addProperty("icon", stat.icon());
                out.add("label", stat.label().json());
                stats.add(out);
            }
            JsonObject out = new JsonObject();
            out.addProperty("id", page.id());
            out.addProperty("subject", subject.key());
            out.addProperty("material", page.material());
            out.add("title", page.title().json());
            out.add("summary", page.summary().json());
            out.add("about", page.about().json());
            out.add("stats", stats);
            out.add("cards", cards);
            pages.add(out);
        }
        if (pages.isEmpty()) return null;
        JsonObject out = new JsonObject();
        out.add("about", screens.about().json());
        out.add("pages", pages);
        return out;
    }

    /** The subject a page edits: most of its keys, then its name in the path; {@code null} when still more than one. */
    static @Nullable Candidate match(Page page, List<Candidate> candidates) {
        int best = 0;
        List<Candidate> top = new ArrayList<>();
        for (Candidate candidate : candidates) {
            int held = 0;
            for (Card card : page.cards()) if (has(candidate.values(), card.key())) held++;
            if (held > best) {
                best = held;
                top.clear();
            }
            if (held == best && held > 0) top.add(candidate);
        }
        if (top.size() <= 1) return top.isEmpty() ? null : top.get(0);
        List<String> names = new ArrayList<>(List.of(slug(page.id())));
        String english = page.title().byLocale().get("en");
        if (english != null) names.add(slug(english));
        List<Candidate> named = top.stream()
                .filter(candidate -> names.stream().anyMatch(name -> !name.isEmpty()
                        && List.of(candidate.file().toLowerCase(Locale.ROOT).split("/")).contains(name)))
                .toList();
        return named.size() == 1 ? named.get(0) : null;
    }

    private static @Nullable Candidate only(String key, List<Candidate> candidates) {
        List<Candidate> holding = candidates.stream().filter(candidate -> has(candidate.values(), key)).toList();
        return holding.size() == 1 ? holding.get(0) : null;
    }

    private static String slug(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    /** Whether the values hold a dotted key. */
    static boolean has(JsonObject values, String key) {
        JsonElement node = values;
        for (String part : key.split("\\.")) {
            if (!(node instanceof JsonObject object) || !object.has(part)) return false;
            node = object.get(part);
        }
        return true;
    }

    /** The key a hub's {@code %page_settings_x%} shows: one of the page's cards, else any key of its file. */
    private static @Nullable String keyOf(String placeholder, Page page, JsonObject values) {
        for (Card card : page.cards()) {
            if (placeholder(page.id(), card.key()).equals(placeholder)) return card.key();
        }
        List<String> found = new ArrayList<>();
        leaves(values, "", page.id(), placeholder, found);
        return found.size() == 1 ? found.get(0) : null;
    }

    private static void leaves(JsonObject node, String prefix, String page, String placeholder, List<String> found) {
        for (var entry : node.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (placeholder(page, key).equals(placeholder)) found.add(key);
            else if (entry.getValue() instanceof JsonObject inner && placeholder.startsWith(placeholder(page, key) + "_")) {
                leaves(inner, key, page, placeholder, found);
            }
        }
    }

    /**
     * How the card's value is edited, as the in-game card edits it: its kind, bounds, format,
     * choices, and the value it resets to, in the shape the file holds it.
     */
    private static void control(ConfigFile<?> file, String key, String pluginName, JsonObject out) {
        ConfigFields.Field field = ConfigFields.field(file.schema(), key);
        if (field == null) return;
        JsonObject control = new JsonObject();
        control.addProperty("kind", field.kind().name().toLowerCase(Locale.ROOT));
        ConfigFields.Bounds bounds = ConfigFields.bounds(field);
        if (bounds != null) {
            number(control, "min", bounds.min());
            number(control, "max", bounds.max());
            number(control, "step", bounds.step());
            if (bounds.hasNone()) number(control, "none", bounds.none());
        }
        Menu.Format format = field.hint(Menu.Format.class);
        if (format != null) control.addProperty("format", format.value().name().toLowerCase(Locale.ROOT));
        try {
            Object values = file.get();
            String currency = values == null ? null : ConfigFields.currencyOf(field, file.schema(), values);
            if (currency != null && !currency.isEmpty()) control.addProperty("currency", currency);
        } catch (RuntimeException unreadable) {
            // Shown in the default currency.
        }
        Menu.Unit unit = field.hint(Menu.Unit.class);
        if (unit != null) control.addProperty("unit", unit.value());
        if (ConfigFields.materials(field)) control.addProperty("materials", true);
        if (ConfigFields.currency(field)) control.addProperty("currencyId", true);
        List<String[]> choices = ConfigFields.choices(field.owner(), field.schema().name());
        if (choices != null) {
            JsonArray list = new JsonArray();
            for (String[] choice : choices) {
                JsonObject item = new JsonObject();
                item.addProperty("value", choice[0]);
                item.addProperty("label", choice[1]);
                list.add(item);
            }
            control.add("choices", list);
        }
        out.add("control", control);
        try {
            Object defaults = ConfigMenu.defaults(file);
            if (defaults != null) {
                JsonElement node = ValueCodec.toJson(file.schema().type(), pluginName, defaults);
                for (String part : key.split("\\.")) {
                    node = node instanceof JsonObject object ? object.get(part) : null;
                }
                if (node != null) out.add("default", node);
            }
        } catch (RuntimeException unreadable) {
            // No default to reset to.
        }
    }

    private static void number(JsonObject out, String name, double value) {
        if (Double.isFinite(value) && Math.abs(value) < Double.MAX_VALUE / 2) {
            if (value == Math.rint(value) && Math.abs(value) < 1e15) out.addProperty(name, (long) value);
            else out.addProperty(name, value);
        }
    }

    /** Forgets what was read, for tests. */
    static void forget() {
        CACHE.clear();
    }
}
