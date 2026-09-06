package net.exylia.lib.api.practice;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/**
 * One kit, as it was when you asked.
 *
 * <p>A snapshot: the plugin replaces its kit rows on every edit.
 *
 * <p>The items a kit hands out are not here. They are an inventory layout that
 * only means anything applied to a player, which is what
 * {@link PracticeService#applyKit} is for; a copy of it in a record would be
 * forty item stacks nobody asked for on every lookup.
 *
 * <p>The four mode flags are separate rather than a set because a server turns
 * them on and off independently: a kit can be duellable but not queueable while
 * an admin tunes it.
 *
 * @param id               the kit id, stable for the kit's whole life
 * @param displayName      the name menus show
 * @param description      the one-line description menus show, or empty
 * @param enabled          whether the kit may be played at all
 * @param ranked           whether matches on it move ELO
 * @param queueEnabled     whether it appears in matchmaking
 * @param duelEnabled      whether players may duel each other on it
 * @param partyEnabled     whether parties may fight on it, in any party mode
 * @param priority         the order menus list kits in, higher first
 * @param iconMaterial     the Bukkit material name menus draw it with
 * @param categories       the category ids this kit is grouped under
 * @param compatibleArenas the arena ids it may be played in, empty for any
 * @since 1.0.0
 */
public record Kit(
        @NotNull String id,
        @NotNull String displayName,
        @NotNull String description,
        boolean enabled,
        boolean ranked,
        boolean queueEnabled,
        boolean duelEnabled,
        boolean partyEnabled,
        int priority,
        @NotNull String iconMaterial,
        @NotNull @Unmodifiable List<String> categories,
        @NotNull @Unmodifiable List<String> compatibleArenas) {
}
