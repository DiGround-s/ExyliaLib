package net.exylia.lib.internal;

import net.exylia.lib.ExyliaLib;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.screen.ScreenSettings;
import net.exylia.lib.text.Lines;
import net.exylia.lib.text.Lore;
import net.exylia.lib.text.Palette;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.exylia.lib.ui.PluginMenus;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.ui.UiKeys;
import net.exylia.lib.ui.UiSession;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The screens of {@code /exylialib setup}: a hub with one card per step, and a page per step.
 *
 * <p>Every choice is written to the library's own files and applied the moment it is clicked, so the
 * page redraws in the new language or colours straight away. Only the database waits for a restart.
 */
public final class SetupMenu {

    static final String HUB = "setup";
    static final String LANGUAGE = "setup_language";
    static final String MENUS = "setup_menus";
    static final String LOOK = "setup_look";
    static final String NETWORK = "setup_network";

    private static final String BACK_HEAD = "basehead-eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjIzZmI2NzQyOTcxNmIyMWJjNmU4ZTdkNjY5Y2VkZGY2NWIxM2UwNzkwYTVjZTU1YjJlMDc3YjgyZDE5ZTEyNCJ9fX0=";

    /** The languages the library ships, with the name each one calls itself. */
    record Language(String code, String name, String icon) {
    }

    static final List<Language> LANGUAGES = List.of(
            new Language("en", "English", "BLUE_BANNER"),
            new Language("es", "Español", "ORANGE_BANNER"),
            new Language("pt", "Português", "LIME_BANNER"),
            new Language("fr", "Français", "WHITE_BANNER"),
            new Language("ru", "Русский", "RED_BANNER"),
            new Language("tr", "Türkçe", "MAGENTA_BANNER"));

    /** A colour theme: the five colours that make the identity; the meaning colours stay as they are. */
    record Theme(String id, String name, String icon, String primary, String secondary, String secondaryLight,
                 String letters, String lettersBlack) {

        boolean worn(Palette palette) {
            return primary.equalsIgnoreCase(palette.primary()) && secondary.equalsIgnoreCase(palette.secondary());
        }

        Palette on(Palette p) {
            return new Palette(primary, secondary, secondaryLight, letters, lettersBlack, p.error(), p.errorLight(),
                    p.success(), p.successLight(), p.warning(), p.warningLight(), p.info(), p.infoLight(),
                    p.accent(), p.neutral(), p.highlight(), p.muted(), p.serverIp());
        }
    }

    static final List<Theme> THEMES = List.of(
            new Theme("exylia", "Exylia", "AMETHYST_SHARD", "#8a51c4", "#aa76de", "#b48fd9", "#e7cfff", "#a89ab5"),
            new Theme("ocean", "Ocean", "PRISMARINE_CRYSTALS", "#3d7fd6", "#5e9ce6", "#8cb9ef", "#d8e9ff", "#95a7bd"),
            new Theme("emerald", "Emerald", "EMERALD", "#2e9d6b", "#4cc28a", "#7fd7aa", "#d6f7e7", "#92b3a4"),
            new Theme("crimson", "Crimson", "REDSTONE", "#c23a52", "#da5d72", "#e68b9b", "#ffdbe1", "#b69ba1"),
            new Theme("gold", "Gold", "GOLD_INGOT", "#c58f25", "#dcab45", "#e8c676", "#fff1cf", "#b7a98c"));

    /** A card on a setup page: what a click on it does. */
    record Card(String id, Consumer<Player> click) {
    }

    private static volatile @Nullable PluginMenus menus;

    private SetupMenu() {
    }

