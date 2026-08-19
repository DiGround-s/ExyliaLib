package net.exylia.lib.util.snapshot;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.UUID;

/**
 * A player with an inventory that can actually hold something.
 *
 * <p>Separate from the shared {@code FakePlayer}, which records what reached the
 * client. What a snapshot test needs is the opposite: a player whose inventory,
 * health, hunger and game mode can be read back after a restore, so a claim like
 * "only the chosen parts were touched" is provable rather than asserted about a
 * log line.
 *
 * <p>Everything is a dynamic proxy, as everywhere else in these tests: no
 * mocking framework, and nothing here needs a running server.
 */
final class SnapshotPlayer {

    private final UUID id = UUID.randomUUID();
    private final String name;
    private final Player proxy;

    private final ItemStack[] contents = new ItemStack[Snapshot.INVENTORY_SLOTS];
    private final ItemStack[] armor = new ItemStack[Snapshot.ARMOR_SLOTS];
    private final ItemStack[] enderChest = new ItemStack[27];
    private volatile ItemStack offHand;

    private volatile GameMode gameMode = GameMode.SURVIVAL;
    private volatile double health = 20.0d;
    private volatile int foodLevel = 20;
    private volatile float saturation = 5.0f;
    private volatile int level;
    private volatile float exp;
    private volatile boolean allowFlight;
    private volatile boolean flying;
    private volatile float flySpeed = 0.1f;
    private volatile int fireTicks;
    private volatile int remainingAir = 300;
    private volatile float walkSpeed = 0.2f;
    private volatile boolean invulnerable;
    private volatile org.bukkit.util.Vector velocity = new org.bukkit.util.Vector();
    private volatile Location location;
    private volatile boolean online = true;
    private volatile int inventoryUpdates;

