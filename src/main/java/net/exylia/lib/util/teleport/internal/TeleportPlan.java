package net.exylia.lib.util.teleport.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.effect.EffectConfig;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.util.teleport.ExyliaLocation;
import net.exylia.lib.util.teleport.RandomArea;
import net.exylia.lib.util.teleport.TeleportCause;
import net.exylia.lib.util.teleport.TeleportResult;
import net.exylia.lib.util.teleport.TeleportSettings;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Everything a request decided, frozen at the moment it was started.
 *
 * <p>A carrier rather than a design: the builder lives in the public package
 * and the runtime lives here, so one of them has to hand the other a value.
 * Freezing it also means a builder that is reused after {@code start()} cannot
 * change a teleport that is already counting down.
 *
 * <h2>Three ways of saying where</h2>
 * Exactly one of {@code destination}, {@code random} and {@code crossServer} is
 * set. They are separate components rather than one field of a common type
 * because they are answered at different moments: a plain destination is known
 * before the countdown starts, a random one is searched for after it ends, and
 * a cross-server one is never a place on this server at all.
 *
 * @param plugin          who asked
 * @param player          who is being moved
 * @param destination     where to, before any safe search
 * @param random          the area to look in, for a random teleport
 * @param crossServer     the place on another server, for a handover
 * @param cause           why
 * @param tasks           the asking plugin's scheduler
 * @param debug           where failures are reported
 * @param settings        the owner's defaults, for the parts read while running
 * @param warmupTicks     how long the countdown lasts, in ticks
 * @param cancelOnMove    whether leaving the starting block calls it off
 * @param cancelOnDamage  whether being hit calls it off
 * @param safe            whether to look for somewhere safe to land
 * @param safeRadius      how far that search may look
 * @param safeAttempts    how many blocks that search may check
 * @param cooldownKey     the cooldown this teleport claimed, or {@code null}
 * @param onStart         played when the countdown begins
 * @param onArrive        played when the player lands
 * @param onCancel        played when it is called off
 * @param onTick          told how much countdown is left, for an action bar
 * @param bookkeeping     the module's own answer to how it ended, run before
 *                        the caller's and never replaced by it
 * @param then            told how it ended
 */
@ApiStatus.Internal
public record TeleportPlan(
        @NotNull Plugin plugin,
        @NotNull Player player,
        @Nullable Location destination,
        @Nullable RandomArea random,
        @Nullable ExyliaLocation crossServer,
        @Nullable UUID follow,
        @NotNull TeleportCause cause,
        @NotNull TaskScheduler tasks,
        @NotNull Debug debug,
        @NotNull TeleportSettings settings,
        long warmupTicks,
        boolean cancelOnMove,
        boolean cancelOnDamage,
        boolean safe,
        int safeRadius,
        int safeAttempts,
        @Nullable String cooldownKey,
        @Nullable EffectConfig onStart,
        @Nullable EffectConfig onArrive,
        @Nullable EffectConfig onCancel,
        @Nullable DoubleConsumer onTick,
        @Nullable Consumer<TeleportResult> bookkeeping,
        @Nullable Consumer<TeleportResult> then) {
}