    /**
     * Registers the actions. Once, before the screens are loaded.
     *
     * @param plugin    the library
     * @param namespace the library's menu and action namespace
     */
    public static void init(@NotNull Plugin plugin, @NotNull String namespace) {
        PluginActions actions = Actions.of(plugin, namespace);
        actions.registerSync("setup_click", (context, arguments) -> {
            context.get(UiKeys.ENTRY).filter(Card.class::isInstance).map(Card.class::cast)
                    .ifPresent(card -> card.click().accept(context.player()));
            return ActionResult.success();
        });
        actions.registerSync("setup_hub", (context, arguments) -> {
            open(context.player(), HUB);
            return ActionResult.success();
        });
        actions.registerSync("setup_finish", (context, arguments) -> {
            Player player = context.player();
            context.get(UiKeys.SESSION).ifPresentOrElse(UiSession::close, player::closeInventory);
            SetupWizard.finish(player);
            return ActionResult.success();
        });
    }

    /** Compiles the screens into the library's menus, in the language now in force. */
    public static void load(@NotNull PluginMenus target) {
        target.load(HUB, hub());
        target.load(LANGUAGE, page(27, "10,11,12,14,15,16", 22, "BOOK", Lore.Tone.INFO,
                Phrases.tr("Language"), Phrases.tr("Setup · step 1"),
                List.of(Phrases.tr("Every plugin set to {highlight}default"),
                        Phrases.tr("speaks the language chosen here."))));
        target.load(MENUS, page(27, "12,13,14", 22, "CHEST", Lore.Tone.INFO,
                Phrases.tr("Menus"), Phrases.tr("Setup · step 2"),
                List.of(Phrases.tr("How the menus of every plugin open."),
                        Phrases.tr("A client that cannot draw screens"),
                        Phrases.tr("always gets chest windows."))));
        target.load(LOOK, page(36, "11,12,13,14,15,21,23", 31, "AMETHYST_SHARD", Lore.Tone.INFO,
                Phrases.tr("Look"), Phrases.tr("Setup · step 3"),
                List.of(Phrases.tr("The colours and letters of every"),
                        Phrases.tr("message, menu and scoreboard."))));
        target.load(NETWORK, page(27, "12,14", 22, "ENDER_EYE", Lore.Tone.INFO,
                Phrases.tr("Database"), Phrases.tr("Setup · step 4"),
                List.of(Phrases.tr("Players keep their data between"),
                        Phrases.tr("servers only when every server"),
                        Phrases.tr("shares one database."))));
        menus = target;
    }

    /** Forgets the screens, when the library disables. */
    public static void release() {
        menus = null;
    }

    /**
     * Opens one of the setup screens.
     *
     * @param player who sets the server up; any thread
     * @param page   {@link #HUB} or a step
     */
    public static void open(@NotNull Player player, @NotNull String page) {
        PluginMenus current = menus;
        if (current == null) {
            return;
        }
        // Read once: the cards change only on a click, which opens the page again.
        List<UiEntry> rows = rows(page);
        current.open(player, page, new HashMap<>(), () -> rows);
    }

    private static List<UiEntry> rows(String page) {
        return switch (page) {
            case LANGUAGE -> languageCards();
            case MENUS -> menuCards();
            case LOOK -> lookCards();
            case NETWORK -> networkCards();
            default -> hubCards();
        };
    }

    // ------------------------------------------------------------------ hub

