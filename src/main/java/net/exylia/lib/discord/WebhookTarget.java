package net.exylia.lib.discord;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Discord webhook, reduced to the two things that identify it.
 *
 * <p>Whatever was typed or configured is parsed strictly and then thrown away:
 * the address a message is posted to is always rebuilt as
 * {@code https://discord.com/api/webhooks/<id>/<token>}. A URL somebody pasted
 * can never point the server at another host, carry a port, a query or a
 * redirect, or smuggle a path past the parser.
 *
 * <pre>{@code
 * WebhookTarget.Parsed parsed = WebhookTarget.parse(config.webhook());
 * if (!parsed.ok()) {
 *     getLogger().warning("Webhook ignored: " + parsed.rejection().message());
 *     return;
 * }
 * WebhookTarget target = parsed.target();
 * store(target.secret());                        // the canonical URL, for storage
 * WebhookTarget back = WebhookTarget.parse(stored).target();
 * }</pre>
 *
 * <p>{@link #toString()} is masked ({@code https://discord.com/api/webhooks/1234…/••••}),
 * so a target that ends up in a log or an exception never leaks its token. The
 * token is only ever read on purpose, through {@link #token()} or
 * {@link #secret()}.
 *
 * @param id    the webhook id, 17 to 20 digits
 * @param token the webhook token
 * @since 1.245.0
 */
public record WebhookTarget(@NotNull String id, @NotNull String token) {

    private static final Pattern ID = Pattern.compile("\\d{17,20}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{60,100}");
    private static final Pattern PATH = Pattern.compile("/api/webhooks/([^/]+)/([^/]+)/?");
    private static final Set<String> HOSTS = Set.of(
            "discord.com", "discordapp.com", "ptb.discord.com", "canary.discord.com");
    /** Longer than any real webhook URL by a wide margin; past it, nothing is parsed. */
    private static final int MAX_LENGTH = 300;

    /**
     * Rejects anything but a well-formed id and token.
     *
     * @throws IllegalArgumentException when either does not match Discord's shape
     */
    public WebhookTarget {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("webhook id must be 17 to 20 digits");
        }
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("webhook token is malformed");
        }
    }

    /**
     * Parses a webhook URL as Discord hands it out.
     *
     * <p>Accepted: {@code https://} on {@code discord.com}, {@code discordapp.com},
     * {@code ptb.discord.com} or {@code canary.discord.com}, the path
     * {@code /api/webhooks/<id>/<token>} and an optional trailing slash.
     * Surrounding whitespace is ignored. Everything else — another scheme or
     * host, credentials, a port, a query, a fragment, any other path — is
     * rejected with the reason.
     *
     * @param raw what was typed or configured; {@code null} is blank
     * @return the target, or why there is none
     */
    public static @NotNull Parsed parse(@Nullable String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return Parsed.rejected(Rejection.BLANK);
        }
        if (text.length() > MAX_LENGTH) {
            return Parsed.rejected(Rejection.MALFORMED);
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException malformed) {
            return Parsed.rejected(Rejection.MALFORMED);
        }
        if (uri.getScheme() == null || uri.isOpaque()) {
            return Parsed.rejected(Rejection.MALFORMED);
        }
        if (!uri.getScheme().equalsIgnoreCase("https")) {
            return Parsed.rejected(Rejection.NOT_HTTPS);
        }
        if (uri.getRawUserInfo() != null) {
            return Parsed.rejected(Rejection.EXTRA);
        }
        String host = uri.getHost();
        if (host == null || !HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            return Parsed.rejected(Rejection.HOST);
        }
        if (uri.getPort() != -1 || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return Parsed.rejected(Rejection.EXTRA);
        }
        Matcher path = PATH.matcher(uri.getRawPath() == null ? "" : uri.getRawPath());
        if (!path.matches()) {
            return Parsed.rejected(Rejection.PATH);
        }
        if (!ID.matcher(path.group(1)).matches() || !TOKEN.matcher(path.group(2)).matches()) {
            return Parsed.rejected(Rejection.INCOMPLETE);
        }
        return new Parsed(new WebhookTarget(path.group(1), path.group(2)), null);
    }

    /**
     * Returns the canonical URL, token included, for storing the target.
     *
     * <p>{@link #parse} reads it back. Treat it like a password: it is all
     * anyone needs to post as this webhook.
     *
     * @return {@code https://discord.com/api/webhooks/<id>/<token>}
     */
    public @NotNull String secret() {
        return "https://discord.com/api/webhooks/" + id + "/" + token;
    }

    /**
     * Returns a form safe to show or log: the first digits of the id and no token.
     *
     * @return {@code https://discord.com/api/webhooks/1234…/••••}
     */
    public @NotNull String masked() {
        return "https://discord.com/api/webhooks/" + id.substring(0, 4) + "…/••••";
    }

    /** Masked: see {@link #masked()}. */
    @Override
    public @NotNull String toString() {
        return masked();
    }

    /**
     * The outcome of {@link #parse}: exactly one of the two is set.
     *
     * @param target    the parsed target, when accepted
     * @param rejection why it was refused, when not
     * @since 1.245.0
     */
    public record Parsed(@Nullable WebhookTarget target, @Nullable Rejection rejection) {

        private static Parsed rejected(Rejection rejection) {
            return new Parsed(null, rejection);
        }

        /** Returns whether the URL was accepted. */
        public boolean ok() {
            return target != null;
        }
    }

    /**
     * Why a URL was refused. {@link #message()} is written for the player who
     * pasted it, in English; pass it through {@code Phrases.tr} to translate.
     *
     * @since 1.245.0
     */
    public enum Rejection {
        /** Nothing was given. */
        BLANK("Paste the webhook URL."),
        /** Not a URL at all. */
        MALFORMED("That is not a URL."),
        /** Not {@code https://}. */
        NOT_HTTPS("The URL has to start with https://"),
        /** Not one of Discord's hosts. */
        HOST("That URL is not from discord.com."),
        /** Credentials, a port, a query or a fragment were added. */
        EXTRA("Paste the URL exactly as Discord gives it, with nothing added."),
        /** Not a webhook path. */
        PATH("That is not a webhook URL. Copy it from Integrations » Webhooks."),
        /** The id or the token is cut short or malformed. */
        INCOMPLETE("The webhook URL is incomplete. Copy it again.");

        private final String message;

        Rejection(String message) {
            this.message = message;
        }

        /** Returns the explanation for the player, in English. */
        public @NotNull String message() {
            return message;
        }
    }
}
