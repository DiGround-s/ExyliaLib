package net.exylia.lib.economy;

/**
 * Where a currency's balances live, which decides what can be done with them.
 *
 * @since 1.228.0
 */
public enum CurrencyKind {

    /** Kept in a database by the plugin that registered it: read and paid offline and across servers. */
    STORED,

    /** An item in the player's inventory: read and paid only while they are online. */
    ITEM,

    /** Experience levels or points: read and paid only while they are online. */
    EXPERIENCE,

    /** Another plugin's economy, such as Vault or PlayerPoints. */
    EXTERNAL,

    /** Nothing is registered under that id. */
    UNKNOWN
}