    private static List<UiEntry> hubCards() {
        Lore.Tone tone = Lore.Tone.PRIMARY;
        List<UiEntry> cards = new ArrayList<>();
        cards.add(card("language", "BOOK", Lore.name(tone, Phrases.tr("Setup"), Phrases.tr("Language")), false,
                Lore.of(tone).subtitle(Phrases.tr("Step 1"))
                        .section(Phrases.tr("Information")).text(Phrases.tr("The language every plugin speaks."))
                        .section("✦", Phrases.tr("Status")).stat("book", "info", Phrases.tr("Language"), languageName())
                        .action(Phrases.tr("Click to choose")).note(Phrases.tr("Six languages, applied at once.")),
                player -> open(player, LANGUAGE)));
        cards.add(card("menus", "CHEST", Lore.name(tone, Phrases.tr("Setup"), Phrases.tr("Menus")), false,
                Lore.of(tone).subtitle(Phrases.tr("Step 2"))
                        .section(Phrases.tr("Information")).text(Phrases.tr("Chest windows or full screens."))
                        .section("✦", Phrases.tr("Status")).stat("eye", "info", Phrases.tr("Style"), styleName())
                        .action(Phrases.tr("Click to choose")).note(Phrases.tr("Three ways, applied at once.")),
                player -> open(player, MENUS)));
        Palette palette = palette();
        cards.add(card("look", themeIcon(palette), Lore.name(tone, Phrases.tr("Setup"), Phrases.tr("Look")), false,
                Lore.of(tone).subtitle(Phrases.tr("Step 3"))
                        .section(Phrases.tr("Information")).text(Phrases.tr("Colours, letters and the address."))
                        .section("✦", Phrases.tr("Status"))
                        .stat("star", "info", Phrases.tr("Theme"), themeName(palette))
                        .stat("compass", "info", Phrases.tr("Address"), Text.escape(palette.serverIp()))
                        .action(Phrases.tr("Click to open")).note(Phrases.tr("Every plugin repaints at once.")),
                player -> open(player, LOOK)));
        SetupNetwork.Target network = SetupNetwork.current();
        cards.add(card("network", "ENDER_EYE", Lore.name(tone, Phrases.tr("Setup"), Phrases.tr("Database")), false,
                Lore.of(tone).subtitle(Phrases.tr("Step 4"))
                        .section(Phrases.tr("Information")).text(Phrases.tr("Where every plugin keeps its data."))
                        .section("✦", Phrases.tr("Status"))
                        .stat("key", "info", Phrases.tr("Database"), network == null
                                ? Phrases.tr("This server only") : SetupNetwork.engineName(network.engine()))
                        .action(Phrases.tr("Click to open")).note(Phrases.tr("Tested before anything is saved.")),
                player -> open(player, NETWORK)));
        return cards;
    }

    private static YamlConfiguration hub() {
        YamlConfiguration yaml = frame(27, "11,12,14,15", "NETHER_STAR");
        yaml.set("title", "{primary}&l" + Phrases.tr("SETUP"));
        yaml.set("items.header.name", Lore.name(Lore.Tone.PRIMARY, Phrases.tr("Server setup")));
        yaml.set("items.header.lore", Lore.of(Lore.Tone.PRIMARY).subtitle(Phrases.tr("First steps"))
                .section(Phrases.tr("Information"))
                .text(Phrases.tr("Four choices for every Exylia"))
                .text(Phrases.tr("plugin. Each one applies at once."))
                .lines());
        yaml.set("items.finish.slot", 22);
        yaml.set("items.finish.material", "LIME_DYE");
        yaml.set("items.finish.name", Lore.name(Lore.Tone.SUCCESS, Phrases.tr("Setup"), Phrases.tr("Finish")));
        yaml.set("items.finish.lore", Lore.of(Lore.Tone.SUCCESS).subtitle(Phrases.tr("Last step"))
                .section(Phrases.tr("Information"))
                .text(Phrases.tr("Admins stop being invited."))
                .text(Phrases.tr("Reopen it with {highlight}/exylialib setup{letters}."))
                .action(Phrases.tr("Click to finish"))
                .note(Phrases.tr("Says if a restart is still needed."))
                .lines());
        yaml.set("items.finish.actions", List.of("exylialib:setup_finish"));
        return yaml;
    }

    // ------------------------------------------------------------------ language

