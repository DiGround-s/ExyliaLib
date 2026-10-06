package net.exylia.lib.discord.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.exylia.lib.discord.WebhookTemplate;
import net.exylia.lib.discord.Webhooks;
import net.exylia.lib.text.Colors;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.format.TextColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a template and its values into the JSON Discord expects.
 *
 * <p>Pure: no network, no scheduler, no server. Everything about what a
 * message <em>says</em> — escaping, mentions, limits, colours — lives here, so
 * it is tested on its own.
 *
 * <h2>Two kinds of text</h2>
 * Template text is the server owner's: its Discord markdown is kept and its
 * Minecraft formatting converted. A value is somebody else's: its formatting is
 * removed, its markdown escaped and its mentions broken, so a player called
 * {@code @everyone} or {@code **x**} reads exactly as typed and pings nobody.
 */
public final class Payloads {

    public static final int CONTENT = 2000;
    public static final int TITLE = 256;
    public static final int FIELD_NAME = 256;
    public static final int FIELD_VALUE = 1024;
    public static final int FIELDS = 25;
    public static final int FOOTER = 2048;
    public static final int AUTHOR = 256;
    public static final int USERNAME = 80;
    /** Discord's cap on the text of every text display in one message, together. */
    public static final int TEXT_TOTAL = 4000;
    public static final int BUTTON_LABEL = 80;
    /** Four rows of five: with the card's own parts, safely under Discord's 40 components. */
    public static final int BUTTONS = 20;
    public static final int MENTIONS = 100;

    /** {@code IS_COMPONENTS_V2}: the message is laid out by its components, with no content or embeds. */
    public static final int FLAG_COMPONENTS = 1 << 15;
    static final int ACTION_ROW = 1;
    static final int BUTTON = 2;
    static final int SECTION = 9;
    static final int TEXT = 10;
    static final int THUMBNAIL = 11;
    static final int GALLERY = 12;
    static final int SEPARATOR = 14;
    static final int CONTAINER = 17;
    private static final int LINK = 5;
    private static final int PER_ROW = 5;
    /**
     * Ids a merge finds its text by. Discord numbers components without one from 1
     * up, and a message holds at most 40, so these never collide.
     */
    static final int MESSAGE_ID = 100;
    static final int DESCRIPTION_ID = 101;

    private static final char BOLD = '';
    private static final char TOKEN_BASE = '';
    private static final String ZWSP = "​";
    /** Discord syntax a strip must not touch: {@code <@&1>} carries a legacy {@code &1}. */
    private static final Pattern DISCORD_TOKEN = Pattern.compile(
            "<(?:@[!&]?\\d+|#\\d+|a?:\\w+:\\d+|t:-?\\d+(?::[tTdDfFR])?|/[\\w -]+:\\d+|id:\\w+)>");
    private static final Pattern PLACEHOLDER = Pattern.compile("%([A-Za-z0-9_.:-]+)%");
    private static final Pattern ROLE = Pattern.compile("<@&(\\d{17,20})>");
    private static final Pattern USER = Pattern.compile("<@!?(\\d{17,20})>");
    private static final Pattern HEX = Pattern.compile("<?#([0-9A-Fa-f]{6})>?");
    private static final Pattern TOKEN_NAME = Pattern.compile("\\{([a-z_A-Z]+)}");
    private static final Pattern CUSTOM_EMOJI = Pattern.compile("<(a?):(\\w{2,32}):(\\d{17,20})>");

    /** Converted template text, by raw text. Nothing in it depends on the palette. */
    private static final Cache<String, String> CONVERTED = Caffeine.newBuilder().maximumSize(1024).build();

    private Payloads() {
    }

    /** How a part is read by Discord, which decides how a value is made safe. */
    enum Kind {
        /** Rendered as markdown: every text display. */
        MARKDOWN,
        /** Shown as typed: username, button labels. Escapes would show. */
        PLAIN,
        /** A link or an image. */
        URL
    }

