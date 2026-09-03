package net.exylia.lib.session;

import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;

/** A player that answers nothing; the rules under test never ask it anything. */
final class FakePlayer {

    private FakePlayer() {
    }

    static Player any() {
        return (Player) Proxy.newProxyInstance(FakePlayer.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> null);
    }
}
