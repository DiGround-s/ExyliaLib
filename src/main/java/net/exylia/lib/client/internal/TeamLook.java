package net.exylia.lib.client.internal;

import net.exylia.lib.client.ClientTeam;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * How a team looks on a client that draws more than markers.
 *
 * <p>A snapshot taken when the team is drawn, so a link reads one consistent
 * picture even while the plugin is re-describing members on another thread.
 *
 * @param id         the team's id
 * @param name       the team's name, {@code null} when it was never styled
 * @param colour     the colour of the viewer's own group
 * @param allyColour the colour of every other group in the team
 * @param members    the group and rank of each member that was described
 * @param groupNames names given to single groups
 */
record TeamLook(UUID id, Component name, TextColor colour, TextColor allyColour,
                Map<UUID, Member> members, Map<String, Component> groupNames) {

    /** One member's group and rank. A {@code null} group is everyone's group. */
    record Member(String group, ClientTeam.Rank rank) {
    }

    /** Returns whether the team was styled, so a client should draw its panel. */
    boolean styled() {
        return name != null;
    }

    /** Returns the name {@code viewer} sees: their group's, else the team's. */
    Component nameFor(UUID viewer) {
        Member mine = members.get(viewer);
        Component own = mine == null || mine.group() == null ? null : groupNames.get(mine.group());
        return own != null ? own : name;
    }

    /** Returns whether {@code other} belongs to a different group than {@code viewer}. */
    boolean ally(UUID viewer, UUID other) {
        Member mine = members.get(viewer);
        Member theirs = members.get(other);
        return mine != null && theirs != null
                && mine.group() != null && theirs.group() != null
                && !Objects.equals(mine.group(), theirs.group());
    }

    /** Returns the colour {@code viewer} sees {@code other} in. */
    TextColor colourOf(UUID viewer, UUID other) {
        return ally(viewer, other) ? allyColour : colour;
    }

    /** Returns {@code other}'s rank, {@link ClientTeam.Rank#MEMBER} when undescribed. */
    ClientTeam.Rank rankOf(UUID other) {
        Member member = members.get(other);
        return member == null ? ClientTeam.Rank.MEMBER : member.rank();
    }
}