    /**
     * Builds the webhook payload: the plain message as a text display of its
     * own, where it can ping, and the rest as one container.
     *
     * @param template the message
     * @param values   bare-named values; a {@link Webhooks.Trusted} is inserted as written
     * @param everyone whether a literal {@code @everyone}/{@code @here} in the message may ping
     * @param warn     receives a one-line warning when something had to be trimmed
     * @return the payload, or {@code null} when there is nothing to send
     */
    public static @Nullable JsonObject build(@NotNull WebhookTemplate template, @NotNull Map<String, ?> values,
                                             boolean everyone, @NotNull Consumer<String> warn) {
        JsonObject payload = new JsonObject();
        String username = truncate(fill(template.username(), values, Kind.PLAIN).trim(), USERNAME);
        // Discord refuses the whole message over a name it reserves.
        String lower = username.toLowerCase(Locale.ROOT);
        if (!username.isEmpty() && !lower.contains("discord") && !lower.contains("clyde")) {
            payload.addProperty("username", username);
        }
        String avatar = link(template.avatarUrl(), values);
        if (avatar != null) {
            payload.addProperty("avatar_url", avatar);
        }
        JsonArray components = new JsonArray();
        String content = truncate(fill(template.message(), values, Kind.MARKDOWN), CONTENT);
        if (!content.isBlank()) {
            components.add(text(content, MESSAGE_ID));
        }
        JsonObject card = card(template, values, TEXT_TOTAL - (content.isBlank() ? 0 : content.length()));
        if (card != null) {
            components.add(card);
        }
        if (components.isEmpty()) {
            return null;
        }
        payload.addProperty("flags", FLAG_COMPONENTS);
        payload.add("components", components);
        payload.add("allowed_mentions", allowedMentions(template.message(), everyone, warn));
        return payload;
    }

    /**
     * Who the message may ping: only what the template itself wrote.
     *
     * <p>Read from the raw message, before any value is substituted, so no
     * value can add a ping. {@code parse} is always empty unless a literal
     * {@code @everyone} or {@code @here} is allowed.
     */
    static JsonObject allowedMentions(String raw, boolean everyone, Consumer<String> warn) {
        JsonObject allowed = new JsonObject();
        JsonArray parse = new JsonArray();
        if (everyone && (raw.contains("@everyone") || raw.contains("@here"))) {
            parse.add("everyone");
        }
        allowed.add("parse", parse);
        allowed.add("roles", ids(ROLE, raw, "role", warn));
        allowed.add("users", ids(USER, raw, "user", warn));
        return allowed;
    }