    private static List<UiEntry> languageCards() {
        String current = LibrarySettings.get().language();
        List<UiEntry> cards = new ArrayList<>();
        for (Language language : LANGUAGES) {
            boolean chosen = language.code().equalsIgnoreCase(current);
            Lore.Tone tone = chosen ? Lore.Tone.SUCCESS : Lore.Tone.INFO;
            Lore lore = Lore.of(tone)
                    .subtitle(chosen ? Phrases.tr("Server language · in use") : Phrases.tr("Server language"))
                    .section(Phrases.tr("Information"))
                    .text(Phrases.tr("Every plugin on {highlight}default"))
                    .text(Phrases.tr("speaks it, and so does this setup."));
            if (chosen) {
                lore.action(Phrases.tr("In use")).note(Phrases.tr("Pick another one to switch."));
            } else {
                lore.action(Phrases.tr("Click to choose")).note(Phrases.tr("Running plugins switch on restart."));
            }
            cards.add(card(language.code(), language.icon(), Lore.name(tone, Phrases.tr("Language"), language.name()),
                    chosen, lore, player -> {
                        if (!chosen) chooseLanguage(player, language);
                    }));
        }
        return cards;
    }

    private static void chooseLanguage(Player player, Language language) {
        ConfigFile<LibrarySettings> file = LibrarySettings.file();
        ExyliaLib library = SetupWizard.library();
        if (file == null || library == null) return;
        file.set("language", language.code());
        // Phrases, the library's screens and every cached line, in the new language.
        library.reloadPalette();
        done(player, Phrases.tr("{letters}Language set to {highlight}{0}{letters}.", language.name()));
        open(player, LANGUAGE);
    }

    private static String languageName() {
        String code = LibrarySettings.get().language();
        return LANGUAGES.stream().filter(language -> language.code().equalsIgnoreCase(code))
                .map(Language::name).findFirst().orElse(code);
    }

    // ------------------------------------------------------------------ menus

    private static final String CLASSIC = "CLASSIC";
    private static final String SCREEN = "SCREEN";
    private static final String CHOICE = "CHOICE";

    private static List<UiEntry> menuCards() {
        String current = styleKey();
        return List.of(
                styleCard(CLASSIC, "CHEST", Phrases.tr("Chest windows"), current,
                        Phrases.tr("Classic inventory menus."), Phrases.tr("Every client draws them.")),
                styleCard(SCREEN, "ITEM_FRAME", Phrases.tr("Screens"), current,
                        Phrases.tr("Full-screen menus with a"), Phrases.tr("cursor, drawn on 1.21.4+.")),
                styleCard(CHOICE, "PLAYER_HEAD", Phrases.tr("Players choose"), current,
                        Phrases.tr("Each player picks in {highlight}/settings{letters}."),
                        Phrases.tr("Chest windows until they do.")));
    }

    private static UiEntry styleCard(String key, String icon, String name, String current, String first, String second) {
        boolean chosen = key.equals(current);
        Lore.Tone tone = chosen ? Lore.Tone.SUCCESS : Lore.Tone.INFO;
        Lore lore = Lore.of(tone)
                .subtitle(chosen ? Phrases.tr("Menu style · in use") : Phrases.tr("Menu style"))
                .section(Phrases.tr("Information")).text(first).text(second);
        if (chosen) {
            lore.action(Phrases.tr("In use")).note(Phrases.tr("Pick another one to switch."));
        } else {
            lore.action(Phrases.tr("Click to choose")).note(key.equals(CHOICE)
                    ? Phrases.tr("Turns /settings on after a restart.")
                    : Phrases.tr("Menus open this way from now on."));
        }
        return card(key, icon, Lore.name(tone, Phrases.tr("Menus"), name), chosen, lore, player -> {
            if (!chosen) chooseStyle(player, key, name);
        });
    }

    private static void chooseStyle(Player player, String key, String name) {
        ExyliaLib library = SetupWizard.library();
        if (library == null) return;
        ConfigFile<ScreenSettings> screens = library.screensFile();
        if (key.equals(CHOICE)) {
            screens.set("player-choice", true);
            // Players choose in /settings, which the library only registers when told to, at startup.
            ConfigFile<LibrarySettings> file = LibrarySettings.file();
            if (file != null && !LibrarySettings.get().settings().command().enabled()) {
                file.set("settings.command.enabled", true);
                LibrarySettings.reload();
                SetupNetwork.needRestart();
            }
        } else {
            screens.set("player-choice", false);
            screens.set("default-style", key);
        }
        done(player, Phrases.tr("{letters}Menus set to {highlight}{0}{letters}.", name));
        open(player, MENUS);
    }

