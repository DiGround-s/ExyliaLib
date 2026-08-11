package net.exylia.lib.placeholder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Produces the value of one placeholder.
 *
 * <p>The only resolver type in this module. Whether a placeholder needs a
 * player, arguments, both or neither is decided by what the lambda reads from
 * its {@link Request}, not by picking a category up front.
 *
 * <pre>{@code
 * group.add("balance", request -> economy.balanceOf(request.requireViewer()));
 * group.add("online", request -> Bukkit.getOnlinePlayers().size());
 * group.add("top", request -> leaderboard.at(request.arg(0, 1)));
 * }</pre>
 *
 * <h2>Return value</h2>
 * Anything. Numbers, booleans and enums are formatted sensibly, so there is no
 * need to call {@code String.valueOf} or {@code toString}.
 *
 * <p>Returning {@code null} means "no value": the caller's fallback is used, or
 * the placeholder is left visible when there is none. That is how an optional
 * value is expressed, and it is why a resolver should return {@code null} rather
 * than throw when something is missing.
 *
 * <h2>Threading</h2>
 * A resolver may be called from any thread, and from many at once, so it must
 * not touch anything that is not safe to read concurrently. In particular it
 * must not call the Bukkit API from an async render: read from a cache or a
 * snapshot instead.
 *
 * <p>A resolver that throws is logged once and treated as if it returned
 * {@code null}, so one broken placeholder cannot take down a scoreboard.
 *
 * @since 1.3.0
 */
@FunctionalInterface
public interface Resolver {

    /**
     * Produces the value for a request.
     *
     * @param request who is asking, about whom, and with what arguments
     * @return the value, or {@code null} when there is none
     */
    @Nullable Object resolve(@NotNull Request request);
}
