package net.exylia.lib.discord;

import com.google.gson.JsonObject;
import net.exylia.lib.discord.internal.Payloads;
import net.exylia.lib.discord.internal.WebhookRuntime;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.input.TextInput;
import net.exylia.lib.text.Phrases;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * One plugin's webhook messages. Obtained from {@link Webhooks#of(Plugin)}.
 *
 * <h2>Threads</h2>
 * Every method may be called from any thread and none of them blocks: the
 * request goes out on the library's async scheduler. The returned stages and
 * the {@link #onInvalidated} listener complete off the main thread — hop with
 * {@code Tasks} before touching the world or a player.
 *
 * <h2>Lifecycle</h2>
 * Messages to one webhook leave in the order they were sent, at the pace
 * Discord allows. Up to 50 wait per webhook and 1000 in total; past that the
 * oldest waiting message of that webhook is dropped. When the plugin is
 * disabled its listeners are forgotten and whatever it still had waiting is
 * dropped, one tick after its {@code onDisable}.
 *
 * @since 1.245.0
 */
public final class PluginWebhooks {

    private final Plugin plugin;
    private final boolean everyone;
    private volatile PluginWebhooks playerOwned;

    PluginWebhooks(Plugin plugin, boolean everyone) {
        this.plugin = plugin;
        this.everyone = everyone;
    }

    /**
     * Returns the view for webhooks a player supplied.
     *
     * <p>The same queues and listeners; the one difference is that a literal
     * {@code @everyone} or {@code @here} in a template never pings. A clan's
     * Discord is not the server owner's to ping.
     *
     * <pre>{@code
     * PluginWebhooks clanLog = Webhooks.of(this).playerOwned();
     * clanLog.send(clan.webhook(), lang.memberJoined(), Map.of("player", player.getName()));
     * }</pre>
     *
     * @return the player-owned view
     */
    public @NotNull PluginWebhooks playerOwned() {
        if (!everyone) {
            return this;
        }
        PluginWebhooks view = playerOwned;
        if (view == null) {
            view = new PluginWebhooks(plugin, false);
            playerOwned = view;
        }
        return view;
    }

    /** Returns whether a literal {@code @everyone}/{@code @here} in a template pings. */
    public boolean allowsEveryone() {
        return everyone;
    }

    /**
     * Sends a template with no values.
     *
     * @see #send(WebhookTarget, WebhookTemplate, Map)
     */
    public @NotNull CompletionStage<WebhookResult> send(@NotNull WebhookTarget target,
                                                        @NotNull WebhookTemplate template) {
        return send(target, template, Map.of());
    }

    /**
     * Sends a template, filling its {@code %placeholders%}.
     *
     * <p>Values are keyed by bare name ({@code "player"} fills {@code %player%})
     * and are someone else's text by default: formatting removed, markdown
     * escaped, mentions broken. Wrap the server owner's own text in
     * {@link Webhooks#trusted}. In a URL part (an icon, an image, a link) the
     * value is inserted as is and the part is left out unless it ends up a valid
     * {@code http(s)} URL.
     *
     * <pre>{@code
     * webhooks.send(target, lang.winners(), Map.of("winner", winner.getName(), "zone", zone.name()))
     *         .thenAccept(result -> getLogger().fine("Winner announcement: " + result));
     * }</pre>
     *
     * <p>Pings only ever come from the template's {@code message}: the roles and
     * users written there as {@code <@&id>} / {@code <@id>}, and {@code @everyone}
     * if this view allows it. Nothing a value contains can ping.
     *
     * @param target   where to send it
     * @param template the message
     * @param values   the placeholder values, by bare name
     * @return what became of it: never fails exceptionally
     */
    public @NotNull CompletionStage<WebhookResult> send(@NotNull WebhookTarget target,
                                                        @NotNull WebhookTemplate template,
                                                        @NotNull Map<String, ?> values) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(template, "template");
        WebhookRuntime runtime = WebhookRuntime.get();
        if (runtime == null) {
            return CompletableFuture.completedFuture(WebhookResult.DROPPED);
        }
        if (!template.enabled()) {
            return CompletableFuture.completedFuture(WebhookResult.SKIPPED);
        }
        String owner = plugin.getName();
        JsonObject payload = Payloads.build(template, bare(values), everyone,
                line -> runtime.report("mentions:" + owner, owner + ": " + line));
        if (payload == null) {
            runtime.report("empty:" + owner + ":" + template.hashCode(), owner
                    + " has a webhook template with neither a message nor an embed; it is skipped");
            return CompletableFuture.completedFuture(WebhookResult.SKIPPED);
        }
        long window = template.coalesce().toMillis();
        return runtime.send(plugin, owner, target, payload, window > 0 ? template : null, window);
    }

    /**
     * Asks Discord whether a webhook exists, and what it is called.
     *
     * <p>For the moment a player sets one: show "Connected to <name>", then
     * send a first message. A webhook Discord reports as missing is remembered,
     * so asking again about the same one costs no request.
     *
     * <pre>{@code
     * webhooks.verify(target).thenAccept(check -> tasks.runAtEntity(player, () -> {
     *     if (check.connected()) Text.of("{success}Connected to %name%").with("%name%", check.name()).send(player);
     *     else Text.of("{error}That webhook does not work.").send(player);
     * }));
     * }</pre>
     *
     * @param target the webhook
     * @return the answer: never fails exceptionally
     */
    public @NotNull CompletionStage<WebhookCheck> verify(@NotNull WebhookTarget target) {
        Objects.requireNonNull(target, "target");
        WebhookRuntime runtime = WebhookRuntime.get();
        return runtime == null
                ? CompletableFuture.completedFuture(WebhookCheck.of(WebhookCheck.Status.UNREACHABLE, target))
                : runtime.verify(target);
    }

    /**
     * Runs when Discord says a webhook this plugin sends to is gone (404) or
     * its token is wrong (401).
     *
     * <p>Called once, off the main thread, the moment it is found out; from
     * then on every send to it answers {@link WebhookResult#INVALIDATED} without
     * a request. Clear the stored URL and tell its owner.
     *
     * <pre>{@code
     * Webhooks.of(this).onInvalidated(target -> clans.byWebhook(target).ifPresent(clan -> {
     *     clan.webhook(null);
     *     clan.notifyLeader("{error}Your clan's Discord webhook stopped working and was removed.");
     * }));
     * }</pre>
     *
     * @param listener receives the dead target
     * @return this view
     */
    public @NotNull PluginWebhooks onInvalidated(@NotNull Consumer<WebhookTarget> listener) {
        Objects.requireNonNull(listener, "listener");
        WebhookRuntime runtime = WebhookRuntime.get();
        if (runtime != null) {
            runtime.listen(plugin, listener);
        }
        return this;
    }

    /**
     * Asks a player for a webhook URL, then checks it with Discord.
     *
     * <p>The question is a {@link TextInput#sensitive() sensitive} text input:
     * a dialog where the client has one, and otherwise a chat line taken before
     * any other plugin reads it and never shown to anyone. A URL that is not a
     * Discord webhook is refused on the spot with the reason, and the player
     * asked again.
     *
     * <pre>{@code
     * webhooks.playerOwned().ask(player, "{primary}Paste your clan's webhook URL").thenAccept(check -> {
     *     if (!check.connected()) return;
     *     clan.webhook(check.target().secret());
     *     webhooks.playerOwned().send(check.target(), lang.webhookConnected());
     * });
     * }</pre>
     *
     * @param player who is asked
     * @param prompt the question, in the library's text notation
     * @return the check, {@link WebhookCheck.Status#CANCELLED} if they did not answer
     */
    public @NotNull CompletionStage<WebhookCheck> ask(@NotNull Player player, @NotNull String prompt) {
        TextInput input = Inputs.of(plugin).text(player, prompt)
                .sensitive()
                .hint(Phrases.tr("Discord: Server Settings » Integrations » Webhooks » Copy URL"));
        for (WebhookTarget.Rejection rejection : WebhookTarget.Rejection.values()) {
            input.validate(raw -> WebhookTarget.parse(raw).rejection() != rejection,
                    Phrases.tr(rejection.message()));
        }
        return input.open().thenCompose(result -> {
            if (!result.completed()) {
                return CompletableFuture.completedFuture(WebhookCheck.of(WebhookCheck.Status.CANCELLED, null));
            }
            WebhookTarget target = WebhookTarget.parse(result.value()).target();
            return target == null
                    ? CompletableFuture.completedFuture(WebhookCheck.of(WebhookCheck.Status.CANCELLED, null))
                    : verify(target);
        });
    }

    /** The plugin that owns this view. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /** Accepts {@code %name%} keys too, since that is how every other module writes them. */
    private static Map<String, ?> bare(Map<String, ?> values) {
        boolean wrapped = false;
        for (String key : values.keySet()) {
            if (key.length() > 1 && key.startsWith("%") && key.endsWith("%")) {
                wrapped = true;
                break;
            }
        }
        if (!wrapped) {
            return values;
        }
        Map<String, Object> bare = new HashMap<>(values.size());
        values.forEach((key, value) -> bare.put(key.length() > 1 && key.startsWith("%") && key.endsWith("%")
                ? key.substring(1, key.length() - 1) : key, value));
        return bare;
    }
}