    private static String styleKey() {
        ExyliaLib library = SetupWizard.library();
        if (library == null) return CLASSIC;
        ScreenSettings settings = library.screensFile().get();
        if (settings.playerChoice()) return CHOICE;
        return SCREEN.equalsIgnoreCase(settings.defaultStyle()) ? SCREEN : CLASSIC;
    }

    private static String styleName() {
        return switch (styleKey()) {
            case SCREEN -> Phrases.tr("Screens");
            case CHOICE -> Phrases.tr("Players choose");
            default -> Phrases.tr("Chest windows");
        };
    }

    // ------------------------------------------------------------------ look

    private static List<UiEntry> lookCards() {
        Palette palette = palette();
        List<UiEntry> cards = new ArrayList<>();
        for (Theme theme : THEMES) {
            boolean worn = theme.worn(palette);
            Lore.Tone tone = worn ? Lore.Tone.SUCCESS : Lore.Tone.INFO;
            Lore lore = Lore.of(tone)
                    .subtitle(worn ? Phrases.tr("Colour theme · in use") : Phrases.tr("Colour theme"))
                    .section("✦", Phrases.tr("Preview"))
                    // The theme's own colours, not the palette's: this card shows what a click would wear.
                    .line("  <" + theme.primary() + ">■■ <" + theme.secondary() + ">■■ <" + theme.secondaryLight()
                            + ">■■ <" + theme.letters() + ">■■ <" + theme.lettersBlack() + ">■■")
                    .line("  <gradient:" + theme.primary() + ":" + theme.secondaryLight() + ">&l"
                            + theme.name().toUpperCase(Locale.ROOT) + "</gradient> <" + theme.letters() + ">"
                            + Phrases.tr("Welcome!"));
            if (worn) {
                lore.action(Phrases.tr("In use")).note(Phrases.tr("Pick another one to switch."));
            } else {
                lore.action(Phrases.tr("Click to apply")).note(Phrases.tr("Every plugin repaints at once."));
            }
            cards.add(card(theme.id(), theme.icon(), Lore.name(tone, Phrases.tr("Theme"), theme.name()), worn, lore,
                    player -> {
                        if (!worn) wear(player, theme);
                    }));
        }
        boolean small = LibrarySettings.get().smallText();
        Lore.Tone tone = Lore.Tone.PRIMARY;
        cards.add(card("small_text", "NAME_TAG", Lore.name(tone, Phrases.tr("Text"), Phrases.tr("Small capitals")), false,
                Lore.of(tone).subtitle(Phrases.tr("Lettering"))
                        .section(Phrases.tr("Information"))
                        .text(Phrases.tr("Draws every line in small"))
                        .text(Phrases.tr("capitals: the Exylia look."))
                        .section("✦", Phrases.tr("Status"))
                        .stat(small ? "on" : "off", small ? "success" : "error", Phrases.tr("State"),
                                small ? Phrases.tr("On") : Phrases.tr("Off"))
                        .action(Phrases.tr("Click to toggle")).note(Phrases.tr("Every plugin redraws at once.")),
                SetupMenu::toggleSmallText));
        cards.add(card("address", "OAK_SIGN", Lore.name(tone, Phrases.tr("Server"), Phrases.tr("Address")), false,
                Lore.of(tone).subtitle(Phrases.tr("Server address"))
                        .section(Phrases.tr("Information"))
                        .text(Phrases.tr("The address players join with,"))
                        .text(Phrases.tr("at the foot of every scoreboard."))
                        .section("✦", Phrases.tr("Status"))
                        .stat(null, "info", Phrases.tr("Address"), Text.escape(palette.serverIp()))
                        .action(Phrases.tr("Click to edit")).note(Phrases.tr("Opens with the current address.")),
                SetupMenu::editAddress));
        return cards;
    }

