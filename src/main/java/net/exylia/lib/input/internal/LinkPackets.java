package net.exylia.lib.input.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.chat.clickevent.ClickEvent;
import com.github.retrooper.packetevents.protocol.chat.clickevent.CopyToClipboardClickEvent;
import com.github.retrooper.packetevents.protocol.chat.clickevent.OpenUrlClickEvent;
import com.github.retrooper.packetevents.protocol.chat.clickevent.RunCommandClickEvent;
import com.github.retrooper.packetevents.protocol.chat.clickevent.SuggestCommandClickEvent;
import com.github.retrooper.packetevents.protocol.dialog.CommonDialogData;
import com.github.retrooper.packetevents.protocol.dialog.DialogAction;
import com.github.retrooper.packetevents.protocol.dialog.MultiActionDialog;
import com.github.retrooper.packetevents.protocol.dialog.action.StaticAction;
import com.github.retrooper.packetevents.protocol.dialog.body.DialogBody;
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessage;
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessageDialogBody;
import com.github.retrooper.packetevents.protocol.dialog.button.ActionButton;
import com.github.retrooper.packetevents.protocol.dialog.button.CommonButtonData;
import com.github.retrooper.packetevents.protocol.player.User;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A dialog of links: one button per link, each the click event itself, so the client acts on it alone (an
 * address goes through Minecraft's own "open this page?" screen) and the server keeps no state. Every
 * PacketEvents type of the links is confined here.
 */
final class LinkPackets {

    private static final int BUTTON_WIDTH = 200;
    private static final int BODY_WIDTH = 300;

    private LinkPackets() {
    }

    static boolean show(Player player, Component title, @Nullable Component body, List<DialogTransport.Link> links) {
        try {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user == null || !DialogPackets.reaches(player)) return false;
            List<ActionButton> buttons = new ArrayList<>(links.size());
            for (DialogTransport.Link link : links) {
                buttons.add(new ActionButton(new CommonButtonData(link.label(), link.tooltip(), BUTTON_WIDTH),
                        new StaticAction(click(link))));
            }
            List<DialogBody> lines = body == null ? List.of() : List.of(new PlainMessageDialogBody(new PlainMessage(body, BODY_WIDTH)));
            ActionButton close = new ActionButton(new CommonButtonData(Text.component(Phrases.tr("Close")), null, BUTTON_WIDTH), null);
            DialogPackets.send(user, new MultiActionDialog(new CommonDialogData(title, null, true, false,
                    DialogAction.CLOSE, lines, List.of()), buttons, close, 1));
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    private static ClickEvent click(DialogTransport.Link link) {
        return switch (link.kind()) {
            case OPEN_URL -> new OpenUrlClickEvent(link.value());
            case COPY -> new CopyToClipboardClickEvent(link.value());
            case RUN_COMMAND -> new RunCommandClickEvent(link.value());
            case SUGGEST_COMMAND -> new SuggestCommandClickEvent(link.value());
        };
    }
}