    private static JsonArray ids(Pattern pattern, String raw, String what, Consumer<String> warn) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(raw);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        JsonArray ids = new JsonArray();
        for (String id : found) {
            if (ids.size() == MENTIONS) {
                warn.accept("a webhook message mentions more than " + MENTIONS + " " + what
                        + "s; only the first " + MENTIONS + " can ping");
                break;
            }
            ids.add(id);
        }
        return ids;
    }

    /**
     * The card: a heading, the body, the fields, the image, the buttons and a
     * small footer line, inside a container with the accent colour.
     *
     * @param budget the text the card may hold; over it, the body gives way first
     */
    private static @Nullable JsonObject card(WebhookTemplate template, Map<String, ?> values, int budget) {
        StringBuilder heading = new StringBuilder();
        String author = truncate(fill(template.author().name(), values, Kind.MARKDOWN), AUTHOR);
        if (!author.isBlank()) {
            String url = link(template.author().url(), values);
            heading.append("-# ").append(url == null ? author : "[" + author + "](" + url + ")");
        }
        String title = truncate(fill(template.title(), values, Kind.MARKDOWN), TITLE);
        if (!title.isBlank()) {
            String url = link(template.url(), values);
            heading.append(heading.isEmpty() ? "" : "\n").append("## ")
                    .append(url == null ? title : "[" + title + "](" + url + ")");
        }
        String footerText = truncate(fill(template.footer(), values, Kind.MARKDOWN), FOOTER).replace('\n', ' ').strip();
        String[] texts = {fill(template.description(), values, Kind.MARKDOWN), fields(template.fields(), values),
                footer(footerText, template.timestamp()), heading.toString()};
        // Over the budget, the body gives way first, then the fields, the footer and the heading.
        int excess = -budget;
        for (String text : texts) {
            excess += text.length();
        }
        for (int i = 0; i < texts.length && excess > 0; i++) {
            int cut = Math.min(excess, texts[i].length());
            texts[i] = truncate(texts[i], texts[i].length() - cut);
            excess -= cut;
        }
        String description = texts[0];
        String fields = texts[1];
        String footer = texts[2];
        String headingText = texts[3];

        String thumbnail = link(template.thumbnail(), values);
        if (thumbnail == null) {
            thumbnail = link(template.author().icon(), values);
        }
        String image = link(template.image(), values);
        List<JsonObject> rows = buttons(template.buttons(), values);
        // A colour and a time are not a card on their own.
        if (headingText.isBlank() && description.isBlank() && fields.isBlank() && footerText.isEmpty()
                && image == null && rows.isEmpty()) {
            return null;
        }

        JsonArray parts = new JsonArray();
        JsonArray top = new JsonArray();
        if (!headingText.isBlank()) {
            top.add(text(headingText, null));
        }
        if (!description.isBlank()) {
            top.add(text(description, DESCRIPTION_ID));
        }
        if (thumbnail != null && !top.isEmpty()) {
            // A thumbnail only fits beside text: a section holds both.
            JsonObject section = component(SECTION);
            section.add("components", top);
            JsonObject accessory = component(THUMBNAIL);
            accessory.add("media", url(thumbnail));
            section.add("accessory", accessory);
            parts.add(section);
        } else {
            parts.addAll(top);
        }
        if (!fields.isBlank()) {
            parts.add(text(fields, null));
        }
        if (image != null) {
            JsonObject item = new JsonObject();
            item.add("media", url(image));
            JsonArray items = new JsonArray();
            items.add(item);
            JsonObject gallery = component(GALLERY);
            gallery.add("items", items);
            parts.add(gallery);
        }
        rows.forEach(parts::add);
        if (!footer.isBlank()) {
            JsonObject separator = component(SEPARATOR);
            separator.addProperty("divider", true);
            separator.addProperty("spacing", 1);
            parts.add(separator);
            parts.add(text(footer, null));
        }
        JsonObject container = component(CONTAINER);
        Integer color = color(template.color());
        if (color != null) {
            container.addProperty("accent_color", color);
        }
        container.add("components", parts);
        return container;
    }

    /**
     * Fields as text: a field of its own is its name over its value; inline
     * fields, which a card cannot set side by side, sit one per line as
     * {@code **name:** value}.
     */
    private static String fields(List<String> lines, Map<String, ?> values) {
        StringBuilder out = new StringBuilder();
        boolean lastInline = false;
        int count = 0;
        for (String line : lines) {
            if (count == FIELDS) {
                break;
            }
            // Split the template, never the filled text: a '|' in a value is not a column.
            String[] parts = line.split("\\|", 3);
            String name = truncate(fill(parts[0], values, Kind.MARKDOWN), FIELD_NAME).strip();
            String value = parts.length > 1 ? truncate(fill(parts[1], values, Kind.MARKDOWN), FIELD_VALUE).strip() : "";
            if (name.isBlank() && value.isBlank()) {
                continue;
            }
            boolean inline = parts.length > 2
                    && (parts[2].trim().equalsIgnoreCase("inline") || parts[2].trim().equalsIgnoreCase("true"));
            if (!out.isEmpty()) {
                out.append(inline && lastInline ? "\n" : "\n\n");
            }
            String label = name.endsWith(":") ? name.substring(0, name.length() - 1) : name;
            if (name.isBlank()) {
                out.append(value);
            } else if (value.isBlank()) {
                out.append("**").append(label).append("**");
            } else if (inline) {
                out.append("**").append(label).append(":** ").append(value);
            } else {
                out.append("**").append(label).append("**\n").append(value);
            }
            lastInline = inline;
            count++;
        }
        return out.toString();
    }

    /** The small closing line: the footer and, when asked for, when it was sent. */
    private static String footer(String footer, boolean timestamp) {
        String time = timestamp ? "<t:" + Instant.now().getEpochSecond() + ":f>" : "";
        if (footer.isEmpty() && time.isEmpty()) {
            return "";
        }
        return "-# " + footer + (footer.isEmpty() || time.isEmpty() ? "" : " · ") + time;
    }

    /** Link buttons, five to a row. An entry without a usable label or URL is left out. */
    private static List<JsonObject> buttons(List<String> lines, Map<String, ?> values) {
        List<JsonObject> buttons = new ArrayList<>();
        for (String line : lines) {
            if (buttons.size() == BUTTONS) {
                break;
            }
            String[] parts = line.split("\\|", 3);
            String label = parts.length > 0 ? truncate(fill(parts[0], values, Kind.PLAIN).strip(), BUTTON_LABEL) : "";
            String url = parts.length > 1 ? link(parts[1], values) : null;
            JsonObject emoji = parts.length > 2 ? emoji(parts[2].strip()) : null;
            if (url == null || label.isEmpty() && emoji == null) {
                continue;
            }
            JsonObject button = component(BUTTON);
            button.addProperty("style", LINK);
            if (!label.isEmpty()) {
                button.addProperty("label", label);
            }
            button.addProperty("url", url);
            if (emoji != null) {
                button.add("emoji", emoji);
            }
            buttons.add(button);
        }
        List<JsonObject> rows = new ArrayList<>();
        for (int from = 0; from < buttons.size(); from += PER_ROW) {
            JsonArray row = new JsonArray();
            buttons.subList(from, Math.min(from + PER_ROW, buttons.size())).forEach(row::add);
            JsonObject actionRow = component(ACTION_ROW);
            actionRow.add("components", row);
            rows.add(actionRow);
        }
        return rows;
    }

    /** A unicode emoji as written, or a server one as {@code <:name:id>}; {@code null} for none. */
    private static @Nullable JsonObject emoji(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        JsonObject emoji = new JsonObject();
        Matcher custom = CUSTOM_EMOJI.matcher(raw);
        if (custom.matches()) {
            emoji.addProperty("name", custom.group(2));
            emoji.addProperty("id", custom.group(3));
            emoji.addProperty("animated", !custom.group(1).isEmpty());
        } else if (raw.length() <= 16 && raw.codePoints().noneMatch(Character::isLetterOrDigit)) {
            emoji.addProperty("name", raw);
        } else {
            return null;
        }
        return emoji;
    }

    private static JsonObject text(String content, @Nullable Integer id) {
        JsonObject text = component(TEXT);
        if (id != null) {
            text.addProperty("id", id);
        }
        text.addProperty("content", content);
        return text;
    }

    private static JsonObject component(int type) {
        JsonObject component = new JsonObject();
        component.addProperty("type", type);
        return component;
    }

    private static JsonObject url(String url) {
        JsonObject object = new JsonObject();
        object.addProperty("url", url);
        return object;
    }

    /**
     * Merges a later message from the same template into an earlier one.
     *
     * <p>The bodies are joined a line each; without a card, the plain messages
     * are. Nothing is merged past Discord's text limit: the later message is
     * then sent on its own.
     *
     * @return whether {@code later} now lives inside {@code into}
     */
    public static boolean merge(@NotNull JsonObject into, @NotNull JsonObject later) {
        boolean cards = card(into) != null;
        if (cards != (card(later) != null)) {
            return false;
        }
        int id = cards ? DESCRIPTION_ID : MESSAGE_ID;
        JsonObject first = find(into.getAsJsonArray("components"), id);
        JsonObject next = find(later.getAsJsonArray("components"), id);
        if (first == null || next == null) {
            return false;
        }
        String extra = "\n" + next.get("content").getAsString();
        if (textLength(into.getAsJsonArray("components")) + extra.length() > TEXT_TOTAL) {
            return false;
        }
        first.addProperty("content", first.get("content").getAsString() + extra);
        return true;
    }

    private static @Nullable JsonObject card(JsonObject payload) {
        for (JsonElement element : payload.getAsJsonArray("components")) {
            if (element.getAsJsonObject().get("type").getAsInt() == CONTAINER) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    /** The component with an id, searched through every container and section. */
    static @Nullable JsonObject find(@Nullable JsonArray components, int id) {
        if (components == null) {
            return null;
        }
        for (JsonElement element : components) {
            JsonObject component = element.getAsJsonObject();
            if (component.has("id") && component.get("id").getAsInt() == id) {
                return component;
            }
            JsonObject inside = find(component.getAsJsonArray("components"), id);
            if (inside != null) {
                return inside;
            }
        }
        return null;
    }

    /** The text of every text display, together: what Discord holds to {@link #TEXT_TOTAL}. */
    static int textLength(@Nullable JsonArray components) {
        if (components == null) {
            return 0;
        }
        int length = 0;
        for (JsonElement element : components) {
            JsonObject component = element.getAsJsonObject();
            if (component.get("type").getAsInt() == TEXT) {
                length += component.get("content").getAsString().length();
            }
            length += textLength(component.getAsJsonArray("components"));
        }
        return length;
    }

    /**
     * Resolves a colour: a palette token, read from the live palette so a
     * {@code colors.yml} change applies, or a hex value.
     *
     * @return the RGB value, or {@code null} for none
     */
    public static @Nullable Integer color(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        Matcher token = TOKEN_NAME.matcher(text);
        if (token.matches()) {
            TextColor color = Colors.get(token.group(1));
            return color == null ? null : color.value();
        }
        Matcher hex = HEX.matcher(text);
        return hex.matches() ? Integer.parseInt(hex.group(1), 16) : null;
    }

    /** Converts template text and fills its placeholders, each value made safe for where it lands. */
    static String fill(String raw, Map<String, ?> values, Kind kind) {
        if (raw.isEmpty()) {
            return "";
        }
        String template = convert(raw);
        if (template.indexOf('%') < 0 || values.isEmpty()) {
            return template;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 32);
        while (matcher.find()) {
            Object value = values.get(matcher.group(1));
            String replacement = value == null ? matcher.group() : value(value, kind);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String value(Object value, Kind kind) {
        if (kind == Kind.URL) {
            String text = value instanceof Webhooks.Trusted trusted ? trusted.value() : String.valueOf(value);
            return text.trim();
        }
        if (value instanceof Webhooks.Trusted trusted) {
            return convert(trusted.value());
        }
        return kind == Kind.MARKDOWN ? neutralise(String.valueOf(value)) : plain(String.valueOf(value));
    }

    /**
     * Makes somebody else's text read exactly as typed in a markdown part.
     *
     * <p>Formatting is stripped for good, line breaks flattened, markdown
     * escaped and every mention broken with a zero-width space — so it can
     * neither style the message nor ping anyone, even past {@code allowed_mentions}.
     */
    public static @NotNull String neutralise(@Nullable String untrusted) {
        String text = plain(untrusted);
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            switch (character) {
                case '\\', '*', '_', '~', '`', '|', '>', '#', '-', '[', ']' -> out.append('\\').append(character);
                case '@', '<' -> out.append(character).append(ZWSP);
                default -> out.append(character);
            }
        }
        return out.toString();
    }

    /** Somebody else's text with no formatting and on one line, for a part Discord shows as typed. */
    private static String plain(@Nullable String untrusted) {
        if (untrusted == null || untrusted.isEmpty()) {
            return "";
        }
        String text = hasFormatting(untrusted) ? Text.strip(untrusted) : untrusted;
        return text.replace('\r', ' ').replace('\n', ' ');
    }

    private static boolean hasFormatting(String text) {
        return text.indexOf('<') >= 0 || text.indexOf('&') >= 0 || text.indexOf('{') >= 0
                || text.indexOf('§') >= 0 || text.indexOf('\\') >= 0;
    }

    /**
     * Converts the owner's text: {@code &l} becomes bold until the next colour
     * code, reset or line end; every other colour code, palette token and
     * MiniMessage tag is removed. Discord syntax and markdown are kept.
     */
    static String convert(String raw) {
        if (!hasFormatting(raw)) {
            return raw;
        }
        return CONVERTED.get(raw, Payloads::convertUncached);
    }

    private static String convertUncached(String raw) {
        // Discord's own <...> syntax goes aside first: the strip would read
        // <@&1...> as a tag holding a legacy colour code.
        List<String> tokens = new ArrayList<>();
        Matcher matcher = DISCORD_TOKEN.matcher(raw);
        StringBuilder protectedText = new StringBuilder(raw.length());
        while (matcher.find() && tokens.size() < 256) {
            matcher.appendReplacement(protectedText, String.valueOf((char) (TOKEN_BASE + tokens.size())));
            tokens.add(matcher.group());
        }
        matcher.appendTail(protectedText);
        String stripped = Text.strip(bold(protectedText.toString()));
        // An empty bold run, or two runs touching, is one marker pair too many.
        String text = stripped.replace(String.valueOf(BOLD) + BOLD, "").replace(String.valueOf(BOLD), "**");
        StringBuilder out = new StringBuilder(text.length() + 32);
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            int index = character - TOKEN_BASE;
            out.append(index >= 0 && index < tokens.size() ? tokens.get(index) : String.valueOf(character));
        }
        return out.toString();
    }

    private static String bold(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 8);
        boolean open = false;
        for (int i = 0; i < raw.length(); i++) {
            char character = raw.charAt(i);
            boolean code = (character == '&' || character == '§') && i + 1 < raw.length();
            char next = code ? Character.toLowerCase(raw.charAt(i + 1)) : 0;
            if (code && next == 'l') {
                if (!open) {
                    out.append(BOLD);
                    open = true;
                }
                i++;
                continue;
            }
            if (open && (character == '\n'
                    || code && (next == 'r' || next == '#' || (next >= '0' && next <= '9') || (next >= 'a' && next <= 'f')))) {
                close(out);
                open = false;
            }
            out.append(character);
        }
        if (open) {
            close(out);
        }
        return out.toString();
    }

    /** Closes bold before trailing spaces: Discord does not end a run on {@code **CAPTURE **}. */
    private static void close(StringBuilder out) {
        int at = out.length();
        while (at > 0 && out.charAt(at - 1) == ' ') {
            at--;
        }
        out.insert(at, BOLD);
    }

    /** A filled link, or {@code null} when it is empty or not an http(s) URL Discord would accept. */
    private static @Nullable String link(String raw, Map<String, ?> values) {
        if (raw.isBlank()) {
            return null;
        }
        String url = fill(raw.trim(), values, Kind.URL);
        if (url.length() > 2048 || url.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            return scheme != null && (scheme.equals("https") || scheme.equals("http")) && uri.getHost() != null
                    ? url : null;
        } catch (Exception malformed) {
            return null;
        }
    }

    /** Cuts to a limit with an ellipsis, never through the middle of a surrogate pair. */
    static String truncate(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        if (limit <= 0) {
            return "";
        }
        int end = limit - 1;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }
}
