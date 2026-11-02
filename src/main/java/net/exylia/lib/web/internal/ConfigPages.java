package net.exylia.lib.web.internal;

import net.exylia.lib.config.Menu;
import net.exylia.lib.config.Schema;
import net.exylia.lib.text.Lines;
import net.exylia.lib.text.Phrases;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How a settings file is split into the cards of its hub. Pure: from the {@link Schema} and the
 * record's {@link Menu} annotations only, so the grouping is tested without a server.
 *
 * <p>A key with {@link Menu.Page} goes on that page wherever it is written. The rest follow the
 * file: keys at its root on {@link #GENERAL}, each section of the root on its own card, each map
 * or list of entries at the root on a card that opens the list. A section deeper down is a
 * button on the page of the section holding it.
 *
 * <p>Tokens name a page in the menus' context: {@code @general}, {@code @<page id>},
 * {@code s:<section key>}, {@code e:<map key>}.
 */
final class ConfigPages {

    static final String GENERAL = "@general";

    /**
     * One page.
     *
     * @param token   what names it
     * @param card    whether the file's hub shows it (a nested section is reached from its parent)
     * @param label   its name, in capitals
     * @param icon    its card's item
     * @param about   its Information block, or {@link Lines#OMIT}
     * @param fields  its keys, in order
     * @param entries the map or list a card of entries opens; {@code null} for any other page
     */
    record Page(String token, boolean card, String label, String icon, String about,
                List<ConfigFields.Field> fields, @Nullable ConfigFields.Field entries) {
    }

    private ConfigPages() {
    }

    /** Every page of a file, cards first in the order their keys are declared. */
    static List<Page> pages(Schema root) {
        Map<String, Menu.Card> cards = new HashMap<>();
        collectCards(root, cards);
        Map<String, Building> pages = new LinkedHashMap<>();
        walk(root, "", pages, cards);
        // A section all of whose keys went to pages has nothing left to open.
        boolean pruned = true;
        while (pruned) {
            pruned = false;
            for (Building page : pages.values()) {
                pruned |= page.fields.removeIf(field -> field.kind() == ConfigFields.Kind.SECTION
                        && empty(pages.get("s:" + field.key())));
            }
        }
        List<Page> out = new ArrayList<>();
        for (Building page : pages.values()) {
            if (page.entries == null && page.fields.isEmpty()) continue;
            out.add(new Page(page.token, page.card, page.label, page.icon, page.about, List.copyOf(page.fields), page.entries));
        }
        return out;
    }

    /** The cards of a file's hub. */
    static List<Page> cards(Schema root) {
        return pages(root).stream().filter(Page::card).toList();
    }

    /** One page by its token, or {@code null}. */
    static @Nullable Page page(Schema root, String token) {
        for (Page page : pages(root)) {
            if (page.token().equals(token)) return page;
        }
        return null;
    }

    /**
     * The page a key is a button on, or {@code null} when it is a card of the hub (a section or
     * a map at the root). What BACK from a section's page or a list of entries returns to.
     */
    static @Nullable Page holder(Schema root, String key) {
        for (Page page : pages(root)) {
            for (ConfigFields.Field field : page.fields()) {
                if (field.key().equals(key)) return page;
            }
        }
        return null;
    }

    private static boolean empty(@Nullable Building page) {
        return page == null || page.fields.isEmpty();
    }

    private static final class Building {
        final String token;
        final boolean card;
        final String label;
        final String icon;
        final String about;
        final List<ConfigFields.Field> fields = new ArrayList<>();
        ConfigFields.Field entries;

        Building(String token, boolean card, String label, String icon, String about) {
            this.token = token;
            this.card = card;
            this.label = label;
            this.icon = icon;
            this.about = about;
        }
    }

    private static void walk(Schema root, String section, Map<String, Building> pages, Map<String, Menu.Card> cards) {
        for (ConfigFields.Field field : ConfigFields.fields(root, section)) {
            Menu.Page paged = field.hint(Menu.Page.class);
            boolean opens = field.kind() == ConfigFields.Kind.SECTION;
            if (paged != null) {
                annotated(pages, cards, paged.value()).fields.add(field);
            } else if (section.isEmpty() && opens) {
                pages.computeIfAbsent("s:" + field.key(), token -> sectionPage(token, true, field));
            } else if (section.isEmpty() && field.kind() == ConfigFields.Kind.ENTRIES) {
                Building page = pages.computeIfAbsent("e:" + field.key(),
                        token -> new Building(token, true, ConfigFields.label(field), ConfigFields.icon(field, null),
                                ConfigFields.about(field)));
                page.entries = field;
            } else if (section.isEmpty()) {
                pages.computeIfAbsent(GENERAL, token -> new Building(token, true, Phrases.tr("General").toUpperCase(Locale.ROOT),
                        "COMPARATOR", ConfigFields.block(List.of(Phrases.tr("The main options of this file."))))).fields.add(field);
            } else {
                pages.get("s:" + section).fields.add(field);
            }
            if (opens) {
                pages.computeIfAbsent("s:" + field.key(), token -> sectionPage(token, false, field));
                walk(root, field.key(), pages, cards);
            }
        }
    }

    private static Building sectionPage(String token, boolean card, ConfigFields.Field field) {
        String about = ConfigFields.about(field);
        if (Lines.OMIT.equals(about) && field.schema().nested() != null) {
            about = ConfigFields.summary(field.schema().nested().comments());
        }
        return new Building(token, card, ConfigFields.label(field), ConfigFields.icon(field, null), about);
    }

    private static Building annotated(Map<String, Building> pages, Map<String, Menu.Card> cards, String id) {
        return pages.computeIfAbsent("@" + id, token -> {
            Menu.Card card = cards.get(id);
            String label = card != null && !card.label().isBlank() ? card.label() : ConfigFields.words(id);
            String icon = card != null && !card.icon().isBlank() ? card.icon() : named(id);
            String about = card == null ? Lines.OMIT : ConfigFields.block(List.of(card.about()));
            return new Building(token, true, label.toUpperCase(Locale.ROOT), icon, about);
        });
    }

    private static String named(String id) {
        String named = ConfigFields.named(id);
        return named == null ? "BOOK" : named;
    }

    private static void collectCards(Schema schema, Map<String, Menu.Card> cards) {
        Menu.Pages declared = schema.type().getAnnotation(Menu.Pages.class);
        if (declared != null) {
            for (Menu.Card card : declared.value()) cards.putIfAbsent(card.id(), card);
        }
        for (Schema.Field field : schema.fields()) {
            if (field.nested() != null) collectCards(field.nested(), cards);
        }
    }
}
