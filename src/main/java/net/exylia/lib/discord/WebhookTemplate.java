package net.exylia.lib.discord;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Sparse;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;

/**
 * A Discord message written in configuration: an optional plain message and an
 * optional embed.
 *
 * <p>Nest it in a config record like any other section, or read it from a
 * hand-written file with {@link #read(ConfigurationSection)}. The keys are the
 * same either way:
 *
 * <pre>{@code
 * capture-started:
 *   enabled: true
 *   message: '<@&123456789012345678> A capture just started!'
 *   username: ''
 *   avatar-url: ''
 *   color: '{primary}'
 *   title: 'CAPTURE STARTED'
 *   url: ''
 *   description: 'Zone **%zone%** is now open.'
 *   author:
 *     name: '%player%'
 *     icon: '%player_head%'
 *   thumbnail: ''
 *   image: ''
 *   fields:
 *     - 'Duration|%duration% ⌚|inline'
 *   footer: '%server%'
 *   timestamp: true
 *   coalesce: 0s
 * }</pre>
 *
 * <p>Text is the server owner's: Discord markdown is kept, {@code &l} becomes
 * bold and every other colour code, palette token or MiniMessage tag is removed.
 * {@code %placeholders%} are filled with the values the plugin passes, which are
 * escaped unless marked {@link Webhooks#trusted trusted}. Over-long parts are
 * cut to Discord's limits with an ellipsis rather than refused.
 *
 * @param enabled     whether the message is sent at all
 * @param message     the plain message above the embed; the only part that can ping
 * @param username    the name the message is posted under; empty keeps the webhook's
 * @param avatarUrl   the avatar it is posted with; empty keeps the webhook's
 * @param color       the embed's side colour: a palette token such as {@code {primary}}, or {@code #rrggbb}
 * @param title       the embed title
 * @param url         the link on the title
 * @param description the embed body
 * @param author      the line above the title
 * @param thumbnail   the small image at the top right
 * @param image       the large image at the bottom
 * @param fields      {@code name|value} or {@code name|value|inline}, one per entry
 * @param footer      the small line at the bottom
 * @param timestamp   whether the embed shows when it was sent
 * @param coalesce    messages from this template to the same webhook within this
 *                    window are merged into one; zero sends each on its own
 * @since 1.245.0
 */