    SnapshotPlayer(String name) {
        this.name = name;
        PlayerInventory inventory = newPlayerInventory();
        Inventory chest = newChest();
        this.proxy = (Player) Proxy.newProxyInstance(
                SnapshotPlayer.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (self, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> this.name;
                    case "isOnline", "isValid" -> online;
                    case "getLocation" -> location;
                    case "getWorld" -> location == null ? null : location.getWorld();
                    case "getInventory" -> inventory;
                    case "getEnderChest" -> chest;
                    case "getGameMode" -> gameMode;
                    case "setGameMode" -> {
                        gameMode = (GameMode) args[0];
                        yield null;
                    }
                    case "getHealth" -> health;
                    case "setHealth" -> {
                        health = (double) args[0];
                        yield null;
                    }
                    case "getFoodLevel" -> foodLevel;
                    case "setFoodLevel" -> {
                        foodLevel = (int) args[0];
                        yield null;
                    }
                    case "getSaturation" -> saturation;
                    case "setSaturation" -> {
                        saturation = (float) args[0];
                        yield null;
                    }
                    case "getLevel" -> level;
                    case "setLevel" -> {
                        level = (int) args[0];
                        yield null;
                    }
                    case "getExp" -> exp;
                    case "setExp" -> {
                        exp = (float) args[0];
                        yield null;
                    }
                    case "getAllowFlight" -> allowFlight;
                    case "setAllowFlight" -> {
                        allowFlight = (boolean) args[0];
                        yield null;
                    }
                    case "isFlying" -> flying;
                    case "setFlying" -> {
                        flying = (boolean) args[0];
                        yield null;
                    }
                    case "getFlySpeed" -> flySpeed;
                    case "setFlySpeed" -> {
                        flySpeed = (float) args[0];
                        yield null;
                    }
                    case "getFireTicks" -> fireTicks;
                    case "setFireTicks" -> {
                        fireTicks = (int) args[0];
                        yield null;
                    }
                    case "getRemainingAir" -> remainingAir;
                    case "setRemainingAir" -> {
                        remainingAir = (int) args[0];
                        yield null;
                    }
                    case "getWalkSpeed" -> walkSpeed;
                    case "setWalkSpeed" -> {
                        walkSpeed = (float) args[0];
                        yield null;
                    }
                    case "isInvulnerable" -> invulnerable;
                    case "setInvulnerable" -> {
                        invulnerable = (boolean) args[0];
                        yield null;
                    }
                    case "getVelocity" -> velocity.clone();
                    case "setVelocity" -> {
                        velocity = ((org.bukkit.util.Vector) args[0]).clone();
                        yield null;
                    }
                    // No attribute instance and no potion registry: neither can
                    // be built without a server. The module reports and skips
                    // both, which is what these tests then assert.
                    case "getAttribute" -> null;
                    case "getActivePotionEffects" -> java.util.List.of();
                    case "updateInventory" -> {
                        inventoryUpdates++;
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    case "toString" -> "SnapshotPlayer[" + this.name + ']';
                    default -> net.exylia.lib.FakeServer.defaultValue(method.getReturnType());
                });
    }

    private PlayerInventory newPlayerInventory() {
        return (PlayerInventory) Proxy.newProxyInstance(
                SnapshotPlayer.class.getClassLoader(),
                new Class<?>[]{PlayerInventory.class},
                (self, method, args) -> switch (method.getName()) {
                    case "getContents" -> contents.clone();
                    case "setContents" -> {
                        Arrays.fill(contents, null);
                        ItemStack[] given = (ItemStack[]) args[0];
                        System.arraycopy(given, 0, contents, 0,
                                Math.min(given.length, contents.length));
                        yield null;
                    }
                    case "getArmorContents" -> armor.clone();
                    case "setArmorContents" -> {
                        Arrays.fill(armor, null);
                        if (args[0] != null) {
                            ItemStack[] given = (ItemStack[]) args[0];
                            System.arraycopy(given, 0, armor, 0,
                                    Math.min(given.length, armor.length));
                        }
                        yield null;
                    }
                    case "getItemInOffHand" -> offHand == null ? new TestItem("AIR", 0) : offHand;
                    case "setItemInOffHand" -> {
                        offHand = (ItemStack) args[0];
                        yield null;
                    }
                    case "getSize" -> contents.length;
                    case "clear" -> {
                        if (args == null || args.length == 0) {
                            Arrays.fill(contents, null);
                        }
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    case "toString" -> "FakeInventory";
                    default -> net.exylia.lib.FakeServer.defaultValue(method.getReturnType());
                });
    }

    private Inventory newChest() {
        return (Inventory) Proxy.newProxyInstance(
                SnapshotPlayer.class.getClassLoader(),
                new Class<?>[]{Inventory.class},
                (self, method, args) -> switch (method.getName()) {
                    case "getContents" -> enderChest.clone();
                    case "setContents" -> {
                        Arrays.fill(enderChest, null);
                        ItemStack[] given = (ItemStack[]) args[0];
                        System.arraycopy(given, 0, enderChest, 0,
                                Math.min(given.length, enderChest.length));
                        yield null;
                    }
                    case "getSize" -> enderChest.length;
                    case "clear" -> {
                        if (args == null || args.length == 0) {
                            Arrays.fill(enderChest, null);
                        }
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    case "toString" -> "FakeEnderChest";
                    default -> net.exylia.lib.FakeServer.defaultValue(method.getReturnType());
                });
    }

    // ------------------------------------------------------------- the player

    Player player() {
        return proxy;
    }

    UUID id() {
        return id;
    }

    SnapshotPlayer at(Location where) {
        this.location = where.clone();
        return this;
    }

    void disconnect() {
        online = false;
    }

    // ----------------------------------------------------------- what they had

    SnapshotPlayer holding(int slot, ItemStack item) {
        contents[slot] = item;
        return this;
    }

    SnapshotPlayer wearing(int slot, ItemStack item) {
        armor[slot] = item;
        return this;
    }

    SnapshotPlayer offHand(ItemStack item) {
        this.offHand = item;
        return this;
    }

    SnapshotPlayer inEnderChest(int slot, ItemStack item) {
        enderChest[slot] = item;
        return this;
    }

    SnapshotPlayer health(double value) {
        this.health = value;
        return this;
    }

    SnapshotPlayer hunger(int food, float saturationValue) {
        this.foodLevel = food;
        this.saturation = saturationValue;
        return this;
    }

    SnapshotPlayer experience(int levelValue, float progress) {
        this.level = levelValue;
        this.exp = progress;
        return this;
    }

    SnapshotPlayer mode(GameMode value) {
        this.gameMode = value;
        return this;
    }

    SnapshotPlayer burning(int ticks) {
        this.fireTicks = ticks;
        return this;
    }

    ItemStack[] contents() {
        return contents.clone();
    }

    ItemStack[] armor() {
        return armor.clone();
    }

    ItemStack[] enderChest() {
        return enderChest.clone();
    }

    ItemStack offHandItem() {
        return offHand;
    }

    boolean inventoryIsEmpty() {
        for (ItemStack item : contents) {
            if (item != null) {
                return false;
            }
        }
        for (ItemStack item : armor) {
            if (item != null) {
                return false;
            }
        }
        return offHand == null;
    }

    double health() {
        return health;
    }

    int foodLevel() {
        return foodLevel;
    }

    float saturation() {
        return saturation;
    }

    int level() {
        return level;
    }

    float exp() {
        return exp;
    }

    GameMode mode() {
        return gameMode;
    }

    int fireTicks() {
        return fireTicks;
    }

    int inventoryUpdates() {
        return inventoryUpdates;
    }
}
