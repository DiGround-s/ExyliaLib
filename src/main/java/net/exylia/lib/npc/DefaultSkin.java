package net.exylia.lib.npc;

import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Vanilla's eighteen default skins: nine characters, slim and wide.
 *
 * <p>A client draws a profile that carries no texture in the default skin its UUID hashes to,
 * {@code DEFAULT_SKINS[floorMod(uuid.hashCode(), 18)]} since 1.19.3, in exactly this order. So a body
 * announced under {@link #uuid()} and no texture shows the real default skin: nothing to sign, nothing
 * to download, nothing a modern client can reject.
 *
 * <pre>{@code
 * NpcModel zuri = NpcModel.of("Body", DefaultSkin.ZURI_WIDE);
 * DefaultSkin seen = DefaultSkin.of(player.getUniqueId()); // what an offline-mode player looks like
 * }</pre>
 *
 * <p>{@link #textures()} is the same skin as an unsigned texture value pointing at Mojang's own copy,
 * for what draws a skin from its pixels (a ragdoll, a head) rather than from the client's assets.
 *
 * @since 1.416.0
 */
public enum DefaultSkin {

    ALEX_SLIM("46acd06e8483b176e8ea39fc12fe105eb3a2a4970f5100057e9d84d4b60bdfa7"),
    ARI_SLIM("6ac6ca262d67bcfb3dbc924ba8215a18195497c780058a5749de674217721892"),
    EFE_SLIM("fece7017b1bb13926d1158864b283b8b930271f80a90482f174cca6a17e88236"),
    KAI_SLIM("226c617fde5b1ba569aa08bd2cb6fd84c93337532a872b3eb7bf66bdd5b395f8"),
    MAKENA_SLIM("7cb3ba52ddd5cc82c0b050c3f920f87da36add80165846f479079663805433db"),
    NOOR_SLIM("6c160fbd16adbc4bff2409e70180d911002aebcfa811eb6ec3d1040761aea6dd"),
    STEVE_SLIM("d5c4ee5ce20aed9e33e866c66caa37178606234b3721084bf01d13320fb2eb3f"),
    SUNNY_SLIM("b66bc80f002b10371e2fa23de6f230dd5e2f3affc2e15786f65bc9be4c6eb71a"),
    ZURI_SLIM("eee522611005acf256dbd152e992c60c0bb7978cb0f3127807700e478ad97664"),
    ALEX_WIDE("1abc803022d8300ab7578b189294cce39622d9a404cdc00d3feacfdf45be6981"),
    ARI_WIDE("4c05ab9e07b3505dc3ec11370c3bdce5570ad2fb2b562e9b9dd9cf271f81aa44"),
    EFE_WIDE("daf3d88ccb38f11f74814e92053d92f7728ddb1a7955652a60e30cb27ae6659f"),
    KAI_WIDE("e5cdc3243b2153ab28a159861be643a4fc1e3c17d291cdd3e57a7f370ad676f3"),
    MAKENA_WIDE("dc0fcfaf2aa040a83dc0de4e56058d1bbb2ea40157501f3e7d15dc245e493095"),
    NOOR_WIDE("90e75cd429ba6331cd210b9bd19399527ee3bab467b5a9f61cb8a27b177f6789"),
    STEVE_WIDE("31f477eb1a7beee631c2ca64d06f8f68fa93a3386d04452ab27f43acdf1b60cb"),
    SUNNY_WIDE("a3bd16079f764cd541e072e888fe43885e711f98658323db0f9a6045da91ee7a"),
    ZURI_WIDE("f5dddb41dcafef616e959c2817808e0be741c89ffbfed39134a13e75b811863d");

    private static final DefaultSkin[] ALL = values();

    private final String textureId;

    DefaultSkin(String textureId) {
        this.textureId = textureId;
    }

    /** The default skin a client draws {@code id} in when its profile carries no texture. */
    public static @NotNull DefaultSkin of(@NotNull UUID id) {
        return ALL[Math.floorMod(id.hashCode(), ALL.length)];
    }

    /** Whether it is the slim (three-pixel arm) model. */
    public boolean slim() {
        return ordinal() < ALL.length / 2;
    }

    /** Mojang's id for its picture on textures.minecraft.net. */
    public @NotNull String textureId() {
        return textureId;
    }

    /**
     * A fresh random UUID the client draws in this skin. Fresh on every call, so two bodies wearing the
     * same default never share a profile.
     */
    public @NotNull UUID uuid() {
        UUID random = UUID.randomUUID();
        long least = random.getLeastSignificantBits() & ~0xFFL;
        // The low byte only reaches the hash's low byte: 256 neighbours cover all eighteen remainders.
        for (long low = 0; low < 256; low++) {
            UUID candidate = new UUID(random.getMostSignificantBits(), least | low);
            if (of(candidate) == this) return candidate;
        }
        throw new AssertionError("unreachable: 256 consecutive hashes cover every remainder");
    }

    /** The skin as an unsigned base64 {@code textures} value, slim model marked. */
    public @NotNull String textures() {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + textureId + "\""
                + (slim() ? ",\"metadata\":{\"model\":\"slim\"}" : "") + "}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
