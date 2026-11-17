package net.exylia.lib.downed;

/**
 * The boss bar lines of {@link Downed}. {@code %time%} is the time left, {@code %player%} who is
 * reviving or being revived.
 *
 * @param downed    the downed player's bar while nobody helps
 * @param reviving  the bar while a teammate revives, seen by both
 * @param revived   told to the revived player
 * @param reviver   told to whoever revived them
 * @since 1.621.0
 */
public record DownedMessages(String downed, String reviving, String revived, String reviver) {

    /** English defaults. */
    public DownedMessages() {
        this("{error}✚ Downed {letters_black}» {letters}a teammate can revive you {letters_black}» {info}%time% ⌚",
                "{success}✚ Reviving {highlight}%player% {letters_black}» {letters}keep sneaking",
                "[sound:ENTITY_EXPERIENCE_ORB_PICKUP|0.6|1.2]{success}✚ {highlight}%player% {letters}revived you.",
                "[sound:ENTITY_EXPERIENCE_ORB_PICKUP|0.6|1.2]{success}✚ {letters}You revived {highlight}%player%{letters}.");
    }
}
