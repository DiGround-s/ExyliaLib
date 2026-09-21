package net.exylia.lib.util.showcase;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a plugin's showcase does with its turn.
 *
 * <pre>{@code
 * ShowcaseAct act = stage -> {
 *     EffectDefinition effect = stage.pick(registry.all(), EffectDefinition::id);
 *     if (effect == null) return null;
 *     SequenceRun run = sequences.play(effect.sequence(),
 *             SequenceTarget.at(stage.where()).onlyTo(stage.watching()));
 *     return ShowcaseTurn.of(effect.sequence().durationMillis(), run::cancel);
 * };
 * }</pre>
 *
 * <p>It is the only part that differs from plugin to plugin. Where showcases
 * stand, who is close enough, when a turn may start and what to cancel when it
 * is over belong to {@link PluginShowcases}.
 *
 * @since 1.188.0
 */
@FunctionalInterface
public interface ShowcaseAct {

    /**
     * Starts one turn.
     *
     * <p>Called on the showcase's own region, once the previous turn and its
     * rest are over and only while somebody is watching. It must not block:
     * anything slow is read ahead of time, and a turn that has nothing to show
     * yet returns {@code null} and is asked again a second later.
     *
     * @param stage where it plays and who is watching
     * @return the turn, or {@code null} when there is nothing to show
     */
    @Nullable ShowcaseTurn play(@NotNull ShowcaseStage stage);
}
