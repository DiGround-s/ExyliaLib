package net.exylia.lib.objectives;

/**
 * Something a player does that a quest, mission or journey step can count.
 *
 * <p>What {@link ObjectiveProgress#thing()} names for each type is written on
 * the constant.
 *
 * @since 1.620.0
 */
public enum ObjectiveType {
    /** Broke a block; the thing is its material. Fired at {@code HIGHEST}, so {@link ObjectiveProgress#placedByPlayer()} can still be read. */
    BREAK_BLOCK,
    /** Placed a block; the thing is its material. One spot counts once per player per session. */
    PLACE_BLOCK,
    /** Right-clicked a block with the main hand and was not denied; the thing is its material. */
    INTERACT_BLOCK,
    /** Crafted an item; the thing is the result's material, the amount the recipe's result amount. */
    CRAFT,
    /** Took smelted items out of a furnace; the thing is their material. */
    SMELT,
    /** Enchanted an item at a table; the thing is the item's material. */
    ENCHANT,
    /** Caught an item fishing; the thing is its material. */
    FISH,
    /** Picked up an item nobody dropped; the thing is its material. */
    PICKUP_ITEM,
    /** Ate or drank an item; the thing is its material. */
    CONSUME,
    /** Killed a mob; the thing is its entity type, {@link ObjectiveProgress#origin()} where it came from. */
    KILL_MOB,
    /** Killed another player; the thing is {@code PLAYER}, {@link ObjectiveProgress#victim()} who. */
    KILL_PLAYER,
    /** Walked into a library region; the thing is the region id ({@code namespace:value}). */
    REACH,
    /** Clicked a placed NPC (any plugin's); the thing is the NPC id. */
    INTERACT_NPC,
    /** Ran a command; the thing is the line as typed, slash included. */
    RUN_COMMAND,
    /** Wrote in chat; the thing is the plain message. */
    CHAT,
    /** Put on a piece of armour; the thing is its material. */
    EQUIP,
    /** Gained experience levels; the thing is the new level, the amount how many were gained. */
    LEVEL,
    /** Fired by a plugin through {@link Objectives#fire}; see {@link ObjectiveProgress#customType()}. */
    CUSTOM
}
