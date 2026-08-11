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

    private volatile boolean online = true;

    public FakePlayer(String name) {
        this.name = name;
        this.proxy = (Player) Proxy.newProxyInstance(
                FakePlayer.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (self, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> this.name;
                    case "isOnline", "isValid" -> online;
                    case "sendActionBar" -> {
                        actionBars.add(plain(args[0]));
                        yield null;
                    }
                    case "showTitle" -> {
                        titles.add(String.valueOf(args[0]));
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

    /** Every action bar this player received, in order. */
    public List<String> actionBars() {
        return new ArrayList<>(actionBars);
    }

    /** Every title event, in order. */
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

    /** Simulates the player leaving. */
    public void disconnect() {
        online = false;
    }

    /** Forgets everything recorded so far. */
    public void clear() {
        actionBars.clear();
        titles.clear();
        bossBarsShown.clear();
        bossBarsHidden.clear();
    }
}
