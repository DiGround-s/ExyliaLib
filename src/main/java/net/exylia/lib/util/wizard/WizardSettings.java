package net.exylia.lib.util.wizard;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Key;

/**
 * How a plugin's guided flows behave.
 *
 * <p>Nests inside a plugin's own configuration record like any other section, so
 * a server owner can make the whole thing more patient without a code change:
 *
 * <pre>{@code
 * public record MySettings(WizardSettings wizard) {
 *     public MySettings() {
 *         this(new WizardSettings());
 *     }
 * }
 * }</pre>
 *
 * <h2>Why the run has a timeout of its own</h2>
 * Every question already times out on its own. That is not enough: a player who
 * answers one question every fifty seconds never trips any single timeout and
 * can keep a flow &mdash; and the block selector, and the boss bar, and one slot
 * of the one-wizard-per-player rule &mdash; open indefinitely. The run timeout
 * bounds the whole thing.
 *
 * <h2>Where the wording lives</h2>
 * The four gesture prompts are here, in the library, rather than in each
 * plugin's own {@code messages.yml}. Standing somewhere, clicking a block,
 * selecting a box and holding an item are the library's gestures, performed
 * with the library's selector and answered by the library's listener; a plugin
 * that had to word them was copying the same four lines and could word them
 * wrong &mdash; which is how prompts telling admins to hold a wooden axe
 * outlived the golden one the library actually hands out.
 *
 * @param timeoutSeconds how long the whole run may last
 * @param maxRedos       how many times the summary may be sent back for edits
 * @param progress       whether a boss bar shows how far along the player is
 * @param progressText   what that bar says
 * @param announce       whether a title names each gesture step
 * @param announceTitle  the big line of that title
 * @param announceSubtitle the smaller line under it
 * @param announceSeconds how long it stays
 * @param standPrompt    what a player is told when asked to stand somewhere
 * @param pointPrompt    what a player is told when asked to click a block
 * @param regionPrompt   what a player is told when asked to select a box
 * @param itemPrompt     what a player is told when asked to hold an item
 *
 * @since 1.34.0
 */
