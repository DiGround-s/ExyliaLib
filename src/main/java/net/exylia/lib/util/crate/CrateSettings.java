package net.exylia.lib.util.crate;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Key;
import net.exylia.lib.effect.EffectConfig;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a plugin's crate behaves.
 *
 * <p>Nests inside the plugin's own configuration record like any other section:
 *
 * <pre>{@code
 * public record MySettings(CrateSettings crate) {
 *     public MySettings() {
 *         this(new CrateSettings());
 *     }
 *
 *     public MySettings withCrateBlocks(List<String> blocks) {
 *         return new MySettings(crate.withBlocks(blocks));
 *     }
 * }
 * }</pre>
 *
 * @param enabled         whether the crate can be opened at all
 * @param startKeys       keys a player is given the first time they are seen
 * @param duplicateRefund keys handed back when a crate lands on something already owned
 * @param reward          what a crate hands over
 * @param blocks          the blocks that open it, as {@code server,world,x,y,z,yaw,pitch}
 * @param maxAtOnce       how many crates one opening may spin, capped at seven
 * @param spinFrames      how many faces the first reel runs through
 * @param staggerSeconds  the gap between one reel landing and the next
 * @param onSpin          played as the reels turn
 * @param onWin           played as a reel lands on something new
 * @param onDuplicate     played as a reel lands on something already owned
 * @param keyItem         how a key that can be held is drawn
 * @param tiers           the rarities, by id
 * @since 1.189.0
 */
@Comment("The crate: one key in, one random reward out.")
@Comment("")
@Comment("Keys live on the player's account, where nothing can drop, dupe or be")
@Comment("lost to a full inventory. A key item that can be held opens it as well.")
public record CrateSettings(

        @Comment("Whether the crate can be opened at all. Off, it says so instead of opening.")
        boolean enabled,

        @Key("start-keys")
        @Comment("Keys a player is given the first time they are seen.")
        int startKeys,

        @Key("duplicate-refund")
        @Comment("Keys handed back when a crate lands on something the player already owns.")
        int duplicateRefund,

        @Comment("What a crate hands over when it lands on a reward.")
        @Comment("UNLOCK - it is unlocked on the account. It cannot be traded.")
        @Comment("ITEM   - only its token item. Nothing is unlocked, so no opening is")
        @Comment("         ever a duplicate and duplicate-refund never applies.")
        @Comment("BOTH   - unlocked on the account and handed over as a token as well.")
        @Comment("A plugin with no token items treats ITEM and BOTH as UNLOCK.")
        CrateReward reward,

        @Comment("The blocks that open the crate when they are clicked, as server,world,x,y,z,yaw,pitch.")
        @Comment("Bind them in game with this plugin's crate block admin command rather than by")
        @Comment("hand: a bound block cannot be broken, blown up or pushed away.")
        List<String> blocks,

        @Key("max-at-once")
        @Comment("How many crates a player may open in one go. The opening screen fits seven.")
        int maxAtOnce,

        @Key("spin-frames")
        @Comment("How many faces the first reel runs through before it stops. Higher is a longer fall.")
        @Comment("Capped at 200, which is about eleven seconds.")
        int spinFrames,

        @Key("stagger-seconds")
        @Comment("How long after one reel stops the next one does, in seconds, so they land one")
        @Comment("after another rather than all at once. 0 stops them together.")
        double staggerSeconds,

        @Key("on-spin")
        @Comment("Played as the reels turn.")
        EffectConfig onSpin,

        @Key("on-win")
        @Comment("Played the moment a reel stops on something new, once per crate.")
        EffectConfig onWin,

        @Key("on-duplicate")
        @Comment("Played instead when that reel stopped on something already owned.")
        EffectConfig onDuplicate,

        @Key("key-item")
        @Comment("How a crate key that can be held, traded and dropped is drawn.")
        KeyItem keyItem,

        @Comment("The rarities rewards are sorted into, and how often each one comes out.")
        @Comment("Add, rename or delete any of them: the key is the rarity id rewards name.")
        Map<String, CrateTier> tiers) {

    public CrateSettings() {
        this(true, 0, 1, CrateReward.UNLOCK, List.of(), 4, 34, 2.0,
                sound("BLOCK_NOTE_BLOCK_HAT", 1.6),
                sound("ENTITY_PLAYER_LEVELUP", 1.2),
                sound("BLOCK_NOTE_BLOCK_BASS", 0.8),
                new KeyItem(), CrateTier.defaults());
    }

    public CrateSettings {
        reward = reward == null ? CrateReward.UNLOCK : reward;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        onSpin = onSpin == null ? new EffectConfig() : onSpin;
        onWin = onWin == null ? new EffectConfig() : onWin;
        onDuplicate = onDuplicate == null ? new EffectConfig() : onDuplicate;
        keyItem = keyItem == null ? new KeyItem() : keyItem;
        tiers = tiers == null ? CrateTier.defaults() : Collections.unmodifiableMap(new LinkedHashMap<>(tiers));
    }

    /** The same crate opened by a different set of blocks, for the command that binds them. */
    public @NotNull CrateSettings withBlocks(@NotNull List<String> blocks) {
        return new CrateSettings(enabled, startKeys, duplicateRefund, reward, blocks, maxAtOnce,
                spinFrames, staggerSeconds, onSpin, onWin, onDuplicate, keyItem, tiers);
    }

    /**
     * How a crate key that can be held is drawn.
     *
     * @param material what it is drawn as
     * @param name     its name
     * @param lore     its lore
     * @param glow     whether it glows
     * @since 1.189.0
     */
    public record KeyItem(String material, String name, List<String> lore, boolean glow) {

        public KeyItem() {
            this("TRIPWIRE_HOOK", "{primary}&lCRATE KEY", List.of(
                    "{secondary}Information:",
                    " {letters_black}▎ {letters}Right click the crate holding this",
                    " {letters_black}▎ {letters}to {highlight}open it {letters}once.",
                    "",
                    "{secondary}Note:",
                    " {letters_black}▎ {letters}This one can be traded and dropped.",
                    "",
                    "{warning}➥ Right click the crate to open",
                    ""), true);
        }

        public KeyItem {
            material = material == null || material.isBlank() ? "TRIPWIRE_HOOK" : material;
            name = name == null ? "" : name;
            lore = lore == null ? List.of() : List.copyOf(lore);
        }
    }

    /** One sound and nothing else, which is what most of these moments are. */
    private static EffectConfig sound(String name, double pitch) {
        return new EffectConfig(new EffectConfig.Title(), new EffectConfig.ActionBar(), new EffectConfig.BossBar(),
                new EffectConfig.Sound(name, 1.0, pitch, "PLAYERS"), new EffectConfig.Particle(),
                new EffectConfig.Firework());
    }
}
