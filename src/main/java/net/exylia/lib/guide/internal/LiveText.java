package net.exylia.lib.guide.internal;

import net.exylia.lib.guide.GuideText;
import net.exylia.lib.guide.GuideTextStyle;
import net.exylia.lib.util.combat.Combat;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * A text one player sees: ahead of their eyes (a companion) or at a place.
 *
 * <h2>A companion glides, it does not stick</h2>
 * Pinned to the view every tick, a panel is a HUD element that shakes with
 * every mouse movement. This one is moved only when the view has turned or
 * the player has walked far enough to matter, and the client is told to take a
 * few ticks to get there, so it eases after the view the way a companion in a
 * game does. It is one teleport per real movement, not one per tick.
 *
 * <h2>It steps aside</h2>
 * A panel floating in front of somebody sprint-jumping, gliding or fighting is
 * in the way. Above the style's speed, while gliding, or while in combat, it
 * is taken away, and it comes back after a second of calm.
 */
final class LiveText extends Piece implements GuideText {

    /** How long a companion glides to a new place, in ticks. */
    private static final int GLIDE_TICKS = 6;
    /** How far the view must turn, in degrees, before a companion follows it. */
    private static final double TURN_DEGREES = 6;
    /** How far the panel's spot must move, in blocks, before it follows. */
    private static final double MOVE_BLOCKS = 0.3;
    /** How long a companion waits after the player calms down, in ticks. */
    private static final long CALM_TICKS = 20;

    private final GuideTextStyle style;
    private final Location anchor;

    private volatile Component text;
    private Component sent;
    private int id = -1;
    private double lastX;
    private double lastY;
    private double lastZ;
    private float lastYaw;
    private float lastPitch;
    private float sentScale;

    private double seenX = Double.NaN;
    private double seenY;
    private double seenZ;
    private long calmSince = -CALM_TICKS;

    LiveText(ViewerSession session, Component text, GuideTextStyle style, Location anchor) {
        super(session);
        this.text = text;
        this.style = style;
        this.anchor = anchor == null ? null : anchor.clone();
    }

    @Override
    public void text(@NotNull Component replacement) {
        this.text = replacement;
    }

    @Override
    public @NotNull Component text() {
        return text;
    }

    @Override
    void draw(Player player, long tick) {
        if (anchor == null) {
            drawCompanion(player, tick);
        } else {
            drawAnchored(player);
        }
        if (id >= 0 && !text.equals(sent)) {
            sent = text;
            GuidePackets.text(player, id, sent);
        }
    }

    private void drawAnchored(Player player) {
        if (!player.getWorld().equals(anchor.getWorld())) {
            hide(player);
            return;
        }
        float scale = style.scaleWithDistance()
                ? GuideGeometry.scaleFor(player.getEyeLocation().distance(anchor), style.scale(), style.maxScale())
                : style.scale();
        if (id < 0) {
            id = GuidePackets.newId();
            sent = text;
            sentScale = scale;
            GuidePackets.spawnText(player, id, anchor.getX(), anchor.getY(), anchor.getZ(), sent, style, scale,
                    GuidePackets.BILLBOARD_CENTER, 0, Math.max(1f, style.maxScale()));
            return;
        }
        if (Math.abs(scale - sentScale) > sentScale * 0.05f) {
            sentScale = scale;
            GuidePackets.resizeText(player, id, scale, 4);
        }
    }

    private void drawCompanion(Player player, long tick) {
        Location eye = player.getEyeLocation();
        boolean fast = isBusy(player, eye, tick);
        seenX = eye.getX();
        seenY = eye.getY();
        seenZ = eye.getZ();
        if (fast) {
            calmSince = tick;
            hide(player);
            return;
        }
        if (tick - calmSince < CALM_TICKS) {
            return;
        }
        double[] at = GuideGeometry.ahead(eye.getX(), eye.getY(), eye.getZ(), eye.getYaw(), eye.getPitch(),
                style.ahead(), style.drop());
        if (id < 0) {
            id = GuidePackets.newId();
            sent = text;
            GuidePackets.spawnText(player, id, at[0], at[1], at[2], sent, style, style.scale(),
                    GuidePackets.BILLBOARD_CENTER, GLIDE_TICKS, 1f);
            remember(at, eye);
            return;
        }
        double dx = at[0] - lastX;
        double dy = at[1] - lastY;
        double dz = at[2] - lastZ;
        boolean moved = dx * dx + dy * dy + dz * dz > MOVE_BLOCKS * MOVE_BLOCKS;
        boolean turned = GuideGeometry.turn(lastYaw, eye.getYaw()) > TURN_DEGREES
                || Math.abs(lastPitch - eye.getPitch()) > TURN_DEGREES;
        if (moved || turned) {
            GuidePackets.move(player, id, at[0], at[1], at[2], 0f);
            remember(at, eye);
        }
    }

    /** Whether the player is doing something a panel in their face would be in the way of. */
    private boolean isBusy(Player player, Location eye, long tick) {
        if (player.isGliding() || player.isDead()) {
            return true;
        }
        if (style.hideAboveSpeed() > 0 && !Double.isNaN(seenX)) {
            double dx = eye.getX() - seenX;
            double dy = eye.getY() - seenY;
            double dz = eye.getZ() - seenZ;
            // Measured from where the eye was a tick of ours ago, not from the
            // velocity the server reports, which is zero for a player walking.
            double perSecond = Math.sqrt(dx * dx + dy * dy + dz * dz) * 10.0;
            if (perSecond > style.hideAboveSpeed() && perSecond < 200) {
                return true;
            }
        }
        if (style.hideInCombat()) {
            try {
                return Combat.isTagged(player);
            } catch (Throwable unavailable) {
                return false;
            }
        }
        return false;
    }

    private void remember(double[] at, Location eye) {
        lastX = at[0];
        lastY = at[1];
        lastZ = at[2];
        lastYaw = eye.getYaw();
        lastPitch = eye.getPitch();
    }

    private void hide(Player player) {
        if (id >= 0) {
            GuidePackets.destroy(player, id);
            id = -1;
        }
    }

    @Override
    void clear(Player player) {
        hide(player);
    }

    @Override
    void forget() {
        id = -1;
        seenX = Double.NaN;
    }
}