@Comment("How guided step-by-step flows behave.")
@Comment("")
@Comment("A wizard walks a player through several questions in a row and")
@Comment("applies nothing until they confirm the summary at the end.")
public record WizardSettings(

        @Key("timeout-seconds")
        @Comment("How long the whole flow may last, in seconds.")
        @Comment("Each question has its own shorter limit; this one bounds the")
        @Comment("total, so somebody who answers slowly forever still lets go")
        @Comment("of the flow eventually.")
        int timeoutSeconds,

        @Key("max-redos")
        @Comment("How many times the review screen may be sent back to change")
        @Comment("an answer before the flow gives up. A player going round this")
        @Comment("many times is no longer answering the question.")
        int maxRedos,

        @Comment("Whether a boss bar shows how far through the flow the player")
        @Comment("is. Turn it off if something else already owns that bar.")
        boolean progress,

        @Key("progress-text")
        @Comment("What the progress bar says. %step% is the question they are")
        @Comment("on, %steps% is how many there are, and %title% is the name of")
        @Comment("the flow.")
        String progressText,

        @Comment("Whether a title announces each step the player answers with a")
        @Comment("gesture — standing somewhere, clicking a block, selecting an")
        @Comment("area, holding an item. A question asked in a dialog or an")
        @Comment("anvil already carries its own prompt and is never announced.")
        boolean announce,

        @Key("announce-title")
        @Comment("The big line. %title% is the name of the flow, %step% and")
        @Comment("%steps% count the questions.")
        String announceTitle,

        @Key("announce-subtitle")
        @Comment("The smaller line under it. %prompt% is what the step asks.")
        String announceSubtitle,

        @Key("announce-seconds")
        @Comment("How long that title stays on screen.")
        double announceSeconds,

        @Key("stand-prompt")
        @Comment("What a player is told when a flow asks them to stand")
        @Comment("somewhere. The library's own wording, used by every plugin,")
        @Comment("so the gesture is described the same way everywhere.")
        String standPrompt,

        @Key("point-prompt")
        @Comment("What a player is told when a flow asks them to click a")
        @Comment("block.")
        String pointPrompt,

        @Key("region-prompt")
        @Comment("What a player is told when a flow asks them to select an")
        @Comment("area with the selector the library hands them.")
        String regionPrompt,

        @Key("item-prompt")
        @Comment("What a player is told when a flow asks them to hold an")
        @Comment("item.")
        String itemPrompt
) {

    /** What a flow says when nothing overrides it. */
    public static final String DEFAULT_STAND_PROMPT =
            "{warning}➥ {letters}Stand where you want it and {highlight}sneak + click{letters}.";

    /** What a flow says when it wants a block clicked. */
    public static final String DEFAULT_POINT_PROMPT =
            "{warning}➥ {letters}Left-click the block you want.";

    /** What a flow says when it wants an area selected. */
    public static final String DEFAULT_REGION_PROMPT =
            "{warning}➥ {letters}Left-click one corner and right-click the other, then "
                    + "{highlight}shift + left-click {letters}to confirm.";

    /** What a flow says when it wants an item held. */
    public static final String DEFAULT_ITEM_PROMPT =
            "{warning}➥ {letters}Hold the item you want, then confirm.";

    /** The Exylia defaults: five minutes, three redos, a bar that names the step. */
    public WizardSettings() {
        this(300, 3, true, "{primary}%title% {muted}(%step%/%steps%)",
                true, "{primary}&l%title%", "{letters}%prompt%", 2.5,
                DEFAULT_STAND_PROMPT, DEFAULT_POINT_PROMPT, DEFAULT_REGION_PROMPT,
                DEFAULT_ITEM_PROMPT);
    }

    /**
     * The settings a file written before titles existed describes.
     *
     * <p>Kept so a plugin that constructs these in code, and every test that
     * does, does not have to name four values it never had an opinion about.
     * The announcement takes its defaults.
     *
     * @param timeoutSeconds how long the whole flow may last
     * @param maxRedos       how many times the review may be sent back
     * @param progress       whether a boss bar shows the step count
     * @param progressText   what that bar says
     */
    public WizardSettings(int timeoutSeconds, int maxRedos, boolean progress, String progressText) {
        this(timeoutSeconds, maxRedos, progress, progressText,
                true, "{primary}&l%title%", "{letters}%prompt%", 2.5,
                DEFAULT_STAND_PROMPT, DEFAULT_POINT_PROMPT, DEFAULT_REGION_PROMPT,
                DEFAULT_ITEM_PROMPT);
    }

    /**
     * The settings a file written before the prompts moved here describes.
     *
     * @param timeoutSeconds   how long the whole flow may last
     * @param maxRedos         how many times the review may be sent back
     * @param progress         whether a boss bar shows the step count
     * @param progressText     what that bar says
     * @param announce         whether a title names each gesture step
     * @param announceTitle    the big line
     * @param announceSubtitle the smaller line
     * @param announceSeconds  how long it stays
     */
    public WizardSettings(int timeoutSeconds, int maxRedos, boolean progress, String progressText,
                          boolean announce, String announceTitle, String announceSubtitle,
                          double announceSeconds) {
        this(timeoutSeconds, maxRedos, progress, progressText, announce, announceTitle,
                announceSubtitle, announceSeconds, DEFAULT_STAND_PROMPT, DEFAULT_POINT_PROMPT,
                DEFAULT_REGION_PROMPT, DEFAULT_ITEM_PROMPT);
    }

    public WizardSettings {
        if (announceTitle == null) {
            announceTitle = "";
        }
        if (announceSubtitle == null) {
            announceSubtitle = "";
        }
        if (announceSeconds <= 0) {
            announceSeconds = 2.5;
        }
        // A run shorter than a single question's default timeout would end the
        // flow while the player is still looking at the first prompt.
        timeoutSeconds = Math.max(30, timeoutSeconds);
        // Zero is meaningful: a summary that may be denied but never edited is
        // still a summary, and denying it simply cancels.
        maxRedos = Math.clamp(maxRedos, 0, 20);
        if (progressText == null || progressText.isBlank()) {
            progressText = "{primary}%title% {muted}(%step%/%steps%)";
        }
        // A blank prompt is not an opinion, it is a deleted line: a gesture step
        // with nothing on screen is the bug this file exists to prevent.
        standPrompt = orDefault(standPrompt, DEFAULT_STAND_PROMPT);
        pointPrompt = orDefault(pointPrompt, DEFAULT_POINT_PROMPT);
        regionPrompt = orDefault(regionPrompt, DEFAULT_REGION_PROMPT);
        itemPrompt = orDefault(itemPrompt, DEFAULT_ITEM_PROMPT);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
