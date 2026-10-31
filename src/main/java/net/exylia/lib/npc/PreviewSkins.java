package net.exylia.lib.npc;

import com.destroystokyo.paper.profile.ProfileProperty;
import net.exylia.lib.ragdoll.RagdollModel;
import net.exylia.lib.skull.internal.Textures;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Whose skin a preview body wears when it belongs to nobody: the mannequin an effect plays on, the
 * ragdoll that falls, the {@code [NPC] {victim}} that lies there. An ordered list of sources, the first
 * that has a body wins:
 *
 * <ul>
 *   <li>{@code others}: another online player, picked at random each time, so the "victim" varies;</li>
 *   <li>{@code viewer}: the person looking;</li>
 *   <li>{@code defaults}: vanilla's default skins (Steve, Alex, Ari, Efe, Kai, Makena, Noor, Sunny,
 *       Zuri, slim and wide), one after another.</li>
 * </ul>
 *
 * <pre>{@code
 * PreviewSkins.Body body = PreviewSkins.of(List.of("others", "defaults")).pick(viewer);
 * npcs.show(body.npc().nameless(), motion, at, life, List.of(viewer));      // stands there
 * sequences.play(kill, SequenceTarget.at(at).by(viewer).onlyTo(viewer)
 *         .wearing(body.ragdoll()).standing(body::npc));                       // and dies the same
 * }</pre>
 *
 * <p>{@code viewer} and {@code defaults} always have a body, so whatever follows them is never reached;
 * an empty or unreadable list is {@link #DEFAULT}. Never the block-coloured placeholder: a default skin
 * is the real one, drawn by the client from a UUID that hashes to it ({@link DefaultSkin}).
 *
 * <p>Other players' skins are read from their own profile on their own thread when they join, and kept
 * here; picking one is a map read, never a lookup.
 *
 * @since 1.416.0
 */
public final class PreviewSkins {

    /** Where a body's skin can come from. */
    public enum Source { OTHERS, VIEWER, DEFAULTS }

    /** {@code [others, defaults]}: somebody else on the server, or a vanilla default when alone. */
    public static final PreviewSkins DEFAULT = new PreviewSkins(List.of(Source.OTHERS, Source.DEFAULTS));

    /** {@link #DEFAULT} as a config writes it. @since 1.428.0 */
    public static final List<String> DEFAULT_NAMES = List.of("others", "defaults");

    /** Online players' {@code textures} value and signature, read on their own thread. */
    private static final Map<UUID, String[]> ONLINE = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_DEFAULT = new AtomicInteger(ThreadLocalRandom.current().nextInt(18));
    private static final AtomicBoolean DEFAULTS_WARMED = new AtomicBoolean();

    private final List<Source> sources;

    private PreviewSkins(List<Source> sources) {
        this.sources = sources;
    }

    /**
     * A policy from config: {@code others}, {@code viewer} and {@code defaults}, in order. Unknown names
     * are skipped; nothing left is {@link #DEFAULT}.
     */
    public static @NotNull PreviewSkins of(@Nullable List<String> names) {
        List<Source> read = new ArrayList<>();
        if (names != null) {
            for (String name : names) {
                if (name == null) continue;
                try {
                    Source source = Source.valueOf(name.trim().toUpperCase(Locale.ROOT));
                    if (!read.contains(source)) read.add(source);
                } catch (IllegalArgumentException unknown) {
                    // A typo costs that one entry, not the preview.
                }
            }
        }
        return read.isEmpty() ? DEFAULT : new PreviewSkins(List.copyOf(read));
    }

    /** The sources, in order. */
    public @NotNull List<Source> sources() {
        return sources;
    }

    /** A body for {@code viewer} to look at. Call on the viewer's thread. */
    public @NotNull Body pick(@NotNull Player viewer) {
        warmDefaults();
        UUID me = viewer.getUniqueId();
        for (Source source : sources) {
            switch (source) {
                case OTHERS -> {
                    Body other = other(me, ONLINE);
                    if (other != null) return other;
                }
                case VIEWER -> {
                    String[] own = ONLINE.get(me);
                    // Remembered as {null, null} when they have no skin (offline mode): their UUID's default.
                    if (own == null || own[0] == null) own = texturesOf(viewer);
                    return own != null && own[0] != null ? new Body(own[0], own[1], null) : new Body(null, null, DefaultSkin.of(me));
                }
                case DEFAULTS -> {
                    return nextDefault();
                }
            }
        }
        return nextDefault();
    }

    /** A random online player other than {@code viewer}, or {@code null} when there is none. */
    static @Nullable Body other(UUID viewer, Map<UUID, String[]> online) {
        List<Map.Entry<UUID, String[]>> others = new ArrayList<>(online.size());
        for (Map.Entry<UUID, String[]> entry : online.entrySet()) {
            if (!entry.getKey().equals(viewer)) others.add(entry);
        }
        if (others.isEmpty()) return null;
        Map.Entry<UUID, String[]> chosen = others.get(ThreadLocalRandom.current().nextInt(others.size()));
        String[] skin = chosen.getValue();
        // An offline-mode player with no skin looks like their UUID's default, so their body does too.
        return skin[0] != null ? new Body(skin[0], skin[1], null) : new Body(null, null, DefaultSkin.of(chosen.getKey()));
    }

    static Body nextDefault() {
        DefaultSkin[] all = DefaultSkin.values();
        return new Body(null, null, all[Math.floorMod(NEXT_DEFAULT.getAndIncrement(), all.length)]);
    }

    /** Reads every default skin's picture once, in the background, so the first ragdoll is not the placeholder. */
    private static void warmDefaults() {
        if (!DEFAULTS_WARMED.compareAndSet(false, true)) return;
        for (DefaultSkin skin : DefaultSkin.values()) {
            RagdollModel.ofTexture(skin.textures());
        }
    }

    /** Remembers a player's skin. Called by the library on the player's own thread, at join and a moment after. */
    @ApiStatus.Internal
    public static void remember(@NotNull Player player) {
        String[] skin = texturesOf(player);
        ONLINE.put(player.getUniqueId(), skin != null ? skin : new String[]{null, null});
        if (skin != null) RagdollModel.ofTexture(skin[0]);
    }

    /** Forgets a player who left. Called by the library. */
    @ApiStatus.Internal
    public static void forget(@NotNull UUID player) {
        ONLINE.remove(player);
    }

    private static String @Nullable [] texturesOf(Player player) {
        try {
            for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
                if ("textures".equals(property.getName()) && Textures.urlOf(property.getValue()) != null) {
                    return new String[]{property.getValue(), property.getSignature()};
                }
            }
        } catch (Throwable noProfileApi) {
            // Spigot: no profile properties to read, so this player counts as their UUID's default.
        }
        return null;
    }

    /**
     * One chosen skin, as the body that stands and the body that falls. Each call builds a fresh model
     * (an NPC under a new profile id), so the mannequin and an {@code [NPC]} line never share one.
     */
    public static final class Body {

        private final @Nullable String texture;
        private final @Nullable String signature;
        private final @Nullable DefaultSkin fallback;

        Body(@Nullable String texture, @Nullable String signature, @Nullable DefaultSkin fallback) {
            this.texture = texture;
            this.signature = signature;
            this.fallback = fallback;
        }

        /** The signed {@code textures} value, or {@code null} for a vanilla default. */
        public @Nullable String texture() {
            return texture;
        }

        /** The vanilla default it is, or {@code null} for a player's skin. */
        public @Nullable DefaultSkin defaultSkin() {
            return fallback;
        }

        /**
         * Whether its ragdoll would wear its real colours now. Its picture is read in the background the
         * first time; until then a body falls in the default colours, so a preview waits a moment for it.
         */
        public boolean ready() {
            return net.exylia.lib.ragdoll.internal.SkinCache.isRead(texture != null ? texture : fallback.textures());
        }

        /** A standing NPC in this skin, named {@code Body}. */
        public @NotNull NpcModel npc() {
            return texture != null ? NpcModel.of("Body", texture, signature) : NpcModel.of("Body", fallback);
        }

        /** A ragdoll in this skin: the same pixels, the same face. */
        public @NotNull RagdollModel ragdoll() {
            return RagdollModel.ofTexture(texture != null ? texture : fallback.textures());
        }
    }
}