    private static void wear(Player player, Theme theme) {
        ExyliaLib library = SetupWizard.library();
        if (library == null) return;
        library.paletteFile().update(theme::on);
        library.reloadPalette();
        done(player, Phrases.tr("{letters}Theme set to {highlight}{0}{letters}.", theme.name()));
        open(player, LOOK);
    }

    private static void toggleSmallText(Player player) {
        ConfigFile<LibrarySettings> file = LibrarySettings.file();
        ExyliaLib library = SetupWizard.library();
        if (file == null || library == null) return;
        boolean now = !LibrarySettings.get().smallText();
        file.set("small-text", now);
        library.reloadPalette();
        done(player, now ? Phrases.tr("{letters}Small capitals {success}on{letters}.")
                : Phrases.tr("{letters}Small capitals {error}off{letters}."));
        open(player, LOOK);
    }

    private static void editAddress(Player player) {
        ExyliaLib library = SetupWizard.library();
        if (library == null) return;
        player.closeInventory();
        var inputs = Inputs.of(library);
        inputs.answered(player, inputs.text(player, Phrases.tr("{primary}The address players join with"))
                        .defaultValue(palette().serverIp())
                        .maxLength(64)
                        .open(),
                address -> {
                    String clean = address.trim();
                    library.paletteFile().update(p -> new Palette(p.primary(), p.secondary(), p.secondaryLight(),
                            p.letters(), p.lettersBlack(), p.error(), p.errorLight(), p.success(), p.successLight(),
                            p.warning(), p.warningLight(), p.info(), p.infoLight(), p.accent(), p.neutral(),
                            p.highlight(), p.muted(), clean));
                    library.reloadPalette();
                    done(player, Phrases.tr("{letters}Address set to {highlight}{0}{letters}.", Text.escape(clean)));
                    open(player, LOOK);
                },
                () -> open(player, LOOK));
    }

    private static Palette palette() {
        ExyliaLib library = SetupWizard.library();
        return library == null ? new Palette() : library.paletteFile().get();
    }

    private static String themeName(Palette palette) {
        return THEMES.stream().filter(theme -> theme.worn(palette)).map(Theme::name).findFirst()
                .orElse(Phrases.tr("Custom"));
    }

    private static String themeIcon(Palette palette) {
        return THEMES.stream().filter(theme -> theme.worn(palette)).map(Theme::icon).findFirst()
                .orElse("AMETHYST_SHARD");
    }

    // ------------------------------------------------------------------ network

    private static List<UiEntry> networkCards() {
        SetupNetwork.Target network = SetupNetwork.current();
        boolean single = network == null;
        Lore.Tone singleTone = single ? Lore.Tone.SUCCESS : Lore.Tone.INFO;
        Lore singleLore = Lore.of(singleTone)
                .subtitle(single ? Phrases.tr("Database · in use") : Phrases.tr("Database"))
                .section(Phrases.tr("Information"))
                .text(Phrases.tr("Each plugin keeps its data in"))
                .text(Phrases.tr("a file on this server. Right"))
                .text(Phrases.tr("for one server on its own."));
        if (single) {
            singleLore.action(Phrases.tr("In use")).note(Phrases.tr("Nothing to set up."));
        } else {
            singleLore.action(Phrases.tr("Click to choose")).note(Phrases.tr("You will be asked to confirm."));
        }
        Lore.Tone networkTone = single ? Lore.Tone.INFO : Lore.Tone.SUCCESS;
        Lore networkLore = Lore.of(networkTone)
                .subtitle(single ? Phrases.tr("Database") : Phrases.tr("Database · in use"))
                .section(Phrases.tr("Information"))
                .text(Phrases.tr("One MySQL, MariaDB, PostgreSQL"))
                .text(Phrases.tr("or MongoDB every server shares."));
        if (!single) {
            networkLore.section("✦", Phrases.tr("Status"))
                    .stat("iron", "info", Phrases.tr("Engine"), SetupNetwork.engineName(network.engine()))
                    .stat("compass", "info", Phrases.tr("Host"), Text.escape(network.host()))
                    .stat("tag", "info", Phrases.tr("This server"), Text.escape(network.serverId()))
                    .stat("signal_5", "info", Phrases.tr("Messages"),
                            network.redis() ? "Redis" : Phrases.tr("Through the database"));
        }
        networkLore.action(single ? Phrases.tr("Click to set up") : Phrases.tr("Click to change"))
                .note(Phrases.tr("Tested before anything is saved."));
        return List.of(
                card("single", "BARREL", Lore.name(singleTone, Phrases.tr("Database"), Phrases.tr("This server only")),
                        single, singleLore, player -> {
                            if (!single) SetupNetwork.single(player);
                        }),
                card("network", "ENDER_EYE", Lore.name(networkTone, Phrases.tr("Database"), Phrases.tr("Network")),
                        !single, networkLore, SetupNetwork::ask));
    }

