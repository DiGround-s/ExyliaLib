package net.exylia.lib.discord.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.discord.WebhookCheck;
import net.exylia.lib.discord.WebhookResult;
import net.exylia.lib.discord.WebhookTarget;
import net.exylia.lib.task.Tasks;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Delivers webhook messages: one FIFO queue per webhook, Discord's rate limits
 * honoured, dead webhooks remembered.
 *
 * <h2>Threads</h2>
 * Every request runs on the library's async scheduler ({@code runAsync}); a
 * wait — a rate limit, a retry, a coalescing window — is a delayed task, never a
 * sleeping thread. One drain per webhook at a time keeps its messages in order.
 *
 * <h2>Dead webhooks</h2>
 * A 404 or 401 means the webhook is gone or its token is wrong, and it will
 * not come back. Asking again anyway is how a server ends up on Cloudflare's
 * invalid-request ban list, which blocks every webhook it posts to. So the
 * target is remembered as dead and every later send short-circuits without a
 * request.
 */
@ApiStatus.Internal
public final class WebhookRuntime {

    /** What a request turned into. {@code headers} are lower-case. */
    public record Reply(int status, @NotNull Map<String, String> headers, @NotNull String body) {
    }

    /** The network, replaceable in tests. */
    public interface Http {
        @NotNull Reply exchange(@NotNull String method, @NotNull URI uri, @Nullable String json)
                throws IOException, InterruptedException;

        /**
         * The same request, given up after {@code timeout}. Shutdown passes what is
         * left of its flush window, so one hung request cannot hold the disable for
         * the full request plus connect timeout.
         */
        default @NotNull Reply exchange(@NotNull String method, @NotNull URI uri, @Nullable String json,
                                        @NotNull Duration timeout) throws IOException, InterruptedException {
            return exchange(method, uri, json);
        }
    }

    /** Runs a task off the main thread after a delay; zero means as soon as possible. */
    public interface Scheduler {
        void later(long millis, @NotNull Runnable task);
    }

    static final int PER_WEBHOOK = 50;
    static final int GLOBAL = 1000;
    static final int ATTEMPTS = 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final long SHUTDOWN_FLUSH_MILLIS = 3000;

    private static volatile WebhookRuntime instance;

    private final Http http;
    private final Scheduler scheduler;
    private final Consumer<String> warn;
    private final Map<WebhookTarget, Lane> lanes = new ConcurrentHashMap<>();
    /** By plugin load, not name: a reload's new load must not lose its listeners to the old one's release. */
    private final Map<Object, List<Consumer<WebhookTarget>>> listeners = new ConcurrentHashMap<>();
    /** Webhooks Discord said are gone, and what it said. Capped and expiring like any cache. */
    private final Cache<WebhookTarget, WebhookCheck.Status> dead = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(6, TimeUnit.HOURS).build();
    /** Warnings already given, so one broken template is one line, not one per message. */
    private final Cache<String, Boolean> reported = Caffeine.newBuilder()
            .maximumSize(1_000).expireAfterWrite(10, TimeUnit.MINUTES).build();
    /** A rate limit outlives its lane: an idle webhook's lane is gone, its reset is not. */
    private final Cache<WebhookTarget, Long> resets = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(10, TimeUnit.MINUTES).build();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicLong drops = new AtomicLong();
    private final AtomicLong dropsReportedAt = new AtomicLong();
    private volatile long globalOpenAt;
    private volatile boolean closed;

    WebhookRuntime(Http http, Scheduler scheduler, Consumer<String> warn) {
        this.http = http;
        this.scheduler = scheduler;
        this.warn = warn;
    }

