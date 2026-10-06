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
    public static final int DESCRIPTION = 4096;
    public static final int FIELD_NAME = 256;
    public static final int FIELD_VALUE = 1024;
    public static final int FIELDS = 25;
    public static final int FOOTER = 2048;
    public static final int AUTHOR = 256;
    public static final int USERNAME = 80;
    public static final int EMBED_TOTAL = 6000;
    public static final int MENTIONS = 100;

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

    /** Converted template text, by raw text. Nothing in it depends on the palette. */
    private static final Cache<String, String> CONVERTED = Caffeine.newBuilder().maximumSize(1024).build();

    private Payloads() {
    }

    /** How a part is read by Discord, which decides how a value is made safe. */
    enum Kind {
        /** Rendered as markdown: content, title, description, fields. */
        MARKDOWN,
        /** Shown as typed: footer, author, username. Escapes would show. */
        PLAIN,
        /** A link or an image. */
        URL
    }

    /**
     * Builds the webhook payload.
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
        String content = truncate(fill(template.message(), values, Kind.MARKDOWN), CONTENT);
        if (!content.isBlank()) {
            payload.addProperty("content", content);
        }
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
        JsonObject embed = embed(template, values);
        if (embed != null) {
            JsonArray embeds = new JsonArray();
            embeds.add(embed);
            payload.add("embeds", embeds);
        }
        if (!payload.has("content") && embed == null) {
            return null;
        }
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

    private static @Nullable JsonObject embed(WebhookTemplate template, Map<String, ?> values) {
        JsonObject embed = new JsonObject();
        String title = truncate(fill(template.title(), values, Kind.MARKDOWN), TITLE);
        String description = truncate(fill(template.description(), values, Kind.MARKDOWN), DESCRIPTION);
        String footer = truncate(fill(template.footer(), values, Kind.PLAIN), FOOTER);
        String author = truncate(fill(template.author().name(), values, Kind.PLAIN), AUTHOR);
        JsonArray fields = new JsonArray();
        for (String line : template.fields()) {
            if (fields.size() == FIELDS) {
                break;
            }
            // Split the template, never the filled text: a '|' in a value is not a column.
            String[] parts = line.split("\\|", 3);
            String name = truncate(fill(parts[0], values, Kind.MARKDOWN), FIELD_NAME);
            String value = parts.length > 1 ? truncate(fill(parts[1], values, Kind.MARKDOWN), FIELD_VALUE) : "";
            if (name.isBlank() && value.isBlank()) {
                continue;
            }
            JsonObject field = new JsonObject();
            // Discord refuses an empty name or value; a zero-width space is how one is drawn blank.
            field.addProperty("name", name.isBlank() ? ZWSP : name);
            field.addProperty("value", value.isBlank() ? ZWSP : value);
            field.addProperty("inline", parts.length > 2
                    && (parts[2].trim().equalsIgnoreCase("inline") || parts[2].trim().equalsIgnoreCase("true")));
            fields.add(field);
        }
        String thumbnail = link(template.thumbnail(), values);
        String image = link(template.image(), values);
        boolean drawn = !title.isBlank() || !description.isBlank() || !footer.isBlank()
                || !author.isBlank() || !fields.isEmpty() || thumbnail != null || image != null;
        if (!drawn) {
            return null;
        }
        // Over the total, the description gives way first and then the last fields.
        int excess = title.length() + description.length() + footer.length() + author.length()
                + fieldsLength(fields) - EMBED_TOTAL;
        if (excess > 0) {
            description = truncate(description, Math.max(1, description.length() - excess));
            excess = title.length() + description.length() + footer.length() + author.length()
                    + fieldsLength(fields) - EMBED_TOTAL;
            while (excess > 0 && !fields.isEmpty()) {
                JsonObject last = fields.remove(fields.size() - 1).getAsJsonObject();
                excess -= last.get("name").getAsString().length() + last.get("value").getAsString().length();
            }
        }
        if (!title.isBlank()) {
            embed.addProperty("title", title);
            String url = link(template.url(), values);
            if (url != null) {
                embed.addProperty("url", url);
            }
        }
        if (!description.isBlank()) {
            embed.addProperty("description", description);
        }
        Integer color = color(template.color());
        if (color != null) {
            embed.addProperty("color", color);
        }
        if (!author.isBlank()) {
            JsonObject line = new JsonObject();
            line.addProperty("name", author);
            String icon = link(template.author().icon(), values);
            if (icon != null) {
                line.addProperty("icon_url", icon);
            }
            String url = link(template.author().url(), values);
            if (url != null) {
                line.addProperty("url", url);
            }
            embed.add("author", line);
        }
        if (!fields.isEmpty()) {
            embed.add("fields", fields);
        }
        if (thumbnail != null) {
            embed.add("thumbnail", url(thumbnail));
        }
        if (image != null) {
            embed.add("image", url(image));
        }
        if (!footer.isBlank()) {
            JsonObject line = new JsonObject();
            line.addProperty("text", footer);
            embed.add("footer", line);
        }
        if (template.timestamp()) {
            embed.addProperty("timestamp", Instant.now().toString());
        }
        return embed;
    }

    private static int fieldsLength(JsonArray fields) {
        int length = 0;
        for (JsonElement element : fields) {
            JsonObject field = element.getAsJsonObject();
            length += field.get("name").getAsString().length() + field.get("value").getAsString().length();
        }
        return length;
    }

    private static JsonObject url(String url) {
        JsonObject object = new JsonObject();
        object.addProperty("url", url);
        return object;
    }

    /**
     * Merges a later message from the same template into an earlier one.
     *
     * <p>The descriptions are joined a line each; without an embed, the plain
     * messages are. Nothing is merged past a limit: the later message is then
     * sent on its own.
     *
     * @return whether {@code later} now lives inside {@code into}
     */
    public static boolean merge(@NotNull JsonObject into, @NotNull JsonObject later) {
        JsonObject first = firstEmbed(into);
        JsonObject next = firstEmbed(later);
        if (first != null && next != null) {
            if (!first.has("description") || !next.has("description")) {
                return false;
            }
            String joined = first.get("description").getAsString() + "\n" + next.get("description").getAsString();
            int total = embedLength(first) - first.get("description").getAsString().length() + joined.length();
            if (joined.length() > DESCRIPTION || total > EMBED_TOTAL) {
                return false;
            }
            first.addProperty("description", joined);
            return true;
        }
        if (first == null && next == null && into.has("content") && later.has("content")) {
            String joined = into.get("content").getAsString() + "\n" + later.get("content").getAsString();
            if (joined.length() > CONTENT) {
                return false;
            }
            into.addProperty("content", joined);
            return true;
        }
        return false;
    }

    private static @Nullable JsonObject firstEmbed(JsonObject payload) {
        return payload.has("embeds") ? payload.getAsJsonArray("embeds").get(0).getAsJsonObject() : null;
    }

    private static int embedLength(JsonObject embed) {
        int length = 0;
        for (String key : new String[]{"title", "description"}) {
            if (embed.has(key)) {
                length += embed.get(key).getAsString().length();
            }
        }
        if (embed.has("footer")) {
            length += embed.getAsJsonObject("footer").get("text").getAsString().length();
        }
        if (embed.has("author")) {
            length += embed.getAsJsonObject("author").get("name").getAsString().length();
        }
        if (embed.has("fields")) {
            length += fieldsLength(embed.getAsJsonArray("fields"));
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
        int end = limit - 1;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }
}