    // ------------------------------------------------------------------ parts

    /** Tells the player a choice was saved, with the success sound. */
    static void done(Player player, String line) {
        Text.of("[sound:ENTITY_EXPERIENCE_ORB_PICKUP|0.6|1.2]" + SetupWizard.prefix() + line).send(player);
    }

    private static UiEntry card(String id, String icon, String name, boolean glow, Lore lore, Consumer<Player> click) {
        return UiEntry.of(new Card(id, click))
                .with("icon", icon)
                .withFormatted("name", name)
                .with("glow", glow)
                .withFormatted("lore", String.join(Lines.NEWLINE, lore.lines()))
                .build();
    }

    /** A step's page: its header, its cards and the way back to the hub. */
    private static YamlConfiguration page(int size, String slots, int backSlot, String icon, Lore.Tone tone,
                                          String subject, String subtitle, List<String> about) {
        YamlConfiguration yaml = frame(size, slots, icon);
        yaml.set("title", "{primary}&l" + Phrases.tr("SETUP") + " {letters_black}» {highlight}" + subject);
        yaml.set("items.header.name", Lore.name(tone, subject));
        Lore lore = Lore.of(tone).subtitle(subtitle).section(Phrases.tr("Information"));
        about.forEach(lore::text);
        yaml.set("items.header.lore", lore.lines());
        yaml.set("items.back.slot", backSlot);
        yaml.set("items.back.material", BACK_HEAD);
        yaml.set("items.back.sound", "minecraft:item.bundle.remove_one|1.0|0.5");
        yaml.set("items.back.name", Lore.name(Lore.Tone.ERROR, Phrases.tr("Back"), Phrases.tr("Setup")));
        yaml.set("items.back.lore", List.of("{error}{icon:click} " + Phrases.tr("Click to go back")));
        yaml.set("items.back.actions", List.of("exylialib:setup_hub"));
        return yaml;
    }

    private static YamlConfiguration frame(int size, String slots, String icon) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString("""
                    type: PAGINATION
                    filler:
                      global:
                        material: GRAY_STAINED_GLASS_PANE
                        hide_tooltip: true
                      pagination:
                        material: GRAY_STAINED_GLASS_PANE
                        hide_tooltip: true
                    pagination:
                      item_template:
                        material: '%icon%'
                        name: '%name%'
                        glow: '%glow%'
                        lore:
                          - '%lore%'
                        actions:
                          - 'exylialib:setup_click'
                    items:
                      header:
                        slot: 4
                    """);
        } catch (InvalidConfigurationException impossible) {
            throw new IllegalStateException(impossible);
        }
        yaml.set("size", size);
        yaml.set("pagination.slots", slots);
        yaml.set("items.header.material", icon);
        return yaml;
    }
}