public record WebhookTemplate(
        @Comment("Whether this message is sent at all.")
        boolean enabled,

        @Comment("Plain message above the embed, and the only part that can ping.")
        @Comment("<@&role-id>, <@user-id>, @everyone and @here written here notify.")
        String message,

        @Comment("The name it is posted under. Empty keeps the webhook's own.")
        String username,

        @Comment("The avatar it is posted with, as an https:// image URL. Empty keeps the webhook's own.")
        String avatarUrl,

        @Comment("Side colour of the embed: a palette token such as {primary}, or #rrggbb.")
        String color,

        String title,

        @Comment("Link on the title. Empty for none.")
        String url,

        String description,

        @Comment("The line above the title: name, icon (image URL) and url.")
        Author author,

        @Comment("Small image at the top right, as an https:// URL.")
        String thumbnail,

        @Comment("Large image at the bottom, as an https:// URL.")
        String image,

        @Comment("One per entry: 'name|value', or 'name|value|inline' to sit side by side.")
        List<String> fields,

        String footer,

        @Comment("Whether the embed shows the time it was sent.")
        boolean timestamp,

        @Comment("Messages from this template sent within this window are merged into one,")
        @Comment("their descriptions one line each. 0s sends every message on its own.")
        Duration coalesce
) {

    /** Normalises missing values, so a hand-built template never carries a {@code null}. */
    public WebhookTemplate {
        message = orEmpty(message);
        username = orEmpty(username);
        avatarUrl = orEmpty(avatarUrl);
        color = orEmpty(color);
        title = orEmpty(title);
        url = orEmpty(url);
        description = orEmpty(description);
        author = author == null ? new Author() : author;
        thumbnail = orEmpty(thumbnail);
        image = orEmpty(image);
        fields = fields == null ? List.of() : List.copyOf(fields);
        footer = orEmpty(footer);
        coalesce = coalesce == null || coalesce.isNegative() ? Duration.ZERO : coalesce;
    }

    /** The default: enabled, empty, in the primary colour, with a timestamp. */
    public WebhookTemplate() {
        this("", "");
    }

    /**
     * An embed with a title and a description, everything else at its default.
     *
     * <p>The shape a plugin's shipped defaults usually take:
     * {@code new WebhookTemplate("CAPTURE STARTED", "Zone **%zone%** is now open.")}.
     *
     * @param title       the embed title
     * @param description the embed body
     */
    public WebhookTemplate(@NotNull String title, @NotNull String description) {
        this(true, "", "", "", "{primary}", title, "", description, new Author(), "", "",
                List.of(), "", true, Duration.ZERO);
    }

    /**
     * Returns a copy with a different plain message.
     *
     * @param message the plain message; ping syntax here notifies
     * @return the copy
     */
    public @NotNull WebhookTemplate withMessage(@NotNull String message) {
        return new WebhookTemplate(enabled, message, username, avatarUrl, color, title, url,
                description, author, thumbnail, image, fields, footer, timestamp, coalesce);
    }

    /**
     * Returns a copy with different fields.
     *
     * @param fields {@code name|value} or {@code name|value|inline}, one per entry
     * @return the copy
     */
    public @NotNull WebhookTemplate withFields(@NotNull List<String> fields) {
        return new WebhookTemplate(enabled, message, username, avatarUrl, color, title, url,
                description, author, thumbnail, image, fields, footer, timestamp, coalesce);
    }

    /**
     * Reads a template from a hand-written file, with the same keys a config
     * record writes. A missing key takes its default; nothing is written back.
     *
     * @param section the section holding the template; {@code null} is the default template
     * @return the template
     */
    public static @NotNull WebhookTemplate read(@Nullable ConfigurationSection section) {
        WebhookTemplate defaults = new WebhookTemplate();
        if (section == null) {
            return defaults;
        }
        ConfigurationSection author = section.getConfigurationSection("author");
        // A bare number is seconds, as in a config record.
        Object coalesce = section.get("coalesce", 0);
        Duration window = coalesce instanceof Number seconds
                ? Duration.ofMillis(Math.round(seconds.doubleValue() * 1000))
                : net.exylia.lib.input.InputParser.duration().parse(String.valueOf(coalesce).trim()).value();
        return new WebhookTemplate(
                section.getBoolean("enabled", true),
                section.getString("message", ""),
                section.getString("username", ""),
                section.getString("avatar-url", ""),
                section.getString("color", defaults.color()),
                section.getString("title", ""),
                section.getString("url", ""),
                section.getString("description", ""),
                author == null ? new Author()
                        : new Author(author.getString("name", ""), author.getString("icon", ""),
                        author.getString("url", "")),
                section.getString("thumbnail", ""),
                section.getString("image", ""),
                section.getStringList("fields"),
                section.getString("footer", ""),
                section.getBoolean("timestamp", true),
                window);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * The line above the embed title. Left out of the file while empty.
     *
     * @param name the text; the line is only drawn when it is set
     * @param icon the small round image beside it, as an https:// URL
     * @param url  the link on the name
     * @since 1.245.0
     */
    public record Author(String name, String icon, String url) implements Sparse {

        /** Normalises missing values to empty. */
        public Author {
            name = orEmpty(name);
            icon = orEmpty(icon);
            url = orEmpty(url);
        }

        /** No author line. */
        public Author() {
            this("", "", "");
        }

        @Override
        public boolean isEmpty() {
            return name.isEmpty() && icon.isEmpty() && url.isEmpty();
        }
    }
}
