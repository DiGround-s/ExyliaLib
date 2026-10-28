package net.exylia.lib.guide.internal;

import net.exylia.lib.guide.BeaconStyle;
import net.exylia.lib.guide.GuideBeacon;
import net.exylia.lib.guide.GuideTextStyle;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.function.DoubleFunction;

/**
 * A beam over a target for one player, or a stand-in walking with them when
 * the target is too far for their client to draw.
 *
 * <h2>Why a stand-in</h2>
 * A client draws nothing past its view distance, and an entity at a target
 * five hundred blocks away is not drawn at all however tall it is. So past the
 * cut-over the beam is replaced by a shorter one a fixed distance from the
 * player in the target's direction, at eye height, moved as they move. It
 * glides there, so it reads as a marker on the horizon rather than as
 * something following them. The cut-over has a few blocks of slack each way
 * so walking along it does not flicker between the two.
 */
final class LiveBeacon extends Piece implements GuideBeacon {

    private static final double SLACK = 8;
    private static final double MARKER_HEIGHT = 3;
    private static final double MARKER_MOVE = 0.4;
    private static final int MARKER_GLIDE = 4;
    private static final GuideTextStyle LABEL = GuideTextStyle.anchored().background(0x60000000);

    private final BeaconStyle style;
    private final DoubleFunction<Component> label;
    private final BlockData core = Material.WHITE_CONCRETE.createBlockData();

    private volatile Location target;
    private volatile boolean retargeted;
    private volatile long pulseAsked = -1;
    private volatile State state = State.AT_TARGET;
    private volatile double distance = Double.NaN;

    private boolean drawn;
    private State drawnState;
    private int shell = -1;
    private int inner = -1;
    private int text = -1;
    private long shrinkAt = -1;
    private long rounded = Long.MIN_VALUE;
    private float labelScale;
    private double markerX;
    private double markerY;
    private double markerZ;

    LiveBeacon(ViewerSession session, Location target, BeaconStyle style, DoubleFunction<Component> label) {
        super(session);
        this.target = target.clone();
        this.style = style;
        this.label = label;
    }

    @Override
    public void retarget(@NotNull Location where) {
        this.target = where.clone();
        this.retargeted = true;
    }

    @Override
    public void pulse() {
        pulseAsked = 0;
    }

    @Override
    public @NotNull Location target() {
        return target.clone();
    }

    @Override
    public @NotNull State state() {
        return state;
    }

    @Override
    public double distance() {
        return distance;
    }

    @Override
    void draw(Player player, long tick) {
        Location goal = target;
        if (retargeted) {
            retargeted = false;
            clear(player);
        }
        if (!player.getWorld().equals(goal.getWorld())) {
            state = State.ELSEWHERE;
            distance = Double.NaN;
            clear(player);
            return;
        }
        Location eye = player.getEyeLocation();
        double gap = eye.distance(goal);
        distance = gap;
        double far = style.farDistance() > 0 ? style.farDistance()
                : GuideGeometry.farDistance(Math.min(player.getClientViewDistance(), player.getWorld().getViewDistance()));
        State next = switch (state) {
            case MARKER -> gap < far ? State.AT_TARGET : State.MARKER;
            case AT_TARGET -> gap > far + SLACK ? State.MARKER : State.AT_TARGET;
            case ELSEWHERE -> gap > far ? State.MARKER : State.AT_TARGET;
        };
        state = next;
        if (drawn && drawnState != next) {
            clear(player);
        }
        float viewRange = (float) Math.max(1.0, (far + 32) / 64.0);
        if (next == State.AT_TARGET) {
            if (!drawn) {
                spawn(player, goal.getX(), goal.getY(), goal.getZ(), style.height(), viewRange);
            }
            float scale = GuideGeometry.scaleFor(gap, 1f, 8f);
            if (Math.abs(scale - labelScale) > labelScale * 0.05f) {
                labelScale = scale;
                GuidePackets.resizeText(player, text, scale, 4);
            }
        } else {
            double[] at = GuideGeometry.marker(eye.getX(), eye.getY(), eye.getZ(),
                    goal.getX(), goal.getY(), goal.getZ(), style.markerDistance());
            double markerBase = at[1] - MARKER_HEIGHT / 2;
            if (!drawn) {
                spawn(player, at[0], markerBase, at[2], MARKER_HEIGHT, viewRange);
                GuidePackets.resizeText(player, text, labelScale = (float) (style.markerDistance() / 10.0), 0);
                markerX = at[0];
                markerY = markerBase;
                markerZ = at[2];
            } else {
                double dx = at[0] - markerX;
                double dy = markerBase - markerY;
                double dz = at[2] - markerZ;
                if (dx * dx + dy * dy + dz * dz > MARKER_MOVE * MARKER_MOVE) {
                    markerX = at[0];
                    markerY = markerBase;
                    markerZ = at[2];
                    GuidePackets.move(player, shell, markerX, markerY, markerZ, 0f);
                    GuidePackets.move(player, inner, markerX, markerY, markerZ, 0f);
                    GuidePackets.move(player, text, markerX, markerY + MARKER_HEIGHT + 0.6, markerZ, 0f);
                }
            }
        }
        long metres = Math.round(gap);
        if (label != null && metres != rounded) {
            rounded = metres;
            GuidePackets.text(player, text, label.apply(gap));
        }
        pulse(player, tick, next == State.AT_TARGET ? style.height() : MARKER_HEIGHT);
    }

    private void spawn(Player player, double x, double y, double z, double height, float viewRange) {
        float width = (float) style.width();
        boolean marker = height < style.height();
        int glide = marker ? MARKER_GLIDE : 0;
        shell = GuidePackets.newId();
        inner = GuidePackets.newId();
        text = GuidePackets.newId();
        GuidePackets.spawnBlock(player, shell, x, y, z, 0f, style.beam(), width, (float) height, width,
                style.colour(), viewRange, glide);
        GuidePackets.spawnBlock(player, inner, x, y, z, 0f, core, width * 0.35f, (float) height,
                width * 0.35f, -1, viewRange, glide);
        double labelY = marker ? y + height + 0.6 : y + style.labelHeight();
        labelScale = 1f;
        GuidePackets.spawnText(player, text, x, labelY, z, Component.empty(), LABEL, labelScale,
                GuidePackets.BILLBOARD_CENTER, glide, viewRange);
        rounded = Long.MIN_VALUE;
        drawn = true;
        drawnState = marker ? State.MARKER : State.AT_TARGET;
    }

    /** A swell over three ticks, and a settle over eight once it has peaked. */
    private void pulse(Player player, long tick, double height) {
        float width = (float) style.width();
        if (pulseAsked == 0) {
            pulseAsked = tick;
            shrinkAt = tick + 4;
            GuidePackets.resizeBlock(player, shell, width * 2.2f, (float) height, width * 2.2f, 3);
        } else if (shrinkAt >= 0 && tick >= shrinkAt) {
            shrinkAt = -1;
            GuidePackets.resizeBlock(player, shell, width, (float) height, width, 8);
        }
    }

    @Override
    void clear(Player player) {
        if (drawn) {
            GuidePackets.destroy(player, shell, inner, text);
        }
        drawn = false;
        shell = inner = text = -1;
    }

    @Override
    void forget() {
        drawn = false;
        shell = inner = text = -1;
    }
}
