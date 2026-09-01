package net.exylia.lib.util.head.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.exylia.lib.input.SearchInput;
import net.exylia.lib.util.head.Head;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The headdb.net catalogue, one page at a time.
 *
 * <h2>Why nothing is downloaded</h2>
 * The catalogue offers a full snapshot and a revision feed, and syncing it would
 * make searching instant and offline. It would also cost about forty megabytes
 * on disk and an index of eighty-six thousand names in memory, on every server,
 * so that somebody can pick an icon twice a month. Asking for the page on screen
 * costs one request of about thirty kilobytes and keeps forty-five heads in
 * memory, which is the same answer for a thousandth of the footprint.
 *
 * <h2>Pages here are not pages there</h2>
 * The API answers with a fixed forty-eight results whatever limit is asked for,
 * and the window shows forty-five. A window therefore straddles at most two API
 * pages, and consecutive paging reuses one of the two it just fetched — which is
 * what the small cache below is for. It holds the last few pages and nothing
 * else; a page falls out as soon as a newer one needs the room.
 */
public final class HeadDb {

    private static final String ENDPOINT = "https://headdb.net/api/v1/heads";

    /** What the catalogue answers with, regardless of the limit asked for. */
    private static final int API_PAGE = 48;

    /** How many fetched pages are remembered: about two windows either way. */
    private static final int MAX_CACHED_PAGES = 24;

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    /**
     * One client for the whole server.
     *
     * <p>Built on first use rather than on class load, so a server that never
     * opens the picker never creates the connection pool or its threads.
     */
    private static final class Http {
        private static final HttpClient CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Recently fetched pages, newest kept.
     *
     * <p>Futures rather than results, so two players searching the same thing at
     * the same time make one request. A failed future is dropped on completion:
     * a page that could not be fetched must not be remembered as unfetchable.
     */
    private static final Map<String, CompletableFuture<Answer>> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CompletableFuture<Answer>> eldest) {
                    return size() > MAX_CACHED_PAGES;
                }
            });

    private HeadDb() {
        throw new AssertionError("No instances.");
    }

    /**
     * Fetches the window of results a search is showing.
     *
     * @param query  what the player typed; blank is the whole catalogue
     * @param offset how many results to skip
     * @param limit  how many to return
     * @return the page, completing exceptionally when the catalogue is unreachable
     */
    public static CompletableFuture<SearchInput.Page<Head>> fetch(String query, int offset, int limit) {
        int size = Math.max(1, limit);
        int start = Math.max(0, offset);
        int first = start / API_PAGE + 1;
        int last = (start + size - 1) / API_PAGE + 1;

        CompletableFuture<Answer> head = page(query, first);
        CompletableFuture<Answer> tail = last == first ? head : page(query, last);
        return head.thenCombine(tail, (one, two) ->
                new SearchInput.Page<>(window(start, size, first, one.heads(), two.heads()),
                        one.total()));
    }

    /**
     * Cuts the window out of the one or two API pages that hold it.
     *
     * <p>Package-private for the test: the arithmetic between two page sizes is
     * the only thing here that can be wrong in a way nobody notices — an
     * off-by-one drops or repeats three heads a page, which reads as the
     * catalogue being odd rather than as a bug.
     *
     * @param start  first result wanted, counted from the start of the query
     * @param size   how many are wanted
     * @param first  the API page number {@code one} is
     * @param one    the API page holding {@code start}
     * @param two    the API page after it, or {@code one} when there is no need
     * @return the results in that window, short when the query ran out
     */
    static List<Head> window(int start, int size, int first, List<Head> one, List<Head> two) {
        List<Head> window = new ArrayList<>(size);
        int base = (first - 1) * API_PAGE;
        for (int index = start; index < start + size; index++) {
            boolean early = index < base + API_PAGE;
            List<Head> holding = early ? one : two;
            int within = index - (early ? base : base + API_PAGE);
            if (within < 0 || within >= holding.size()) {
                continue;
            }
            window.add(holding.get(within));
        }
        return window;
    }

    /** Forgets every remembered page. */
    public static void invalidate() {
        CACHE.clear();
    }

    private static CompletableFuture<Answer> page(String query, int number) {
        String key = query.trim().toLowerCase(Locale.ROOT) + '#' + number;
        CompletableFuture<Answer> cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        CompletableFuture<Answer> fetching = request(query, number);
        CACHE.put(key, fetching);
        fetching.whenComplete((answer, failure) -> {
            if (failure != null) {
                CACHE.remove(key, fetching);
            }
        });
        return fetching;
    }

    private static CompletableFuture<Answer> request(String query, int number) {
        final HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url(query, number)))
                    .header("Accept", "application/json")
                    .header("User-Agent", "ExyliaLib")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
        } catch (RuntimeException malformed) {
            return CompletableFuture.failedFuture(malformed);
        }
        return Http.CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        throw new IllegalStateException("headdb answered " + response.statusCode());
                    }
                    return read(response.body());
                });
    }

    private static String url(String query, int number) {
        StringBuilder url = new StringBuilder(ENDPOINT)
                .append("?limit=").append(API_PAGE)
                .append("&page=").append(number);
        String trimmed = query.trim();
        if (!trimmed.isEmpty()) {
            url.append("&q=").append(URLEncoder.encode(trimmed, StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    /**
     * Reads the fields a picker needs and skips everything else.
     *
     * <p>A head with no texture is dropped rather than drawn as a blank: it
     * cannot be rendered and it cannot be stored, so it is not an option.
     */
    private static Answer read(String body) {
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonObject()) {
            throw new IllegalStateException("headdb answered something that is not an object");
        }
        JsonObject object = parsed.getAsJsonObject();
        JsonArray items = object.getAsJsonArray("items");
        List<Head> heads = new ArrayList<>(items == null ? 0 : items.size());
        if (items != null) {
            for (JsonElement element : items) {
                Head head = head(element);
                if (head != null) {
                    heads.add(head);
                }
            }
        }
        return new Answer(List.copyOf(heads), total(object, heads.size()));
    }

    private static Head head(JsonElement element) {
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject item = element.getAsJsonObject();
        String texture = string(item, "texture");
        String name = string(item, "name");
        if (texture == null || texture.isBlank() || name == null || name.isBlank()) {
            return null;
        }
        JsonElement id = item.get("id");
        JsonElement category = item.get("category");
        String section = category != null && category.isJsonObject()
                ? string(category.getAsJsonObject(), "name") : null;
        return new Head(id != null && id.isJsonPrimitive() ? id.getAsInt() : 0,
                name, texture, section == null ? "" : section);
    }

    private static int total(JsonObject object, int fallback) {
        JsonElement pagination = object.get("pagination");
        if (pagination == null || !pagination.isJsonObject()) {
            return fallback;
        }
        JsonElement total = pagination.getAsJsonObject().get("total");
        return total != null && total.isJsonPrimitive() ? total.getAsInt() : fallback;
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    /** One fetched API page, and how many results the query has overall. */
    private record Answer(List<Head> heads, int total) {
    }
}
