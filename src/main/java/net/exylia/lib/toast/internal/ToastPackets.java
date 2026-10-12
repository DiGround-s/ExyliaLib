package net.exylia.lib.toast.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.advancements.Advancement;
import com.github.retrooper.packetevents.protocol.advancements.AdvancementDisplay;
import com.github.retrooper.packetevents.protocol.advancements.AdvancementHolder;
import com.github.retrooper.packetevents.protocol.advancements.AdvancementProgress;
import com.github.retrooper.packetevents.protocol.advancements.AdvancementType;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAdvancements;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The two advancement packets behind a toast.
 *
 * <p>The only class behind {@code Toasts} that names PacketEvents, so a server
 * without it never loads this one.
 *
 * <p>The client pops a toast when an advancement it knows becomes done. So a
 * root advancement nobody else has is added already done, and removed in the
 * very next packet: the toast keeps its own copy and plays out, the
 * advancement screen never had time to list it, and the server never knew it
 * existed, so nothing is saved and nothing is announced in chat.
 */
@ApiStatus.Internal
public final class ToastPackets {

    private static final String CRITERION = "shown";
    private static final AtomicLong NEXT = new AtomicLong();

    private ToastPackets() {
    }

    /**
     * Pops one toast on one client.
     *
     * @param frame {@code TASK}, {@code GOAL} or {@code CHALLENGE}
     */
    public static void send(Player player, ItemStack icon, Component title, String frame) {
        ResourceLocation id = new ResourceLocation("exylia", "toast/" + NEXT.incrementAndGet());
        AdvancementDisplay display = new AdvancementDisplay(title, Component.empty(),
                SpigotConversionUtil.fromBukkitItemStack(icon), AdvancementType.valueOf(frame),
                null, true, true, 0f, 0f);
        Advancement advancement = new Advancement(null, display, List.of(CRITERION),
                List.of(List.of(CRITERION)), false);
        AdvancementProgress done = new AdvancementProgress(Map.of(CRITERION,
                new AdvancementProgress.CriterionProgress(System.currentTimeMillis())));
        var players = PacketEvents.getAPI().getPlayerManager();
        players.sendPacket(player, new WrapperPlayServerUpdateAdvancements(false,
                List.of(new AdvancementHolder(id, advancement)), Set.of(), Map.of(id, done), true));
        players.sendPacket(player, new WrapperPlayServerUpdateAdvancements(false,
                List.of(), Set.of(id), Map.of(), false));
    }
}
