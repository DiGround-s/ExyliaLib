package net.exylia.lib.internal;

import net.exylia.lib.party.Parties;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import revxrsal.commands.Lamp;
import revxrsal.commands.annotation.CommandPlaceholder;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Single;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.orphan.OrphanCommand;
import revxrsal.commands.orphan.Orphans;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * {@code /party}, under the names the library's config gives it. Installed the first time a plugin
 * calls {@link Parties#use}; a name another plugin already owns is left to it.
 */
public final class PartyCommand implements OrphanCommand {

    /** Registers the command with the library's Lamp, skipping names already taken. */
    static void install(Lamp<BukkitCommandActor> lamp, Logger log) {
        LibrarySettings.Parties settings = LibrarySettings.get().parties();
        if (settings == null || !settings.command().enabled()) return;
        List<String> names = new ArrayList<>();
        names.add(settings.command().name());
        if (settings.command().aliases() != null) names.addAll(settings.command().aliases());
        names.removeIf(name -> name == null || name.isBlank() || Bukkit.getCommandMap().getCommand(name) != null);
        if (names.isEmpty()) {
            log.warning("Parties: /" + settings.command().name() + " and its aliases belong to another plugin; "
                    + "give parties.command.name a free name in config.yml.");
            return;
        }
        lamp.register(Orphans.path(names.toArray(String[]::new)).handler(new PartyCommand()));
    }

    @CommandPlaceholder
    public void info(Player player) {
        Parties.info(player);
    }

    @Subcommand("invite")
    public void invite(Player player, Player target) {
        Parties.invite(player, target);
    }

    @Subcommand("accept")
    public void acceptLatest(Player player) {
        Parties.accept(player, null);
    }

    @Subcommand("accept")
    public void accept(Player player, @Single String leader) {
        Parties.accept(player, leader);
    }

    @Subcommand("deny")
    public void denyLatest(Player player) {
        Parties.decline(player, null);
    }

    @Subcommand("deny")
    public void deny(Player player, @Single String leader) {
        Parties.decline(player, leader);
    }

    @Subcommand("leave")
    public void leave(Player player) {
        Parties.leave(player);
    }

    @Subcommand("kick")
    public void kick(Player player, @Single String member) {
        Parties.kick(player, member);
    }

    @Subcommand("leader")
    public void leader(Player player, @Single String member) {
        Parties.promote(player, member);
    }

    @Subcommand("disband")
    public void disband(Player player) {
        Parties.disband(player);
    }

    @Subcommand("chat")
    public void chat(Player player, @Optional String message) {
        Parties.chat(player, message);
    }

    @Subcommand("ready")
    public void ready(Player player) {
        Parties.vote(player, true);
    }

    @Subcommand("notready")
    public void notReady(Player player) {
        Parties.vote(player, false);
    }
}
