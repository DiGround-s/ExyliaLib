package net.exylia.lib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A player that records what was shown to it.
 *
 * <p>Effects are only observable through what reaches the client, so a test
 * asserts on this rather than on internal state. Recording the plain text of
 * each component is enough to prove a countdown is really counting.
 */
public final class FakePlayer {

    private final UUID id = UUID.randomUUID();
    private final String name;
    private final Player proxy;

    private final List<String> actionBars = new CopyOnWriteArrayList<>();
    private final List<String> titles = new CopyOnWriteArrayList<>();
    private final List<String> bossBarsShown = new CopyOnWriteArrayList<>();
    private final List<String> bossBarsHidden = new CopyOnWriteArrayList<>();

    private final List<Component> actionBarComponents = new ArrayList<>();
    private final List<String> titleParts = new CopyOnWriteArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private final List<String> sounds = new ArrayList<>();
    private final List<String> commands = new CopyOnWriteArrayList<>();
    private volatile boolean acceptsCommands = true;
    private volatile boolean online = true;
    private final java.util.Set<String> permissions = new java.util.concurrent.CopyOnWriteArraySet<>();
    private final java.util.concurrent.atomic.AtomicInteger experience =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile Object inventory;
    private volatile org.bukkit.Location location;
    private final List<org.bukkit.Location> teleports = new CopyOnWriteArrayList<>();
    private final List<String> hidden = new CopyOnWriteArrayList<>();
    private final List<String> pluginMessages = new CopyOnWriteArrayList<>();
    private volatile boolean allowFlight;
    private volatile boolean flying;
    private volatile boolean invulnerable;
    private volatile boolean gravity = true;

