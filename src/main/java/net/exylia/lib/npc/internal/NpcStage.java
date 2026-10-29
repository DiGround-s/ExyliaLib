package net.exylia.lib.npc.internal;

import net.exylia.lib.npc.NpcPerformance;
import net.exylia.lib.npc.NpcSpec;
import net.exylia.lib.ragdoll.RagdollModel;
import net.exylia.lib.ragdoll.internal.SkinCache;
import net.exylia.lib.util.sequence.PluginSequences;
import net.exylia.lib.util.sequence.Sequence;
import net.exylia.lib.util.sequence.SequenceRun;
import net.exylia.lib.util.sequence.SequenceTarget;
import net.exylia.lib.util.sequence.Sequences;
import net.exylia.lib.util.sequence.internal.SequenceAccess;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Where a placed NPC's performance is played: a seam, so the NPC's own bookkeeping
 * (hide, one at a time, reveal) is tested without a sequence engine behind it.
 */
interface NpcStage {

    /** A body standing in for an NPC. */
    interface Act {

        /** When the body stands in its last pose, from the start; -1 when it loops. */
        long standsAtMillis();

        /** Ends it: stands back up over {@code settleMillis}, or goes at once for 0. */
        void stop(long settleMillis);

        /** The tempo it plays at, rolled or handed over; {@code 1} is as written. */
        default double tempo() {
            return 1.0;
        }

        /**
         * How long one cycle takes at {@link #tempo()}: the loop's cycle when it loops,
         * otherwise the moment it stands in its last pose. What NPCs on one clock line
         * their starts up on; {@code 0} when it is not known.
         */
        default long cycleMillis() {
            return Math.max(0L, standsAtMillis());
        }
    }

    /**
     * Starts a body at {@code at} for {@code audience}.
     *
     * @param texture the skin's base64 value, or {@code null} for the default skin
     * @param tempo   the tempo to play at, shared by NPCs on one clock; {@code 0} or less
     *                rolls one from the lines' own range
     * @return the act, or {@code null} when it draws no body
     */
    @Nullable Act start(Plugin owner, NpcPerformance performance, Location at, List<Player> audience,
                        NpcSpec spec, @Nullable String texture, double tempo);

    /** The real stage: the performance's lines as a sequence, every body wearing the NPC. */
    NpcStage SEQUENCES = (owner, performance, at, audience, spec, texture, tempo) -> {
        PluginSequences sequences = Sequences.of(owner);
        Sequence sequence = performance.compiled(owner.getName(),
                lines -> sequences.compile(lines, "an NPC performance"));
        long stands = SequenceAccess.bodyMillis(sequence);
        if (stands == 0L) {
            // Nothing to stand in for the NPC, and hiding it would leave a hole.
            return null;
        }
        RagdollModel model = (texture != null ? RagdollModel.ofTexture(texture)
                : RagdollModel.of(SkinCache.fallback(), new ItemStack(Material.PLAYER_HEAD)))
                .scale(spec.scale())
                .glow(spec.glow())
                .carrying(spec.equipment().get(EquipmentSlot.HAND),
                        spec.equipment().get(EquipmentSlot.OFF_HAND),
                        spec.equipment().get(EquipmentSlot.HEAD));
        Set<UUID> allowed = new HashSet<>();
        double furthest = 0.0;
        for (Player viewer : audience) {
            allowed.add(viewer.getUniqueId());
            furthest = Math.max(furthest, viewer.getLocation().distance(at));
        }
        double played = tempo > 0.0 ? tempo
                : sequence.tempoTo() > sequence.tempoFrom()
                        ? ThreadLocalRandom.current().nextDouble(sequence.tempoFrom(), sequence.tempoTo())
                        : sequence.tempoFrom();
        long cycle = Math.round(SequenceAccess.cycleMillis(sequence) / Math.max(0.1, played));
        SequenceRun run = sequences.play(sequence, SequenceTarget.at(at)
                .wearing(model)
                .within(furthest + 8.0)
                .visibleTo((observer, source) -> allowed.contains(observer.getUniqueId())));
        // Overrides the run's own roll before its first step reads it: the steps start
        // on the location's thread on a later tick, never inside play().
        run.tempo(played);
        return new Act() {
            @Override
            public long standsAtMillis() {
                return stands;
            }

            @Override
            public void stop(long settleMillis) {
                if (settleMillis > 0L) {
                    run.settle(settleMillis);
                } else {
                    run.cancel();
                }
            }

            @Override
            public double tempo() {
                return played;
            }

            @Override
            public long cycleMillis() {
                return cycle;
            }
        };
    };
}
