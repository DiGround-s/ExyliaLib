package net.exylia.lib.placeholder.internal;

import net.exylia.lib.placeholder.Request;
import net.exylia.lib.placeholder.Template;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * A template whose parts are known, so rendering only resolves and joins.
 */
public final class CompiledTemplate implements Template {

    private final String raw;
    private final List<Part> parts;
    private final List<String> names;
    private final boolean dynamic;

    /** Set when the text has no placeholders, so rendering returns it directly. */
    private final String constant;

    private final Logger logger;

    CompiledTemplate(String raw, List<Part> parts, Logger logger) {
        this.raw = raw;
        this.parts = parts;
        this.logger = logger;

        List<String> found = new ArrayList<>(4);
        for (Part part : parts) {
            if (!part.isLiteral()) {
                found.add(part.name());
            }
        }
        this.names = List.copyOf(found);
        this.dynamic = !found.isEmpty();

        if (dynamic) {
            this.constant = null;
        } else {
            // Built from the parts rather than from raw: text with no
            // placeholders can still have changed, because "%%" collapses to a
            // single percent sign.
            StringBuilder fixed = new StringBuilder(raw.length());
            for (Part part : parts) {
                fixed.append(part.literal());
            }
            this.constant = fixed.toString();
        }
    }

    @Override
    public @NotNull String render() {
        return constant != null ? constant : renderFor(Request.EMPTY);
    }

    @Override
    public @NotNull String render(@Nullable Player viewer) {
        if (constant != null) {
            return constant;
        }
        return renderFor(new Request(viewer, viewer, List.of(), Map.of()));
    }

    @Override
    public @NotNull String render(@Nullable Player viewer, @NotNull Map<String, Object> data) {
        if (constant != null) {
            return constant;
        }
        return renderFor(new Request(viewer, viewer, List.of(), data));
    }

    @Override
    public @NotNull String render(@Nullable Player viewer, @Nullable OfflinePlayer target) {
        if (constant != null) {
            return constant;
        }
        return renderFor(new Request(viewer, target, List.of(), Map.of()));
    }

    @Override
    public @NotNull String renderFor(@NotNull Request request) {
        if (constant != null) {
            return constant;
        }

        // Sized from the source text, which is close enough that the builder
        // rarely has to grow.
        StringBuilder result = new StringBuilder(raw.length() + 16);

        for (Part part : parts) {
            if (part.isLiteral()) {
                result.append(part.literal());
                continue;
            }

            // The arguments belong to the placeholder text, not to the caller,
            // so they are swapped in for this resolution only.
            Request scoped = part.args().isEmpty()
                    ? request
                    : new Request(request.viewer(), request.target(), part.args(), request.data());

            Object value = resolveWith(part, scoped);

            if (value == null) {
                if (part.fallback() != null) {
                    result.append(part.fallback());
                } else {
                    // Nothing resolved and no fallback: leave it visible so the
                    // owner can see which placeholder is unknown.
                    result.append(part.original());
                }
                continue;
            }

            result.append(Formats.apply(value, part.format()));
        }

        return result.toString();
    }

    /**
     * Resolves one placeholder, from a registered resolver or from the values
     * the caller attached to this render.
     *
     * <p>A registered resolver wins. It is the considered registration, knows
     * about arguments and is what {@code %player_name%} means everywhere on the
     * server; a per-render value must not be able to shadow it by accident.
     *
     * <p>The attached values are consulted second, so
     * {@code apply(text, player, Map.of("class", "Warrior"))} fills in
     * {@code %class%} without anybody registering a resolver for a value that
     * only exists for the length of one message. Before this, that map was only
     * readable from inside a resolver, which meant the obvious call quietly did
     * nothing.
     *
     * @param part    the placeholder to resolve
     * @param request the request being rendered
     * @return the value, or {@code null} when nothing supplies one
     */
    private Object resolveWith(Part part, Request request) {
        Object value = Registry.resolve(part.name(), request, logger);
        if (value != null) {
            return value;
        }
        // Only when no resolver owns the name, so a registered placeholder that
        // legitimately resolved to nothing is not overridden by stray data.
        if (Registry.has(part.name())) {
            return null;
        }
        Object attached = request.data().get(part.name());
        if (attached == null) {
            Registry.reportUnknown(part.name(), logger);
        }
        return attached;
    }

    @Override
    public @NotNull List<String> placeholders() {
        return names;
    }

    @Override
    public boolean isDynamic() {
        return dynamic;
    }

    @Override
    public @NotNull String raw() {
        return raw;
    }

    /**
     * Resolves every placeholder and returns them paired with the text they
     * were written as.
     *
     * <p>For callers that substitute into something other than a string, such as
     * the text module inserting values into an already parsed component. Doing
     * it this way keeps both caches usable: the component is parsed once for
     * everybody, and only the values differ per player.
     *
     * @param request who is asking
     * @return alternating original text and resolved value, empty when the
     *         template has no placeholders
     */
    public List<String> resolvePairs(Request request) {
        if (!dynamic) {
            return List.of();
        }
        List<String> pairs = new ArrayList<>(names.size() * 2);
        for (Part part : parts) {
            if (part.isLiteral()) {
                continue;
            }
            Request scoped = part.args().isEmpty()
                    ? request
                    : new Request(request.viewer(), request.target(), part.args(), request.data());

            Object value = resolveWith(part, scoped);
            String rendered;
            if (value == null) {
                if (part.fallback() == null) {
                    // Unknown placeholder: leave it alone rather than blanking
                    // it, so a typo in a config is visible to its owner.
                    continue;
                }
                rendered = part.fallback();
            } else {
                rendered = Formats.apply(value, part.format());
            }
            pairs.add(part.original());
            pairs.add(rendered);
        }
        return pairs;
    }

    /** Returns whether every placeholder used here is safe off the main thread. */
    public boolean isAsyncSafe() {
        for (Part part : parts) {
            if (part.isLiteral()) {
                continue;
            }
            Registry.Entry entry = Registry.get(part.name());
            if (entry == null || !entry.async()) {
                return false;
            }
        }
        return true;
    }

    /** Returns the placeholders this template could not match to a resolver. */
    public List<String> unresolved() {
        List<String> missing = new ArrayList<>();
        for (Part part : parts) {
            if (!part.isLiteral() && !Registry.has(part.name())) {
                missing.add(part.name());
            }
        }
        return List.copyOf(missing);
    }

    @Override
    public String toString() {
        return "Template[" + raw + "]";
    }
}