    public FakePlayer(String name) {
        this.name = name;
        this.proxy = (Player) Proxy.newProxyInstance(
                FakePlayer.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (self, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getLocation" -> location;
                    case "getWorld" -> location == null ? null : location.getWorld();
                    case "getName" -> this.name;
                    case "isOnline", "isValid" -> online;
                    case "hasPermission" -> permissions.contains(String.valueOf(args[0]));
                    case "getInventory" -> inventory;
                    case "giveExp" -> {
                        experience.addAndGet((int) args[0]);
                        yield null;
                    }
                    case "getTotalExperience" -> experience.get();
                    case "performCommand" -> {
                        commands.add(String.valueOf(args[0]));
                        yield acceptsCommands;
                    }
                    case "playSound" -> {
                        // (location, sound, volume, pitch)
                        sounds.add(String.valueOf(args[1]));
                        yield null;
                    }
                    case "sendMessage" -> {
                        messages.add(plain(args[0]));
                        yield null;
                    }
                    case "sendActionBar" -> {
                        actionBars.add(plain(args[0]));
                        if (args[0] instanceof Component component) {
                            actionBarComponents.add(component);
                        }
                        yield null;
                    }
                    case "showTitle" -> {
                        titles.add(String.valueOf(args[0]));
                        yield null;
                    }
                    // Paper's part-wise title, which is how a countdown replaces
                    // its text without restarting the fade. Recorded separately
                    // from showTitle so a test can tell the two apart: a redraw
                    // that arrives as a whole Title is the pulsing bug.
                    case "sendTitlePart" -> {
                        titleParts.add(String.valueOf(args[0]) + "=" + plain(args[1]));
                        yield null;
                    }
                    case "clearTitle" -> {
                        titles.add("<cleared>");
                        yield null;
                    }
                    case "showBossBar" -> {
                        bossBarsShown.add(String.valueOf(args[0]));
                        yield null;
                    }
                    case "hideBossBar" -> {
                        bossBarsHidden.add(String.valueOf(args[0]));
                        yield null;
                    }
                    case "teleport" -> {
                        if (args[0] instanceof org.bukkit.Location where) {
                            teleports.add(where.clone());
                            this.location = where.clone();
                        }
                        yield true;
                    }
                    // Paper's asynchronous teleport, which is the one the
                    // teleport module uses. Recorded the same way, so a test
                    // asserts on where the player ended up rather than on which
                    // overload moved them.
                    case "teleportAsync" -> {
                        if (args[0] instanceof org.bukkit.Location where) {
                            teleports.add(where.clone());
                            this.location = where.clone();
                        }
                        yield java.util.concurrent.CompletableFuture.completedFuture(true);
                    }
                    case "setAllowFlight" -> {
                        allowFlight = (boolean) args[0];
                        yield null;
                    }
                    case "getAllowFlight" -> allowFlight;
                    case "setFlying" -> {
                        flying = (boolean) args[0];
                        yield null;
                    }
                    case "isFlying" -> flying;
                    case "setInvulnerable" -> {
                        invulnerable = (boolean) args[0];
                        yield null;
                    }
                    case "isInvulnerable" -> invulnerable;
                    case "setGravity" -> {
                        gravity = (boolean) args[0];
                        yield null;
                    }
                    case "hasGravity" -> gravity;
                    case "hideEntity", "hidePlayer" -> {
                        hidden.add(String.valueOf(args[1]));
                        yield null;
                    }
                    case "showEntity", "showPlayer" -> {
                        hidden.remove(String.valueOf(args[1]));
                        yield null;
                    }
                    // How a proxy is told to move a player between servers.
                    // Recorded by channel so a test can assert not only that
                    // one was sent but exactly when, which is the whole
                    // contract of a cross-server handover.
                    case "sendPluginMessage" -> {
                        pluginMessages.add(String.valueOf(args[1]));
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    case "toString" -> "FakePlayer[" + this.name + "]";
                    default -> FakeServer.defaultValue(method.getReturnType());
                });
    }

    private static String plain(Object component) {
        if (component instanceof Component text) {
            return PlainTextComponentSerializer.plainText().serialize(text);
        }
        return String.valueOf(component);
    }

    /** The player to hand to the code under test. */
    public Player player() {
        return proxy;
    }

    /** Every sound played to this player, in order. */
    /** Everywhere this player was teleported, in order. */
    public List<org.bukkit.Location> teleports() {
        return List.copyOf(teleports);
    }

    /** Every plugin-message channel this player was sent something on. */
    public List<String> pluginMessages() {
        return List.copyOf(pluginMessages);
    }

    /** What is currently hidden from this player. */
    public List<String> hidden() {
        return List.copyOf(hidden);
    }

    /** Whether this player is currently held in the air. */
    public boolean isFrozen() {
        return allowFlight && flying && !gravity;
    }

    /** Whether this player is currently invulnerable. */
    public boolean isInvulnerable() {
        return invulnerable;
    }

    /** Every command this player was made to run, in order. */
    public List<String> commands() {
        return List.copyOf(commands);
    }

    /** Makes the player's commands report failure, as an unknown one would. */
    public FakePlayer rejectsCommands() {
        this.acceptsCommands = false;
        return this;
    }

    public List<String> sounds() {
        return new ArrayList<>(sounds);
    }

    /** Every chat message this player received, in order. */
    public List<String> messages() {
        return new ArrayList<>(messages);
    }

    /** Every action bar this player received, in order. */
    public List<String> actionBars() {
        return new ArrayList<>(actionBars);
    }

    /** The same, unserialized, for tests that care about colour. */
    public List<Component> actionBarComponents() {
        return new ArrayList<>(actionBarComponents);
    }

    /** Every title event, in order. */
    /** The title parts sent, as {@code PART=text}. */
    public List<String> titleParts() {
        return new ArrayList<>(titleParts);
    }

    public List<String> titles() {
        return new ArrayList<>(titles);
    }

    /** How many boss bars were shown through the Bukkit path. */
    public int bossBarsShown() {
        return bossBarsShown.size();
    }

    /** How many boss bars were hidden through the Bukkit path. */
    public int bossBarsHidden() {
        return bossBarsHidden.size();
    }

    /** Puts the player somewhere, which is what makes distance checks work. */
    public FakePlayer at(org.bukkit.Location where) {
        this.location = where.clone();
        return this;
    }

    /**
     * Gives this player an inventory.
     *
     * <p>Untyped because only a test that cares about inventories provides one,
     * and building a real {@code PlayerInventory} needs a running server.
     *
     * @param stub something implementing {@code PlayerInventory}
     */
    public FakePlayer inventory(Object stub) {
        this.inventory = stub;
        return this;
    }

    /** Gives this player a permission. */
    public FakePlayer grant(String permission) {
        permissions.add(permission);
        return this;
    }

    /** How much experience this player was granted. */
    public int experience() {
        return experience.get();
    }

    /** Simulates the player leaving. */
    public void disconnect() {
        online = false;
    }

    /** Forgets everything recorded so far. */
    public void clear() {
        actionBars.clear();
        actionBarComponents.clear();
        titleParts.clear();
        messages.clear();
        sounds.clear();
        titles.clear();
        bossBarsShown.clear();
        bossBarsHidden.clear();
    }
}