    /** Starts the runtime on the library's scheduler. */
    public static void init(@NotNull net.exylia.lib.ExyliaLib lib) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // A webhook URL never redirects; one that does is not Discord.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        String agent = "ExyliaLib/" + lib.version();
        Http http = new Http() {
            @Override
            public @NotNull Reply exchange(@NotNull String method, @NotNull URI uri, @Nullable String json)
                    throws IOException, InterruptedException {
                return exchange(method, uri, json, TIMEOUT);
            }

            @Override
            public @NotNull Reply exchange(@NotNull String method, @NotNull URI uri, @Nullable String json,
                                           @NotNull Duration timeout) throws IOException, InterruptedException {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                        .timeout(timeout)
                        .header("User-Agent", agent);
                if (json == null) {
                    request.GET();
                } else {
                    request.header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json));
                }
                // Waited on with the same bound: the request timeout alone does not
                // cover name resolution or the connect, which have their own 5 s.
                CompletableFuture<HttpResponse<String>> call =
                        client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString());
                HttpResponse<String> response;
                try {
                    response = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException stopped) {
                    call.cancel(true);
                    throw stopped;
                } catch (java.util.concurrent.TimeoutException late) {
                    call.cancel(true);
                    throw new java.net.http.HttpTimeoutException("no answer within " + timeout.toMillis() + " ms");
                } catch (java.util.concurrent.ExecutionException failed) {
                    throw failed.getCause() instanceof IOException io ? io : new IOException(failed.getCause());
                }
                Map<String, String> headers = new java.util.HashMap<>();
                response.headers().map().forEach((name, value) -> {
                    if (!value.isEmpty()) {
                        headers.put(name.toLowerCase(Locale.ROOT), value.getFirst());
                    }
                });
                return new Reply(response.statusCode(), headers, response.body() == null ? "" : response.body());
            }
        };
        Scheduler scheduler = (millis, task) -> {
            if (millis <= 0) {
                Tasks.of(lib).runAsync(task);
            } else {
                Tasks.of(lib).runAsyncLater(Math.max(1, (millis + 49) / 50), task);
            }
        };
        instance = new WebhookRuntime(http, scheduler, line -> Debug.of(lib).warn("Webhooks: " + line));
    }

    /** Replaces the runtime with one on a fake network and scheduler. */
    public static @NotNull WebhookRuntime installForTests(@NotNull Http http, @NotNull Scheduler scheduler,
                                                          @NotNull Consumer<String> warn) {
        WebhookRuntime runtime = new WebhookRuntime(http, scheduler, warn);
        instance = runtime;
        return runtime;
    }

    /** Returns the runtime, or {@code null} when the library is not running. */
    public static @Nullable WebhookRuntime get() {
        return instance;
    }

    // ------------------------------------------------------------------ send

    /**
     * Queues a message.
     *
     * @param owner    the sending plugin's load, for its listeners and its release
     * @param name     the sending plugin's name, for warnings
     * @param target   where it goes
     * @param payload  the JSON, owned by the runtime from here on
     * @param key      messages with an equal key may merge; {@code null} never merges
     * @param window   how long a mergeable message waits for company, in millis
     * @return what became of it
     */
    public @NotNull CompletableFuture<WebhookResult> send(@NotNull Object owner, @NotNull String name,
                                                          @NotNull WebhookTarget target,
                                                          @NotNull JsonObject payload, @Nullable Object key,
                                                          long window) {
        CompletableFuture<WebhookResult> future = new CompletableFuture<>();
        if (closed) {
            future.complete(WebhookResult.DROPPED);
            return future;
        }
        if (dead.getIfPresent(target) != null) {
            future.complete(WebhookResult.INVALIDATED);
            return future;
        }
        while (true) {
            Lane lane = lanes.computeIfAbsent(target, this::lane);
            synchronized (lane) {
                if (lane.retired) {
                    continue;
                }
                if (key != null) {
                    for (Pending pending : lane.queue) {
                        if (pending.attempts == 0 && key.equals(pending.key) && owner == pending.owner
                                && Payloads.merge(pending.payload, payload)) {
                            pending.futures.add(future);
                            return future;
                        }
                    }
                }
                if (lane.queue.size() >= PER_WEBHOOK || queued.get() >= GLOBAL) {
                    // The oldest gives way: the newest is what somebody is waiting to read.
                    Pending oldest = lane.queue.pollFirst();
                    countDrop();
                    if (oldest == null) {
                        future.complete(WebhookResult.DROPPED);
                        return future;
                    }
                    queued.decrementAndGet();
                    oldest.complete(WebhookResult.DROPPED);
                }
                lane.queue.addLast(new Pending(owner, name, payload, key,
                        key == null ? 0 : System.currentTimeMillis() + window, future));
                queued.incrementAndGet();
                if (!lane.draining) {
                    lane.draining = true;
                    scheduler.later(key == null ? 0 : window, () -> drain(lane));
                }
                return future;
            }
        }
    }

    private void drain(Lane lane) {
        while (true) {
            Pending pending;
            synchronized (lane) {
                if (closed) {
                    lane.draining = false;
                    return;
                }
                pending = lane.queue.peekFirst();
                if (pending == null) {
                    lane.draining = false;
                    lane.retired = true;
                    lanes.remove(lane.target, lane);
                    if (lane.openAt > System.currentTimeMillis()) {
                        resets.put(lane.target, lane.openAt);
                    }
                    return;
                }
                long now = System.currentTimeMillis();
                long wait = Math.max(Math.max(lane.openAt, globalOpenAt), pending.notBefore) - now;
                if (wait > 0) {
                    scheduler.later(wait, () -> drain(lane));
                    return;
                }
                lane.queue.pollFirst();
                queued.decrementAndGet();
            }
            if (isDead(lane.target)) {
                pending.complete(WebhookResult.INVALIDATED);
                continue;
            }
            deliver(lane, pending, true, TIMEOUT);
        }
    }

    /** One attempt. With {@code retry}, a failure that may pass goes back to the head of the queue. */
    private void deliver(Lane lane, Pending pending, boolean retry, Duration timeout) {
        pending.attempts++;
        Reply reply;
        try {
            reply = http.exchange("POST", postUri(lane.target), pending.payload.toString(), timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            pending.complete(WebhookResult.FAILED);
            return;
        } catch (Exception unreachable) {
            again(lane, pending, retry, 1000L << (pending.attempts - 1), WebhookResult.FAILED,
                    "could not reach Discord (" + unreachable.getClass().getSimpleName() + ")");
            return;
        }
        bucket(lane, reply.headers());
        int status = reply.status();
        if (status >= 200 && status < 300) {
            pending.complete(WebhookResult.SENT);
        } else if (status == 404 || status == 401) {
            // Listeners first: a caller that sees INVALIDATED may rely on its
            // onInvalidated cleanup having already run.
            invalidate(lane, status == 404 ? WebhookCheck.Status.NOT_FOUND : WebhookCheck.Status.UNAUTHORIZED,
                    pending.owner);
            pending.complete(WebhookResult.INVALIDATED);
        } else if (status == 429) {
            long wait = retryAfter(lane, reply);
            again(lane, pending, retry, wait, WebhookResult.RATE_LIMITED, null);
        } else if (status >= 500) {
            again(lane, pending, retry, 1000L << (pending.attempts - 1), WebhookResult.FAILED,
                    "Discord answered " + status);
        } else {
            pending.complete(WebhookResult.FAILED);
            report("refused:" + pending.name + ":" + status, pending.name + "'s message to "
                    + lane.target.masked() + " was refused (" + status + "): " + discordMessage(reply.body()));
        }
    }

    private void again(Lane lane, Pending pending, boolean retry, long waitMillis, WebhookResult outcome,
                       @Nullable String why) {
        if (!retry || pending.attempts >= ATTEMPTS || closed) {
            pending.complete(outcome);
            if (why != null) {
                report("failed:" + lane.target.id(), "gave up on a message to " + lane.target.masked() + ": " + why);
            }
            return;
        }
        synchronized (lane) {
            pending.notBefore = System.currentTimeMillis() + waitMillis;
            lane.queue.addFirst(pending);
            queued.incrementAndGet();
        }
    }

    /** Reads the webhook's bucket: with nothing left, the lane waits for the reset. */
    private void bucket(Lane lane, Map<String, String> headers) {
        if ("0".equals(headers.get("x-ratelimit-remaining"))) {
            long reset = seconds(headers.get("x-ratelimit-reset-after"));
            if (reset > 0) {
                lane.openAt = Math.max(lane.openAt, System.currentTimeMillis() + reset);
            }
        }
    }

    private long retryAfter(Lane lane, Reply reply) {
        long wait = -1;
        boolean global = "true".equalsIgnoreCase(reply.headers().get("x-ratelimit-global"));
        try {
            JsonElement body = JsonParser.parseString(reply.body());
            if (body.isJsonObject()) {
                JsonObject object = body.getAsJsonObject();
                if (object.has("retry_after")) {
                    wait = Math.round(object.get("retry_after").getAsDouble() * 1000);
                }
                global |= object.has("global") && object.get("global").getAsBoolean();
            }
        } catch (RuntimeException notJson) {
            // Cloudflare answers 429 in HTML; the header still says how long.
        }
        if (wait < 0) {
            wait = seconds(reply.headers().get("retry-after"));
        }
        wait = Math.max(wait, 500);
        long until = System.currentTimeMillis() + wait;
        if (global) {
            globalOpenAt = Math.max(globalOpenAt, until);
        } else {
            lane.openAt = Math.max(lane.openAt, until);
        }
        return wait;
    }

    private static long seconds(@Nullable String value) {
        if (value == null) {
            return -1;
        }
        try {
            return Math.round(Double.parseDouble(value.trim()) * 1000);
        } catch (NumberFormatException unreadable) {
            return -1;
        }
    }

    /** Marks a webhook dead, drops what was waiting for it and tells whoever sent to it. */
    private void invalidate(Lane lane, WebhookCheck.Status why, Object sender) {
        dead.put(lane.target, why);
        Set<Object> owners = new LinkedHashSet<>();
        owners.add(sender);
        List<Pending> stranded;
        synchronized (lane) {
            stranded = new ArrayList<>(lane.queue);
            lane.queue.clear();
            queued.addAndGet(-stranded.size());
        }
        for (Pending pending : stranded) {
            owners.add(pending.owner);
            pending.complete(WebhookResult.INVALIDATED);
        }
        for (Object owner : owners) {
            for (Consumer<WebhookTarget> listener : listeners.getOrDefault(owner, List.of())) {
                try {
                    listener.accept(lane.target);
                } catch (Throwable failure) {
                    report("listener:" + owner, "a webhook listener threw " + failure);
                }
            }
        }
    }

    // ---------------------------------------------------------------- verify

    /** Asks Discord whether a webhook exists, off the main thread. */
    public @NotNull CompletableFuture<WebhookCheck> verify(@NotNull WebhookTarget target) {
        CompletableFuture<WebhookCheck> future = new CompletableFuture<>();
        WebhookCheck.Status known = dead.getIfPresent(target);
        if (known != null || closed) {
            future.complete(WebhookCheck.of(known != null ? known : WebhookCheck.Status.UNREACHABLE, target));
            return future;
        }
        scheduler.later(0, () -> future.complete(check(target)));
        return future;
    }

    private WebhookCheck check(WebhookTarget target) {
        Reply reply;
        try {
            reply = http.exchange("GET", uri(target), null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return WebhookCheck.of(WebhookCheck.Status.UNREACHABLE, target);
        } catch (Exception unreachable) {
            return WebhookCheck.of(WebhookCheck.Status.UNREACHABLE, target);
        }
        switch (reply.status()) {
            case 200 -> {
                try {
                    JsonObject body = JsonParser.parseString(reply.body()).getAsJsonObject();
                    return new WebhookCheck(WebhookCheck.Status.CONNECTED, target,
                            string(body, "name"), string(body, "channel_id"), string(body, "guild_id"));
                } catch (RuntimeException unreadable) {
                    return WebhookCheck.of(WebhookCheck.Status.UNREACHABLE, target);
                }
            }
            case 404, 401 -> {
                WebhookCheck.Status status = reply.status() == 404
                        ? WebhookCheck.Status.NOT_FOUND : WebhookCheck.Status.UNAUTHORIZED;
                dead.put(target, status);
                return WebhookCheck.of(status, target);
            }
            case 429 -> {
                return WebhookCheck.of(WebhookCheck.Status.RATE_LIMITED, target);
            }
            default -> {
                return WebhookCheck.of(WebhookCheck.Status.UNREACHABLE, target);
            }
        }
    }

    private static @Nullable String string(JsonObject body, String key) {
        JsonElement value = body.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    // ------------------------------------------------------------- lifecycle

    /** Registers a dead-webhook listener for a plugin. */
    public void listen(@NotNull Object owner, @NotNull Consumer<WebhookTarget> listener) {
        listeners.computeIfAbsent(owner, ignored -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /** Returns whether a target is known to be dead. */
    public boolean isDead(@NotNull WebhookTarget target) {
        return dead.getIfPresent(target) != null;
    }

    /** Forgets a plugin: its listeners, and its messages still waiting, which are dropped. */
    public void release(@NotNull Object owner) {
        listeners.remove(owner);
        for (Lane lane : lanes.values()) {
            List<Pending> released = new ArrayList<>();
            synchronized (lane) {
                for (Iterator<Pending> it = lane.queue.iterator(); it.hasNext(); ) {
                    Pending pending = it.next();
                    if (pending.owner == owner) {
                        it.remove();
                        released.add(pending);
                    }
                }
                queued.addAndGet(-released.size());
            }
            released.forEach(pending -> pending.complete(WebhookResult.DROPPED));
        }
    }

    /**
     * Stops: what is still queued gets one attempt each, inline, for a few
     * seconds at most; whatever is left after that is dropped.
     *
     * <p>Inline because the scheduler is about to go, and the server is
     * stopping, so nobody is playing through the pause.
     */
    public void shutdown() {
        closed = true;
        listeners.clear();
        long deadline = System.currentTimeMillis() + SHUTDOWN_FLUSH_MILLIS;
        for (Lane lane : lanes.values()) {
            List<Pending> left;
            synchronized (lane) {
                left = new ArrayList<>(lane.queue);
                lane.queue.clear();
                queued.addAndGet(-left.size());
            }
            for (Pending pending : left) {
                long now = System.currentTimeMillis();
                if (now < deadline && lane.openAt <= now && globalOpenAt <= now && !isDead(lane.target)) {
                    // Bounded by what is left of the window, not by the 10 s of a normal
                    // send: checking the deadline only between requests let one hung
                    // POST stall the disable for up to 15 s.
                    deliver(lane, pending, false, Duration.ofMillis(deadline - now));
                } else {
                    pending.complete(WebhookResult.DROPPED);
                }
            }
        }
        lanes.clear();
        if (instance == this) {
            instance = null;
        }
    }

    /** Returns how many messages are waiting, across every webhook. */
    public int queued() {
        return queued.get();
    }

    // --------------------------------------------------------------- helpers

    /** Warns once per key per ten minutes. */
    public void report(@NotNull String key, @NotNull String line) {
        if (reported.asMap().putIfAbsent(key, Boolean.TRUE) == null) {
            warn.accept(line);
        }
    }

    private void countDrop() {
        drops.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = dropsReportedAt.get();
        if (now - last >= 60_000 && dropsReportedAt.compareAndSet(last, now)) {
            warn.accept(drops.getAndSet(0) + " message(s) dropped because a webhook queue was full;"
                    + " a plugin is sending faster than Discord accepts.");
        }
    }

    private Lane lane(WebhookTarget target) {
        Lane lane = new Lane(target);
        Long reset = resets.getIfPresent(target);
        if (reset != null) {
            lane.openAt = reset;
        }
        return lane;
    }

    private static URI uri(WebhookTarget target) {
        // Rebuilt from the parsed parts every time: never the text somebody typed.
        return URI.create(target.secret());
    }

    /** Where a message is posted: a webhook no application owns takes components only when asked to. */
    static URI postUri(WebhookTarget target) {
        return URI.create(target.secret() + "?with_components=true");
    }

    private static String discordMessage(String body) {
        try {
            JsonObject object = JsonParser.parseString(body).getAsJsonObject();
            return object.has("message") ? object.get("message").getAsString() : "no reason given";
        } catch (RuntimeException notJson) {
            return "no reason given";
        }
    }

    /** One webhook's queue and rate-limit state. Guarded by its own monitor. */
    private static final class Lane {
        final WebhookTarget target;
        final Deque<Pending> queue = new ArrayDeque<>();
        boolean draining;
        /** Removed from the map; a sender that still holds it must fetch a new one. */
        boolean retired;
        volatile long openAt;

        Lane(WebhookTarget target) {
            this.target = target;
        }
    }

    private static final class Pending {
        final Object owner;
        final String name;
        final JsonObject payload;
        final @Nullable Object key;
        final List<CompletableFuture<WebhookResult>> futures = new ArrayList<>(1);
        long notBefore;
        int attempts;

        Pending(Object owner, String name, JsonObject payload, @Nullable Object key, long notBefore,
                CompletableFuture<WebhookResult> future) {
            this.owner = owner;
            this.name = name;
            this.payload = payload;
            this.key = key;
            this.notBefore = notBefore;
            futures.add(future);
        }

        void complete(WebhookResult result) {
            for (CompletableFuture<WebhookResult> future : futures) {
                future.complete(result);
            }
        }
    }
}
