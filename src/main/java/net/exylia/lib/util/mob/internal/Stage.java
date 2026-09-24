package net.exylia.lib.util.mob.internal;

import net.exylia.lib.display.Vfx;
import net.exylia.lib.display.VfxRun;
import net.exylia.lib.util.mob.MobSkill;
import net.exylia.lib.util.mob.MobTheme;
import net.exylia.lib.util.mob.MobVisuals;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One cast as it plays out: where it started, what it is aimed at, who is
 * watching, and what it has drawn so far.
 *
 * <p>Made as the cast starts, on the mob's thread, and read by the mechanics
 * too, which is why it exists even when nothing is drawn: a dash still needs
 * the direction it was locked to. What the mechanics find at impact — the
 * bodies reached, where a teleport landed, the minions that came — is written
 * back here for the impact to draw.
 *
 * <p>A preview is a stage with no mob: {@link #caster()} is {@code null}, and a
 * style draws the body it would have used where the caster would stand.
 */
final class Stage {

    /** A body's size when there is none to measure: a zombie's. */
    static final double WIDTH = 0.6;
    static final double HEIGHT = 1.95;

    final Plugin plugin;
    final MobSkill skill;
    final @Nullable MobStyle style;
    final MobAim.Lock lock;
    final @Nullable LivingEntity caster;
    final @Nullable LiveMob mob;
    final List<Player> viewers;
    final boolean lod;
    final double width;
    final double height;
    final List<Material> palette;
    final BlockData ground;
    final MobTheme theme;
    final MobVisuals visuals;
    final int tint;
    final boolean preview;

    /** Where summoned minions will stand, chosen as the wind-up starts so their portals open there. */
    final List<Location> spots = new ArrayList<>();

    private final List<VfxRun> runs = new ArrayList<>();
    private final List<LivingEntity> reached = new ArrayList<>();
    private @Nullable Location landed;
    private @Nullable Location left;
    private @Nullable Location arrived;
    private volatile long busy;

    Stage(Plugin plugin, MobSkill skill, @Nullable MobStyle style, MobAim.Lock lock, @Nullable LivingEntity caster,
          @Nullable LiveMob mob, List<Player> viewers, @Nullable Boolean lod, double width, double height,
          List<Material> palette, BlockData ground, MobTheme theme, MobVisuals visuals, boolean preview) {
        this.plugin = plugin;
        this.skill = skill;
        this.style = style;
        this.lock = lock;
        this.caster = caster;
        this.mob = mob;
        this.viewers = List.copyOf(viewers);
        this.lod = lod != null ? lod : viewers.size() > Vfx.LOD_VIEWERS;
        this.width = width;
        this.height = height;
        this.palette = palette;
        this.ground = ground;
        this.theme = theme;
        this.visuals = visuals;
        this.tint = Shapes.colour(skill.cast().tint());
        this.preview = preview;
    }

    /** Whether anything is drawn for this cast: it has a style and somebody to see it. */
    boolean drawn() {
        return style != null && !viewers.isEmpty();
    }

    @Nullable LivingEntity caster() {
        return caster;
    }

    /** Where the caster stood as the cast began. */
    Location origin() {
        return lock.origin().clone();
    }

    /** Where it was aimed as the cast began: its target's feet, or its own when it had none. */
    Location point() {
        Location point = lock.point();
        return point == null ? origin() : point.clone();
    }

    float yaw() {
        return lock.yaw();
    }

    long windup() {
        return skill.cast().windup().toMillis();
    }

    /** A colour by role; the skill's tint stands in for its style's main one. */
    int colour(MobTheme.Role role) {
        if (tint >= 0 && style != null && role == style.role) return tint;
        int themed = Shapes.colour(theme.of(role));
        if (themed >= 0) return themed;
        int fallback = Shapes.colour(MobTheme.DEFAULT.of(role));
        return fallback >= 0 ? fallback : 0xFFFFFF;
    }

    /** The style's main colour. */
    int main() {
        return colour(style == null ? MobTheme.Role.TELEGRAPH : style.role);
    }

    /** An effect at a place, seen by this cast's viewers at its level of detail. */
    Vfx vfx(Location at) {
        return Vfx.at(at).viewers(viewers).lod(lod);
    }

    /** How many beats a shake designed with {@code designed} plays here. */
    int shakes(int designed) {
        return visuals.shakes(designed);
    }

    /** Halves a count at low detail, never below {@code least}. */
    int count(int full, int least) {
        return lod ? Math.max(least, full / 2) : full;
    }

    // --------------------------------------------------------------- outcome

    synchronized void reached(List<? extends LivingEntity> bodies) {
        reached.addAll(bodies);
    }

    synchronized List<LivingEntity> reached() {
        return List.copyOf(reached);
    }

    /** Where the skill landed, when it landed somewhere of its own. */
    synchronized void landed(@NotNull Location at) {
        landed = at.clone();
    }

    synchronized Location landedOr(Location fallback) {
        return landed == null ? fallback : landed.clone();
    }

    /** A teleport: where it left and where it arrived. */
    synchronized void moved(@NotNull Location from, @NotNull Location to) {
        left = from.clone();
        arrived = to.clone();
    }

    synchronized @Nullable Location left() {
        return left == null ? null : left.clone();
    }

    synchronized @Nullable Location arrived() {
        return arrived == null ? null : arrived.clone();
    }

    /** A mechanic that keeps the mob busy past its impact, a dash: its recovery waits this many ticks more. */
    void busy(long ticks) {
        busy = Math.max(busy, ticks);
    }

    long busy() {
        return busy;
    }

    // ------------------------------------------------------------------ runs

    /** Keeps a run so a death can take it off every screen. */
    synchronized void own(VfxRun run) {
        runs.add(run);
    }

    /** Takes everything this cast drew off every screen: the mob died, or it was cut short. */
    void cancel() {
        List<VfxRun> all;
        synchronized (this) {
            all = List.copyOf(runs);
            runs.clear();
        }
        all.forEach(VfxRun::cancel);
    }
}
