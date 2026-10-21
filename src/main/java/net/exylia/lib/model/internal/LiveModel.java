package net.exylia.lib.model.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.display.internal.DisplayRuntime;
import net.exylia.lib.display.internal.DisplaySink;
import net.exylia.lib.model.Model;
import net.exylia.lib.model.ModelInstance;
import net.exylia.lib.model.ModelOptions;
import net.exylia.lib.model.ModelState;
import net.exylia.lib.model.ModelTweaks;
import net.exylia.lib.ragdoll.internal.BlockPalette;
import net.exylia.lib.ragdoll.internal.RagdollTextures;
import net.exylia.lib.ragdoll.internal.SkinCubes;
import net.exylia.lib.ragdoll.internal.SkinTextureQueue;
import net.exylia.lib.skull.internal.HeadFactory;
import net.exylia.lib.skull.internal.Textures;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * One model on one carrier, drawn as packets.
 *
 * <h2>How it is drawn</h2>
 * Every cube is a display riding one invisible root display, and the root is
 * teleported after the carrier with a glide the client draws itself: a model
 * walking across a field costs one small packet a tick, however many cubes it
 * has. A cube's pose is sent only when it changes, over as many ticks as the
 * next one is away, so the client draws every frame in between.
 *
 * <h2>Three distances</h2>
 * Within 16 blocks a viewer is sent every pose every other tick; within 32,
 * every fourth; beyond, every eighth and only the larger cubes. When the server
 * nears its ceiling every viewer is drawn one step further away.
 *
 * <h2>Threads</h2>
 * Everything runs on the carrier's thread, and on the dying place's once it has
 * let go of the carrier. What other threads ask for is queued and done on the
 * next tick.
 */
@ApiStatus.Internal
final class LiveModel implements ModelInstance {

    /** Models that let go of their carrier and are dying where it stood. */
    static final Set<LiveModel> DETACHED = ConcurrentHashMap.newKeySet();

    static final int NEAR = 0;
    static final int MID = 1;
    static final int FAR = 2;
    /** Ticks between poses at each distance. */
    static final int[] PERIOD = {2, 4, 8};
    static final double NEAR_RANGE = ModelRuntime.NEAR_RANGE;
    static final double MID_RANGE = ModelRuntime.MID_RANGE;
    /** How often who is watching is read again. */
    private static final int RETRACK_TICKS = 5;
    /** How long a block of ground read under a foot is believed, in ticks. */
    private static final int GROUND_TICKS = 10;
    /** How much further than its view range a viewer may walk before the model is taken off them. */
    static final double KEEP_MARGIN = 8;
    /** How often a textured head is looked for again. */
    private static final int HEADS_TICKS = 40;
    /** A model whose ticks stopped this long ago is gone: its carrier went a way nothing told. */
    private static final long STALE_MS = 3_000;
    /** How long a model takes to shrink away. */
    private static final long FADE_MS = 300;
    /** How long its cubes take to fly, land and melt when it comes apart. */
    private static final long APART_MS = 1_400;

    /** The turn a head item needs before it is posed, as ragdolls turn theirs. */
    private static final Rotation ITEM_FACING = Rotation.around(Rotation.Axis.Y, Math.PI);
    /** How far a head item sits below its centre, per block of its size. */
    private static final float HEAD_LIFT = 0.25f;

    private static final DisplayModel ROOT = DisplayModel.item(new ItemStack(Material.AIR));
    private static final DisplayKeyframe STILL = new DisplayKeyframe(0, 0f, 0f, 0f, Rotation.NONE, 1f, 1f, 1f);

    /** One head per texture, as ragdolls keep theirs. */
    private static final Cache<String, ItemStack> HEADS = Caffeine.newBuilder()
            .maximumSize(5_000).expireAfterAccess(Duration.ofMinutes(30)).build();

    private final String owner;
    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final Model model;
    private final ModelOptions options;
    private final Rig rig;
    private final Poser poser;
    private final Controller controller;
    private final DisplaySink sink;

    private volatile @Nullable LivingEntity carrier;
    private final int carrierId;
    private final UUID carrierUuid;
    private final boolean wasInvisible;

    private final int rootId;
    /** The root every cube rides, casting the model's shadow: an invisible carrier casts none. */
    private final DisplayModel root;
    private final int nametagId;
    private final int[] ids;
    private final DisplayModel[] drawn;
    private final boolean[] major;
    private final boolean[] waitingHead;
    private final double[][] offsets;
    private final double[][] poses;
    private final float[][][] sent = new float[3][][];
    private final int head;
    /** Per cube, what a tweak draws it with instead of its own look, or {@code null}. */
    private final DisplayModel[] tweaked;
    private final Legs legs;
    private final double[] offset;
    /** Blocks of ground read this pose and the last few, by column and height. */
    private final Map<Long, Double> grounds = new HashMap<>();

    /**
     * Who watches at each distance. Copy-on-write: written when somebody comes
     * or goes, read every tick, and read by a plugin being disabled on
     * another thread while the carrier's thread may still be writing.
     */
    @SuppressWarnings("unchecked")
    private final List<Player>[] tiers = new List[]{new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(),
            new CopyOnWriteArrayList<>()};
    private final Map<UUID, Integer> tierOf = new ConcurrentHashMap<>();
    private int cost;

    private final int[] boxIds;
    private final double[] boxAlong;
    private final boolean boxesAlongForward;
    private float boxWidth;
    private float boxHeight;
    /** What the carrier was grown to when the boxes were sized: they grow from there with it and the model. */
    private final double boxBase;
    private double boxSent = 1;
    /** Who last hit a box, on which tick: one hit a tick, however many packets a client sends. */
    private final Map<UUID, Long> lastHit = new ConcurrentHashMap<>();
    /** Projectiles already handed to the carrier, so one is not handed twice. */
    private final Set<Integer> caught = java.util.Collections.newSetFromMap(new java.util.LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) {
            return size() > 64;
        }
    });

    private final ConcurrentLinkedQueue<Runnable> asked = new ConcurrentLinkedQueue<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    private @Nullable World world;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private double speed;
    private double carrierScale = 1;
    private double headYaw;
    private double headPitch;
    private @Nullable Component name;
    private long ticks;
    private boolean posed;
    private long flashUntil;
    private boolean flashing;
    private boolean anyWaitingHead;
    private boolean fading;
    private boolean onGround = true;
    /** Per viewer, the distance they were last drawn at before the ceiling pushed them out. */
    private final Map<UUID, Integer> distanceOf = new ConcurrentHashMap<>();
    private volatile int phase = 1;
    private double size = 1;
    private double sizeWanted = 1;
    private int glow = -1;
    private final double[] push = new double[3];

    private @Nullable TaskHandle timer;
    private volatile long lastTick = System.currentTimeMillis();
    private volatile boolean removed;
    private boolean detached;
    private long endsAt = Long.MAX_VALUE;
    private boolean apartAtEnd;

    LiveModel(Plugin plugin, LivingEntity carrier, Model model, ModelOptions options, DisplaySink sink) {
        this.owner = plugin.getName();
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
        this.model = model;
        this.options = options;
        this.rig = model.rig();
        this.poser = new Poser(rig);
        this.controller = new Controller(rig.bones().size(), state -> model.clipFor(state, options, phase),
                state -> options.tweaks().speed(state));
        this.sink = sink;
        this.carrier = carrier;
        this.carrierId = carrier.getEntityId();
        this.carrierUuid = carrier.getUniqueId();
        this.wasInvisible = carrier.isInvisible();

        int count = rig.parts().size();
        this.rootId = DisplayRuntime.nextEntityId();
        AttributeInstance grown = scaleOf(carrier);
        this.carrierScale = grown == null ? 1 : grown.getValue();
        this.boxBase = carrierScale;
        // How big it is drawn: its own size, times what the carrier was grown to.
        double asDrawn = options.scale() * carrierScale;
        double footprint = Math.max(rig.max()[0] - rig.min()[0], rig.max()[2] - rig.min()[2]) / 16.0 * asDrawn;
        this.root = ROOT.shadow((float) Math.clamp(footprint * 0.45, 0.3, 4), 0.8f);
        this.nametagId = options.nametag() ? DisplayRuntime.nextEntityId() : 0;
        this.ids = new int[count];
        this.drawn = new DisplayModel[count];
        this.major = new boolean[count];
        this.waitingHead = new boolean[count];
        this.offsets = new double[rig.bones().size()][Clip.CHANNELS];
        this.poses = new double[count][Poser.FIELDS];
        for (int tier = 0; tier < 3; tier++) {
            sent[tier] = new float[count][];
        }
        this.head = options.lookAt() ? rig.head() : -1;
        ModelTweaks tweaks = options.tweaks();
        this.offset = new double[]{tweaks.offsetX(), tweaks.offsetY(), tweaks.offsetZ()};
        this.tweaked = new DisplayModel[count];
        this.legs = Legs.of(rig, poser);

        // What a tweak does to a bone it does to every bone below it that says nothing of its own.
        List<Rig.Bone> boneList = rig.bones();
        ModelTweaks.Bone[] byBone = new ModelTweaks.Bone[boneList.size()];
        for (int bone = 0; bone < boneList.size(); bone++) {
            ModelTweaks.Bone own = tweaks.bone(boneList.get(bone).name());
            int parent = boneList.get(bone).parent();
            byBone[bone] = own != null ? own : parent >= 0 ? byBone[parent] : null;
        }

        Integer[] byVolume = new Integer[count];
        for (int index = 0; index < count; index++) {
            byVolume[index] = index;
            Look look = rig.parts().get(index).look();
            ModelTweaks.Bone tweak = byBone[rig.parts().get(index).bone()];
            if (look.kind() == Look.Kind.HIDDEN || tweak != null && tweak.hidden()) {
                continue;
            }
            ids[index] = DisplayRuntime.nextEntityId();
            tweaked[index] = tweak == null ? null : material(tweak.material());
            drawn[index] = tweaked[index] != null ? tweaked[index] : drawnFor(look, carrier);
            waitingHead[index] = tweaked[index] == null && look.kind() == Look.Kind.HEAD && options.heads()
                    && drawn[index].kind() == DisplayModel.Kind.BLOCK;
            anyWaitingHead |= waitingHead[index];
        }
        // The larger cubes are what a model looks like from afar.
        Arrays.sort(byVolume, Comparator.comparingDouble(index -> -rig.parts().get(index).volume()));
        int keep = Math.max(6, (count + 2) / 3);
        for (int rank = 0; rank < count && rank < keep; rank++) {
            major[byVolume[rank]] = true;
        }
        if (anyWaitingHead) {
            askForHeads();
        }

        // Boxes of its own when it is bigger than what carries it.
        double width = rig.width() * asDrawn;
        double height = rig.height() * asDrawn;
        double spanX = (rig.max()[0] - rig.min()[0]) / 16.0 * asDrawn;
        double spanZ = (rig.max()[2] - rig.min()[2]) / 16.0 * asDrawn;
        boolean bigger = width > carrier.getWidth() * 1.25 || height > carrier.getHeight() * 1.25;
        if (options.hitboxes() && tweaks.hasHitbox()) {
            // Sized by hand: one box, centred on the model.
            boxesAlongForward = true;
            boxIds = new int[]{DisplayRuntime.nextEntityId()};
            boxAlong = new double[]{(rig.max()[2] + rig.min()[2]) / 32.0 * asDrawn};
            ModelRuntime.box(boxIds[0], this);
            boxWidth = (float) tweaks.hitboxWidth();
            boxHeight = (float) tweaks.hitboxHeight();
        } else if (options.hitboxes() && bigger) {
            boxesAlongForward = spanZ >= spanX;
            double longest = Math.max(spanX, spanZ);
            double shortest = Math.max(0.4, Math.min(spanX, spanZ));
            int count2 = (int) Math.clamp(Math.ceil(longest / shortest - 0.25), 1, 4);
            boxIds = new int[count2];
            boxAlong = new double[count2];
            double centre = boxesAlongForward ? (rig.max()[2] + rig.min()[2]) / 32.0 * asDrawn
                    : (rig.max()[0] + rig.min()[0]) / 32.0 * asDrawn;
            for (int box = 0; box < count2; box++) {
                boxIds[box] = DisplayRuntime.nextEntityId();
                boxAlong[box] = centre - longest / 2 + longest * (box + 0.5) / count2;
                ModelRuntime.box(boxIds[box], this);
            }
            boxWidth = (float) Math.max(shortest, longest / count2);
            boxHeight = (float) height;
        } else {
            boxesAlongForward = true;
            boxIds = new int[0];
            boxAlong = new double[0];
        }
    }

    // ------------------------------------------------------------------ lifecycle

    void start() {
        LivingEntity body = carrier;
        if (body == null) {
            return;
        }
        read(body);
        body.setInvisible(true);
        controller.play(ModelState.SPAWN, System.currentTimeMillis());
        timer = tasks.runAtEntityTimer(body, 1L, 1L, this::tick);
    }

    String owner() {
        return owner;
    }

    int carrierId() {
        return carrierId;
    }

    UUID carrierUuid() {
        return carrierUuid;
    }

    int[] boxIds() {
        return boxIds;
    }

    boolean stale(long now) {
        return !removed && now - lastTick > STALE_MS;
    }

    @Override
    public @NotNull Model model() {
        return model;
    }

    @Override
    public @Nullable LivingEntity carrier() {
        return detached ? null : carrier;
    }

    @Override
    public boolean isActive() {
        return !removed;
    }

    @Override
    public boolean play(@NotNull String stateOrAnimation) {
        return play(stateOrAnimation, 0);
    }

    @Override
    public boolean play(@NotNull String stateOrAnimation, long strikeInMillis) {
        if (removed || model.clipFor(stateOrAnimation, options, phase) == null) {
            return false;
        }
        long asked = System.currentTimeMillis();
        this.asked.add(() -> {
            // Queued until the next tick: the strike is still where it was asked for.
            long late = System.currentTimeMillis() - asked;
            long fit = strikeInMillis <= 0 ? 0 : Math.max(1, strikeInMillis - late);
            controller.play(stateOrAnimation, System.currentTimeMillis(), fit);
        });
        return true;
    }

    /** Ticks one rise and fall of a float takes. */
    private static final int BOB_TICKS = 48;

    @Override
    public void hover(double amplitude) {
        bob = Double.isFinite(amplitude) ? Math.clamp(amplitude, 0, 2) : 0;
    }

    @Override
    public long strikeMillis(@NotNull String stateOrAnimation) {
        Clip clip = model.clipFor(stateOrAnimation, options, phase);
        if (clip == null) {
            return -1;
        }
        double speed = options.tweaks().speed(stateOrAnimation);
        speed = Double.isFinite(speed) && speed > 0 ? Math.clamp(speed, 0.1, 10) : 1;
        return Math.round(Controller.strike(clip) / speed);
    }

    @Override
    public void phase(int phase) {
        int wanted = Math.max(1, phase);
        asked.add(() -> {
            if (this.phase == wanted) {
                return;
            }
            this.phase = wanted;
            controller.refresh(System.currentTimeMillis());
        });
    }

    @Override
    public void resize(double multiplier) {
        double wanted = Double.isFinite(multiplier) && multiplier > 0 ? Math.clamp(multiplier, 0.1, 4) : 1;
        asked.add(() -> sizeWanted = wanted);
    }

    @Override
    public void glow(int rgb) {
        asked.add(() -> {
            glow = rgb;
            if (!flashing) {
                restyleAll(glow);
            }
        });
    }

    @Override
    public void stop() {
        asked.add(controller::stop);
    }

    @Override
    public @NotNull ModelInstance onEvent(@NotNull Consumer<String> listener) {
        listeners.add(listener);
        return this;
    }

    @Override
    public void flash(int rgb, long millis) {
        asked.add(() -> {
            flashUntil = System.currentTimeMillis() + Math.max(50, millis);
            restyleAll(rgb);
            flashing = true;
        });
    }

    @Override
    public void die(boolean apart, double pushX, double pushY, double pushZ) {
        pushed(pushX, pushY, pushZ);
        die(apart);
    }

    @Override
    public void comeApart(double pushX, double pushY, double pushZ) {
        pushed(pushX, pushY, pushZ);
        comeApart();
    }

    private void pushed(double x, double y, double z) {
        // A blow is a direction and a strength, never a launch.
        double length = Math.sqrt(x * x + y * y + z * z);
        double scale = length > 6 ? 6 / length : 1;
        push[0] = Double.isFinite(x) ? x * scale : 0;
        push[1] = Double.isFinite(y) ? y * scale : 0;
        push[2] = Double.isFinite(z) ? z * scale : 0;
    }

    @Override
    public void die(boolean apart) {
        if (removed) {
            return;
        }
        LivingEntity body = carrier;
        if (body != null && tasks.isOwnedBy(body) && !detached) {
            dieNow(apart);
        } else {
            asked.add(() -> dieNow(apart));
        }
    }

    @Override
    public void comeApart() {
        if (removed) {
            return;
        }
        LivingEntity body = carrier;
        if (body != null && !detached && tasks.isOwnedBy(body)) {
            apart();
        } else {
            asked.add(this::apart);
        }
    }

    @Override
    public void remove() {
        if (removed) {
            return;
        }
        removed = true;
        TaskHandle running = timer;
        if (running != null) {
            running.cancel();
        }
        DETACHED.remove(this);
        ModelRuntime.forget(this);
        List<Player> everyone = viewers();
        ModelPackets.destroy(everyone, everything());
        ModelRuntime.load(-cost);
        cost = 0;
        for (List<Player> tier : tiers) {
            tier.clear();
        }
        tierOf.clear();
        distanceOf.clear();
        LivingEntity body = carrier;
        if (body != null && !detached) {
            Runnable show = () -> {
                if (!body.isValid()) {
                    return;
                }
                body.setInvisible(wasInvisible);
                reveal(body, everyone);
            };
            if (tasks.isOwnedBy(body)) {
                show.run();
            } else {
                tasks.runAtEntity(body, show);
            }
        }
    }

    /** Shows a carrier's own armour and name again to whoever was kept from them. */
    private static void reveal(LivingEntity body, List<Player> viewers) {
        EntityEquipment equipment = body.getEquipment();
        for (Player viewer : viewers) {
            if (!viewer.isOnline()) {
                continue;
            }
            if (equipment != null) {
                Map<org.bukkit.inventory.EquipmentSlot, ItemStack> worn = new HashMap<>();
                for (org.bukkit.inventory.EquipmentSlot slot : org.bukkit.inventory.EquipmentSlot.values()) {
                    try {
                        worn.put(slot, equipment.getItem(slot));
                    } catch (IllegalArgumentException notThisBody) {
                        // A body slot on a mob that has none.
                    }
                }
                viewer.sendEquipmentChange(body, worn);
            }
        }
        if (body.isCustomNameVisible()) {
            // Set again, so the name's visibility is sent once more and no longer rewritten.
            body.setCustomNameVisible(false);
            body.setCustomNameVisible(true);
        }
    }

    // ------------------------------------------------------------------ the tick

    private void tick() {
        if (removed) {
            return;
        }
        lastTick = System.currentTimeMillis();
        long now = lastTick;
        try {
            Runnable request;
            while ((request = asked.poll()) != null) {
                request.run();
                if (removed) {
                    return;
                }
            }
            if (!detached) {
                LivingEntity body = carrier;
                if (body == null || !body.isValid()) {
                    if (body != null && body.isDead()) {
                        dieNow(true);
                    } else {
                        remove();
                    }
                    return;
                }
                double fromX = x;
                double fromY = y;
                double fromZ = z;
                float fromYaw = yaw;
                World before = world;
                read(body);
                boolean jumped = before != world;
                double moved = jumped ? 0 : Math.hypot(x - fromX, z - fromZ);
                double rose = jumped ? 0 : y - fromY;
                if (moved > 2 || Math.abs(rose) > 2 || jumped) {
                    // Teleported: no foot stays where it was.
                    legs.release();
                    moved = 0;
                    rose = 0;
                }
                onGround = body.isOnGround();
                motion = motionOf(body, rose);
                // Up a wall, through water or the air, the way it goes counts, not only along the ground.
                double travelled = motion == Controller.Motion.GROUND || motion == Controller.Motion.JUMP
                        || motion == Controller.Motion.FALL ? moved : Math.hypot(moved, rose);
                speed = speed * 0.5 + travelled * 20 * 0.5;
                turnRate = turnRate * 0.5 + wrap(yaw - fromYaw) * 20 * 0.5;
                double wanted = motion == Controller.Motion.CLIMB && body instanceof org.bukkit.entity.Spider ? 1 : 0;
                climb += (wanted - climb) * 0.25;
                if (Math.abs(climb - wanted) < 0.01) climb = wanted;
                look(body);
            } else {
                speed = 0;
                if (now >= endsAt) {
                    if (apartAtEnd) {
                        apart();
                    } else {
                        fade();
                    }
                    return;
                }
            }
            controller.advance(now, speed, detached ? Controller.Motion.GROUND : motion, detached ? 0 : turnRate);
            for (String event : controller.drainEvents()) {
                tell(event);
            }
            if (ticks % RETRACK_TICKS == 0) {
                retrack();
                if (!detached) {
                    refreshCarrier();
                }
            }
            if (ticks % GROUND_TICKS == 0) {
                grounds.clear();
            }
            if (anyWaitingHead && ticks % HEADS_TICKS == 0) {
                swapHeads();
            }
            if (flashing && now >= flashUntil) {
                flashing = false;
                restyleAll(glow);
            }
            if (Math.abs(size - sizeWanted) > 1e-3) {
                size += (sizeWanted - size) * 0.2;
                if (Math.abs(size - sizeWanted) <= 1e-3) size = sizeWanted;
                moveNametag();
            }
            send(now);
            if (!detached && carrier != null) {
                resizeBoxes();
                catchProjectiles(carrier);
            }
            ticks++;
        } catch (RuntimeException failure) {
            ModelRuntime.logger().log(Level.WARNING, "Model " + model.name() + " failed while being drawn and"
                    + " was removed.", failure);
            remove();
        }
    }

    /** How the carrier is moving: on its feet, up a wall, in water, in the air. */
    private Controller.Motion motionOf(LivingEntity body, double rose) {
        if (body.isInWater() && !onGround) {
            return Controller.Motion.SWIM;
        }
        if (body.isClimbing() && !onGround) {
            return Controller.Motion.CLIMB;
        }
        if (onGround) {
            return Controller.Motion.GROUND;
        }
        if (!body.hasGravity()) {
            return Controller.Motion.FLY;
        }
        // A step down a slab is no fall; the controller waits a moment before it lands.
        if (rose > 0.02) {
            return Controller.Motion.JUMP;
        }
        if (rose < -0.05) {
            return Controller.Motion.FALL;
        }
        return motion == Controller.Motion.JUMP || motion == Controller.Motion.FALL ? motion : Controller.Motion.GROUND;
    }

    private void read(LivingEntity body) {
        Location at = body.getLocation();
        if (world != at.getWorld() && world != null) {
            // Taken to another world: everybody who saw it here no longer can.
            dropEveryone();
        }
        world = at.getWorld();
        x = at.getX();
        y = at.getY();
        z = at.getZ();
        float body2;
        try {
            body2 = body.getBodyYaw();
        } catch (NoSuchMethodError spigot) {
            body2 = at.getYaw();
        }
        yaw = body2;
    }

    /** Turns the head towards what the carrier is looking at, a little at a time. */
    private void look(LivingEntity body) {
        if (head < 0) {
            return;
        }
        Location target = null;
        if (body instanceof Mob mob && mob.getTarget() != null && mob.getTarget().getWorld() == body.getWorld()) {
            target = mob.getTarget().getEyeLocation();
        } else {
            double best = 10 * 10;
            for (List<Player> tier : tiers) {
                for (Player viewer : tier) {
                    if (viewer.getWorld() != world) {
                        continue;
                    }
                    double distance = viewer.getLocation().distanceSquared(body.getLocation());
                    if (distance < best) {
                        best = distance;
                        target = viewer.getEyeLocation();
                    }
                }
            }
        }
        double wantYaw = 0;
        double wantPitch = 0;
        if (target != null) {
            Location eye = body.getEyeLocation();
            double dx = target.getX() - eye.getX();
            double dy = target.getY() - eye.getY();
            double dz = target.getZ() - eye.getZ();
            double facing = Math.toDegrees(Math.atan2(-dx, dz));
            wantYaw = Math.clamp(wrap(facing - yaw), -70, 70);
            wantPitch = Math.clamp(-Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))), -40, 40);
        }
        headYaw += (wantYaw - headYaw) * 0.3;
        headPitch += (wantPitch - headPitch) * 0.3;
    }

    private static double wrap(double degrees) {
        double wrapped = degrees % 360;
        if (wrapped > 180) wrapped -= 360;
        if (wrapped < -180) wrapped += 360;
        return wrapped;
    }

    /** Name, scale and held items: whatever the carrier changes about itself. */
    private void refreshCarrier() {
        LivingEntity body = carrier;
        if (body == null) {
            return;
        }
        AttributeInstance scale = scaleOf(body);
        double now = scale == null ? 1 : scale.getValue();
        if (Math.abs(now - carrierScale) > 1e-3) {
            carrierScale = now;
            moveNametag();
        }
        Component named = body.isCustomNameVisible() ? body.customName() : null;
        if (nametagId != 0 && !java.util.Objects.equals(named, name)) {
            name = named;
            sink.restyle(viewers(), nametagId, nametag());
        }
        List<Rig.Part> parts = rig.parts();
        for (int index = 0; index < parts.size(); index++) {
            if (parts.get(index).look().kind() != Look.Kind.EQUIPMENT || tweaked[index] != null || ids[index] == 0) {
                continue;
            }
            DisplayModel now2 = drawnFor(parts.get(index).look(), body);
            ItemStack before = drawn[index].item();
            ItemStack after = now2.item();
            if (before == null || after == null || !before.equals(after)) {
                drawn[index] = flashing ? now2.glow(0xFFFFFF) : glow >= 0 ? now2.glow(glow) : now2;
                sink.restyle(viewersOf(index), ids[index], drawn[index]);
            }
        }
    }

    private static @Nullable AttributeInstance scaleOf(LivingEntity body) {
        try {
            return body.getAttribute(Attribute.SCALE);
        } catch (RuntimeException | LinkageError older) {
            return null;
        }
    }

    // ------------------------------------------------------------------ who watches

    private List<Player> viewers() {
        List<Player> all = new ArrayList<>(tiers[0].size() + tiers[1].size() + tiers[2].size());
        for (List<Player> tier : tiers) {
            all.addAll(tier);
        }
        return all;
    }

    /** The viewers drawing one cube: every tier for a large cube, the near two for a small one. */
    private List<Player> viewersOf(int part) {
        if (major[part]) {
            return viewers();
        }
        List<Player> near = new ArrayList<>(tiers[NEAR]);
        near.addAll(tiers[MID]);
        return near;
    }

    private boolean drawsAt(int part, int tier) {
        return ids[part] != 0 && (tier != FAR || major[part]);
    }

    private int costAt(int tier) {
        int total = 1;
        for (int part = 0; part < ids.length; part++) {
            if (drawsAt(part, tier)) total++;
        }
        return total;
    }

    /** Reads who should see it, and tells the clients that changed. */
    private void retrack() {
        if (world == null) {
            return;
        }
        Map<UUID, Player> wanted = new HashMap<>();
        double range = options.viewRange();
        double limit = range * range;
        // Somebody already watching keeps it a little further out, so walking along the edge does not blink it.
        double keep = (range + KEEP_MARGIN) * (range + KEEP_MARGIN);
        LivingEntity body = detached ? null : carrier;
        Set<Player> tracked = null;
        if (body != null) {
            try {
                tracked = body.getTrackedBy();
            } catch (NoSuchMethodError spigot) {
                tracked = null;
            }
        }
        Iterable<Player> candidates = tracked != null ? tracked : world.getPlayers();
        for (Player player : candidates) {
            if (!player.isOnline() || player.getWorld() != world) {
                continue;
            }
            Location at = player.getLocation();
            double dx = at.getX() - x;
            double dy = at.getY() - y;
            double dz = at.getZ() - z;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance <= (tierOf.containsKey(player.getUniqueId()) ? keep : limit)) {
                wanted.put(player.getUniqueId(), player);
            }
        }
        // A dying model keeps whoever saw it fall: the carrier is gone from their trackers, not from their screen.
        if (detached) {
            for (List<Player> tier : tiers) {
                for (Player viewer : tier) {
                    if (viewer.isOnline() && viewer.getWorld() == world) {
                        wanted.putIfAbsent(viewer.getUniqueId(), viewer);
                    }
                }
            }
        }
        int pressure = ModelRuntime.pressure();
        for (int tier = 0; tier < 3; tier++) {
            for (Player viewer : new ArrayList<>(tiers[tier])) {
                if (!wanted.containsKey(viewer.getUniqueId())) {
                    drop(viewer, tier);
                }
            }
        }
        for (Player viewer : wanted.values()) {
            Location at = viewer.getLocation();
            double distance = Math.sqrt(Math.pow(at.getX() - x, 2) + Math.pow(at.getY() - y, 2)
                    + Math.pow(at.getZ() - z, 2));
            Integer was = distanceOf.get(viewer.getUniqueId());
            int near = ModelRuntime.tier(distance, was == null ? -1 : was);
            distanceOf.put(viewer.getUniqueId(), near);
            int tier = Math.min(FAR, near + pressure);
            Integer now = tierOf.get(viewer.getUniqueId());
            if (now == null) {
                show(viewer, tier);
            } else if (now != tier) {
                move(viewer, now, tier);
            }
        }
    }

    private void dropEveryone() {
        for (int tier = 0; tier < 3; tier++) {
            for (Player viewer : new ArrayList<>(tiers[tier])) {
                drop(viewer, tier);
            }
        }
    }

    private void show(Player viewer, int tier) {
        if (world == null) {
            return;
        }
        if (!posed) {
            pose(System.currentTimeMillis());
        }
        List<Player> one = List.of(viewer);
        Location seat = new Location(world, x, y, z);
        sink.spawn(one, rootId, root, seat, STILL);
        ModelPackets.glide(one, rootId, 2);
        for (int part = 0; part < ids.length; part++) {
            if (drawsAt(part, tier)) {
                sink.spawn(one, ids[part], drawn[part], seat, keyframe(part));
            }
        }
        if (nametagId != 0) {
            sink.spawn(one, nametagId, nametag(), seat, nametagPose());
        }
        sink.mount(one, rootId, passengers(tier));
        if (!detached) {
            for (int box = 0; box < boxIds.length; box++) {
                double[] at = boxAt(box);
                ModelPackets.box(one, boxIds[box], at[0], at[1], at[2], (float) (boxWidth * boxSent),
                        (float) (boxHeight * boxSent));
            }
        }
        tiers[tier].add(viewer);
        tierOf.put(viewer.getUniqueId(), tier);
        int added = costAt(tier);
        cost += added;
        ModelRuntime.load(added);
        // Whoever already watched this tier was sent poses this one was not; send everything once more.
        Arrays.fill(sent[tier], null);
    }

    private void drop(Player viewer, int tier) {
        tiers[tier].remove(viewer);
        tierOf.remove(viewer.getUniqueId());
        distanceOf.remove(viewer.getUniqueId());
        int removedCost = costAt(tier);
        cost -= removedCost;
        ModelRuntime.load(-removedCost);
        if (viewer.isOnline()) {
            ModelPackets.destroy(List.of(viewer), everything());
        }
    }

    /** A viewer nearer or further than they were: the small cubes come or go. */
    private void move(Player viewer, int from, int to) {
        List<Player> one = List.of(viewer);
        boolean wasFar = from == FAR;
        boolean isFar = to == FAR;
        if (wasFar != isFar) {
            Location seat = new Location(world, x, y, z);
            List<Integer> minor = new ArrayList<>();
            for (int part = 0; part < ids.length; part++) {
                if (ids[part] != 0 && !major[part]) {
                    minor.add(part);
                }
            }
            if (isFar) {
                ModelPackets.destroy(one, minor.stream().mapToInt(part -> ids[part]).toArray());
            } else {
                for (int part : minor) {
                    sink.spawn(one, ids[part], drawn[part], seat, keyframe(part));
                }
            }
            sink.mount(one, rootId, passengers(to));
        }
        tiers[from].remove(viewer);
        tiers[to].add(viewer);
        tierOf.put(viewer.getUniqueId(), to);
        int delta = costAt(to) - costAt(from);
        cost += delta;
        ModelRuntime.load(delta);
        Arrays.fill(sent[to], null);
    }

    private int[] passengers(int tier) {
        List<Integer> riding = new ArrayList<>();
        for (int part = 0; part < ids.length; part++) {
            if (drawsAt(part, tier)) riding.add(ids[part]);
        }
        if (nametagId != 0) riding.add(nametagId);
        return riding.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Every entity id it has ever put on a client, for one destroy. */
    private int[] everything() {
        List<Integer> all = new ArrayList<>(ids.length + boxIds.length + 2);
        all.add(rootId);
        if (nametagId != 0) all.add(nametagId);
        for (int id : ids) {
            if (id != 0) all.add(id);
        }
        for (int id : boxIds) all.add(id);
        return all.stream().mapToInt(Integer::intValue).toArray();
    }

    // ------------------------------------------------------------------ posing

    private void pose(long now) {
        controller.sample(now, offsets);
        double scale = options.scale() * carrierScale * size;
        double tilt = climb * 90;
        // A slow turn is sent a few degrees at a time, the client gliding between: every cube moves when it turns.
        double drift = Math.abs(wrap(yaw - poseYaw));
        if (drift >= YAW_STEP || Math.abs(turnRate) < 1 && drift > 0) {
            poseYaw = yaw;
        }
        if (legs.isEmpty()) {
            poser.bones(offsets, head, headYaw, headPitch, 0, 0, 0);
        } else if (tilt > 4.5) {
            // Up a wall its feet stand on the wall: ground found along its own up, in its own frame.
            boolean stand = !detached && !tiers[NEAR].isEmpty();
            Legs.Ground wall = Surfaces.frame(Poser.facing(poseYaw, tilt), x, y, z, this::probe);
            legs.pose(poser, offsets, head, headYaw, headPitch, stand, speed, scale / 16.0, 0, 0, 0, 0, offset,
                    wall);
        } else {
            // Feet on the ground only where somebody is near enough to see them.
            boolean stand = !detached && onGround && !tiers[NEAR].isEmpty();
            legs.pose(poser, offsets, head, headYaw, headPitch, stand, speed, scale / 16.0, poseYaw, x, y, z, offset,
                    this::groundTop);
        }
        poser.parts(scale, poseYaw, tilt, offset, poses);
        posed = true;
    }

    /** How far a surface is along a direction, read only where this thread may read; {@code NaN} otherwise. */
    private double probe(double sx, double sy, double sz, double dx, double dy, double dz, double max) {
        World at = world;
        if (at == null) {
            return Double.NaN;
        }
        Location start = new Location(at, sx, sy, sz);
        Location end = new Location(at, sx + dx * max, sy + dy * max, sz + dz * max);
        if (!at.isChunkLoaded(start.getBlockX() >> 4, start.getBlockZ() >> 4)
                || !at.isChunkLoaded(end.getBlockX() >> 4, end.getBlockZ() >> 4)
                || !tasks.isOwnedBy(start) || !tasks.isOwnedBy(end)) {
            return Double.NaN;
        }
        org.bukkit.util.RayTraceResult hit = at.rayTraceBlocks(start, new org.bukkit.util.Vector(dx, dy, dz), max,
                org.bukkit.FluidCollisionMode.NEVER, true);
        return hit == null ? Double.NaN : hit.getHitPosition().distance(start.toVector());
    }

    /** The top of the ground under a point, read through a short cache; {@code NaN} where it cannot be read. */
    private double groundTop(double gx, double gz, double feet, double reach) {
        World at = world;
        if (at == null) {
            return Double.NaN;
        }
        int bx = (int) Math.floor(gx);
        int bz = (int) Math.floor(gz);
        int from = (int) Math.floor(feet + reach);
        int to = (int) Math.floor(feet - reach);
        long key = ((long) bx & 0x3FFFFFFL) << 38 | ((long) bz & 0x3FFFFFFL) << 12 | (from & 0xFFFL);
        Double known = grounds.get(key);
        if (known != null) {
            return known;
        }
        double top = Double.NaN;
        if (at.isChunkLoaded(bx >> 4, bz >> 4) && tasks.isOwnedBy(new Location(at, gx, feet, gz))) {
            for (int by = from; by >= to && by >= at.getMinHeight(); by--) {
                org.bukkit.block.Block block = at.getBlockAt(bx, by, bz);
                if (block.isPassable()) {
                    continue;
                }
                org.bukkit.util.BoundingBox box = block.getBoundingBox();
                if (box.getVolume() <= 0) {
                    continue;
                }
                // A wall taller than a foot can reach is nothing to stand on.
                top = box.getMaxY() > feet + reach ? Double.NaN : box.getMaxY();
                break;
            }
        }
        grounds.put(key, top);
        return top;
    }

    /** Degrees a model turning slowly waits for before its cubes are turned again. */
    static final double YAW_STEP = 3;

    private Controller.Motion motion = Controller.Motion.GROUND;
    private double turnRate;
    /** How far up a wall it is stood, {@code 0-1}. */
    private double climb;
    private double poseYaw;
    /** How far it bobs up and down, blocks; a hovering body's float. */
    private volatile double bob;

    private double lastX = Double.NaN;
    private double lastY;
    private double lastZ;
    private float lastYaw;

    private void send(long now) {
        boolean due = false;
        for (int tier = 0; tier < 3; tier++) {
            if (!tiers[tier].isEmpty() && ticks % PERIOD[tier] == 0) {
                due = true;
            }
        }
        boolean moved = x != lastX || y != lastY || z != lastZ;
        boolean turned = yaw != lastYaw;
        double floating = bob;
        if (moved || floating > 0) {
            List<Player> everyone = viewers();
            if (!everyone.isEmpty()) {
                // A float is the root rising and sinking: one packet, every cube along.
                double lift = floating > 0 ? floating * Math.sin(ticks * Math.PI * 2 / BOB_TICKS) : 0;
                ModelPackets.teleport(everyone, rootId, x, y + lift, z);
            }
        }
        if ((moved || turned) && boxIds.length > 0 && !detached) {
            List<Player> everyone = viewers();
            for (int box = 0; box < boxIds.length; box++) {
                double[] at = boxAt(box);
                ModelPackets.teleport(everyone, boxIds[box], at[0], at[1], at[2]);
            }
        }
        lastX = x;
        lastY = y;
        lastZ = z;
        lastYaw = yaw;
        if (!due) {
            return;
        }
        pose(now);
        for (int tier = 0; tier < 3; tier++) {
            List<Player> watching = tiers[tier];
            if (watching.isEmpty() || ticks % PERIOD[tier] != 0) {
                continue;
            }
            for (int part = 0; part < ids.length; part++) {
                if (!drawsAt(part, tier)) {
                    continue;
                }
                DisplayKeyframe pose = keyframe(part);
                if (unchanged(sent[tier], part, pose)) {
                    continue;
                }
                sink.pose(watching, ids[part], drawn[part], pose, PERIOD[tier]);
            }
        }
    }

    /** Whether a cube's pose is what this tier was last sent; remembers it when it is not. */
    private static boolean unchanged(float[][] sent, int part, DisplayKeyframe pose) {
        float[] fields = {pose.x(), pose.y(), pose.z(), pose.rotation().x(), pose.rotation().y(),
                pose.rotation().z(), pose.rotation().w(), pose.scaleX(), pose.scaleY(), pose.scaleZ()};
        float[] last = sent[part];
        if (last != null) {
            boolean same = true;
            for (int index = 0; index < fields.length; index++) {
                if (Math.abs(fields[index] - last[index]) > 1e-3f) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return true;
            }
        }
        sent[part] = fields;
        return false;
    }

    /** One cube's pose, as its display is sent it. */
    private DisplayKeyframe keyframe(int part) {
        double[] pose = poses[part];
        Rotation turn = new Rotation((float) pose[Poser.QX], (float) pose[Poser.QY], (float) pose[Poser.QZ],
                (float) pose[Poser.QW]);
        float sx = (float) pose[Poser.SX];
        float sy = (float) pose[Poser.SY];
        float sz = (float) pose[Poser.SZ];
        float tx = (float) pose[Poser.TX];
        float ty = (float) pose[Poser.TY];
        float tz = (float) pose[Poser.TZ];
        if (fading) {
            sx = sy = sz = 0.001f;
        }
        Look look = rig.parts().get(part).look();
        if (look.kind() == Look.Kind.HEAD && drawn[part].kind() == DisplayModel.Kind.ITEM) {
            // A head is half a block at size one, and sits in the lower half of its space.
            sx *= 2;
            sy *= 2;
            sz *= 2;
            float[] lift = turn.apply(new float[]{0f, HEAD_LIFT * sy, 0f});
            tx += lift[0];
            ty += lift[1];
            tz += lift[2];
            turn = ITEM_FACING.then(turn);
        }
        return new DisplayKeyframe(0, tx, ty, tz, turn, sx, sy, sz);
    }

    // ------------------------------------------------------------------ looks

    /** A block or item a tweak names, or {@code null} for a name that is neither. */
    private static @Nullable DisplayModel material(String name) {
        if (name.isEmpty()) {
            return null;
        }
        Material material = Material.matchMaterial(name);
        if (material == null || material.isAir()) {
            return null;
        }
        return material.isBlock() ? DisplayModel.block(material.createBlockData())
                : DisplayModel.item(new ItemStack(material));
    }

    private DisplayModel drawnFor(Look look, @Nullable LivingEntity body) {
        return switch (look.kind()) {
            case BLOCK -> DisplayModel.block(BlockPalette.block(look.material()));
            case NAMED -> look.material().isBlock()
                    ? DisplayModel.block(look.material().createBlockData())
                    : DisplayModel.item(new ItemStack(look.material()));
            case EQUIPMENT -> {
                ItemStack item = null;
                EntityEquipment equipment = body == null ? null : body.getEquipment();
                if (equipment != null && look.slot() != null) {
                    try {
                        item = equipment.getItem(look.slot());
                    } catch (IllegalArgumentException notThisBody) {
                        item = null;
                    }
                }
                yield DisplayModel.item(item == null ? new ItemStack(Material.AIR) : item.clone());
            }
            case HEAD -> {
                SkinCubes.Cube cube = look.head();
                String texture = options.heads() && cube != null ? RagdollTextures.known(cube.hash()) : null;
                yield texture != null
                        ? DisplayModel.item(HEADS.get(texture, id -> HeadFactory.create(Textures.fromUrl(id))))
                        : DisplayModel.block(BlockPalette.block(look.material()));
            }
            case HIDDEN -> ROOT;
        };
    }

    /** Asks for every cube's head that nobody has a texture for yet. */
    private void askForHeads() {
        RagdollTextures.register(plugin);
        List<SkinCubes.Cube> wanted = new ArrayList<>();
        for (int part = 0; part < ids.length; part++) {
            if (waitingHead[part]) {
                wanted.add(rig.parts().get(part).look().head());
            }
        }
        RagdollTextures.missing(wanted).thenAccept(unknown -> {
            if (!unknown.isEmpty()) {
                SkinTextureQueue.submit(unknown, null, SkinCubes.Quality.HIGH);
            }
        });
    }

    /** Cubes whose head has arrived become heads: a new display each, since a block cannot become an item. */
    private void swapHeads() {
        boolean still = false;
        Location seat = world == null ? null : new Location(world, x, y, z);
        for (int part = 0; part < ids.length; part++) {
            if (!waitingHead[part]) {
                continue;
            }
            DisplayModel now = drawnFor(rig.parts().get(part).look(), carrier);
            if (now.kind() != DisplayModel.Kind.ITEM) {
                still = true;
                continue;
            }
            waitingHead[part] = false;
            List<Player> watching = viewersOf(part);
            int old = ids[part];
            ids[part] = DisplayRuntime.nextEntityId();
            drawn[part] = now;
            if (seat != null && !watching.isEmpty()) {
                sink.spawn(watching, ids[part], drawn[part], seat, keyframe(part));
                ModelPackets.destroy(watching, new int[]{old});
            }
            for (int tier = 0; tier < 3; tier++) {
                sent[tier][part] = null;
                if (!tiers[tier].isEmpty()) {
                    sink.mount(tiers[tier], rootId, passengers(tier));
                }
            }
        }
        anyWaitingHead = still;
    }

    private void restyleAll(int rgb) {
        for (int part = 0; part < ids.length; part++) {
            if (ids[part] == 0) {
                continue;
            }
            drawn[part] = drawn[part].glow(rgb);
            List<Player> watching = viewersOf(part);
            if (!watching.isEmpty()) {
                sink.restyle(watching, ids[part], drawn[part]);
            }
        }
    }

    private DisplayModel nametag() {
        return DisplayModel.text(name == null ? Component.empty() : name)
                .billboard("center")
                .background(0)
                .textShadow(true);
    }

    private DisplayKeyframe nametagPose() {
        float top = (float) (rig.max()[1] / 16.0 * options.scale() * carrierScale * size + 0.3 + offset[1]);
        return new DisplayKeyframe(0, 0f, top, 0f, Rotation.NONE, 1f, 1f, 1f);
    }

    private void moveNametag() {
        if (nametagId == 0) {
            return;
        }
        List<Player> everyone = viewers();
        if (!everyone.isEmpty()) {
            sink.pose(everyone, nametagId, nametag(), nametagPose(), 4);
        }
    }

    // ------------------------------------------------------------------ boxes and hits

    /** Where one box stands now: along the model's long axis, turned with the body. */
    private double[] boxAt(int box) {
        double factor = boxFactor();
        double along = boxAlong[box] * factor;
        double lx = (boxesAlongForward ? 0 : along) + offset[0];
        double lz = (boxesAlongForward ? along : 0) + offset[2];
        double turn = Math.toRadians(-yaw) + Math.PI;
        double[] turned = Poser.rotate(0, Math.sin(turn / 2), 0, Math.cos(turn / 2), lx, 0, lz);
        double bottom = Math.max(0, rig.min()[1] / 16.0 * options.scale() * carrierScale * size) + offset[1];
        return new double[]{x + turned[0], y + bottom, z + turned[2]};
    }

    /** How much bigger than when they were sized the boxes are now: a fight phase grew it, a skill shrank it. */
    private double boxFactor() {
        return boxBase > 0 ? carrierScale * size / boxBase : 1;
    }

    /** One box as the server sees it now. */
    private org.bukkit.util.BoundingBox bounds(int box) {
        double[] at = boxAt(box);
        double factor = boxFactor();
        return Boxes.box(at[0], at[1], at[2], boxWidth * factor, boxHeight * factor);
    }

    /** Sends the boxes' size again once the model has grown or shrunk enough to show. */
    private void resizeBoxes() {
        double factor = boxFactor();
        if (boxIds.length == 0 || detached || Math.abs(factor - boxSent) < 0.02) {
            return;
        }
        boxSent = factor;
        List<Player> everyone = viewers();
        for (int box : boxIds) {
            ModelPackets.boxSize(everyone, box, (float) (boxWidth * factor), (float) (boxHeight * factor));
        }
    }

    /**
     * Hands the carrier every projectile that flew into one of the boxes this
     * tick: the server only knows the carrier's own box, so an arrow would
     * otherwise pass through the rest of the model.
     */
    private void catchProjectiles(LivingEntity body) {
        if (boxIds.length == 0 || detached || viewers().isEmpty() || world == null) {
            return;
        }
        org.bukkit.util.BoundingBox around = bounds(0);
        for (int box = 1; box < boxIds.length; box++) {
            around.union(bounds(box));
        }
        around.expand(4);
        for (org.bukkit.entity.Entity nearby : world.getNearbyEntities(around,
                entity -> entity instanceof org.bukkit.entity.Projectile)) {
            org.bukkit.entity.Projectile projectile = (org.bukkit.entity.Projectile) nearby;
            if (projectile.getShooter() == body || caught.contains(projectile.getEntityId())
                    || projectile instanceof org.bukkit.entity.AbstractArrow arrow && arrow.isInBlock()) {
                continue;
            }
            org.bukkit.util.Vector from = projectile.getLocation().toVector();
            org.bukkit.util.Vector velocity = projectile.getVelocity();
            if (Boxes.crossed(body.getBoundingBox(), from, velocity) != null) {
                // The carrier's own box takes it, as vanilla does.
                continue;
            }
            for (int box = 0; box < boxIds.length; box++) {
                org.bukkit.util.Vector entered = Boxes.crossed(bounds(box), from, velocity);
                if (entered == null) {
                    continue;
                }
                caught.add(projectile.getEntityId());
                if (projectile.canHitEntity(body)) {
                    projectile.hitEntity(body, entered);
                }
                break;
            }
        }
    }

    /**
     * A hit on one of its boxes, from the network thread: the carrier takes it
     * if the player could have made it, as the server checks a hit on any
     * entity: within their interaction range of the box, with nothing solid in
     * the way, once a tick at most. The vanilla attack then weighs the
     * player's attack cooldown as it always does.
     */
    void hitBy(Player player, int boxId) {
        LivingEntity body = carrier;
        if (body == null || detached || removed) {
            return;
        }
        tasks.runAtEntity(body, () -> {
            if (!body.isValid() || !player.isOnline() || player.getWorld() != body.getWorld() || detached) {
                return;
            }
            int box = -1;
            for (int index = 0; index < boxIds.length; index++) {
                if (boxIds[index] == boxId) {
                    box = index;
                }
            }
            if (box < 0) {
                return;
            }
            Long last = lastHit.put(player.getUniqueId(), ticks);
            if (last != null && last == ticks) {
                return;
            }
            org.bukkit.util.BoundingBox bounds = bounds(box);
            Location eye = player.getEyeLocation();
            if (!tasks.isOwnedBy(eye)) {
                // Folia: a player in another region cannot be checked from here, nor reach this far.
                return;
            }
            AttributeInstance range = interactionRange(player);
            if (!Boxes.reaches(bounds, eye.toVector(), range == null ? 3 : range.getValue())) {
                return;
            }
            org.bukkit.util.Vector to = Boxes.nearest(bounds, eye.getX(), eye.getY(), eye.getZ())
                    .subtract(eye.toVector());
            double length = to.length();
            if (length > 1.0E-3 && eye.getWorld().rayTraceBlocks(eye, to.multiply(1 / length), length,
                    org.bukkit.FluidCollisionMode.NEVER, true) != null) {
                return;
            }
            player.attack(body);
        });
    }

    private static @Nullable AttributeInstance interactionRange(Player player) {
        try {
            return player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        } catch (RuntimeException | LinkageError older) {
            return null;
        }
    }

    // ------------------------------------------------------------------ ending

    private void tell(String event) {
        voice(event);
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException failure) {
                ModelRuntime.logger().log(Level.WARNING, "A model event listener failed on \"" + event + "\".",
                        failure);
            }
        }
    }

    /**
     * Plays an event that names a sound or a particle, where it happens:
     * {@code name@bone} at that bone, else in the model's middle.
     */
    private void voice(String event) {
        World at = world;
        if (at == null) {
            return;
        }
        int split = event.lastIndexOf('@');
        String name = split > 0 ? event.substring(0, split) : event;
        double scale = options.scale() * carrierScale * size;
        double ex = x;
        double ey = y + (rig.max()[1] + rig.min()[1]) / 32.0 * scale;
        double ez = z;
        int bone = split > 0 ? rig.bone(event.substring(split + 1).trim()) : -1;
        if (bone >= 0 && posed) {
            List<Rig.Part> parts = rig.parts();
            for (int part = 0; part < parts.size(); part++) {
                if (parts.get(part).bone() == bone) {
                    ex = x + poses[part][Poser.TX];
                    ey = y + poses[part][Poser.TY];
                    ez = z + poses[part][Poser.TZ];
                    break;
                }
            }
        }
        Voices.event(name, new Location(at, ex, ey, ez));
    }

    private void dieNow(boolean apart) {
        if (removed || detached) {
            return;
        }
        long now = System.currentTimeMillis();
        long length = controller.die(now);
        if (length <= 0 && apart) {
            apart();
            return;
        }
        detach();
        endsAt = now + Math.max(length, 50);
        apartAtEnd = apart;
    }

    /** Lets go of the carrier, keeping its cubes where they are, ticking on the place now. */
    private void detach() {
        if (detached) {
            return;
        }
        detached = true;
        ModelRuntime.letGo(this);
        for (int box : boxIds) {
            ModelPackets.destroy(viewers(), new int[]{box});
        }
        DETACHED.add(this);
        TaskHandle running = timer;
        if (running != null) {
            running.cancel();
        }
        if (world != null) {
            timer = tasks.runAtLocationTimer(new Location(world, x, y, z), 1L, 1L, this::tick);
        }
    }

    /** Shrinks every cube to nothing over a moment, then takes the model off. */
    private void fade() {
        fading = true;
        for (int tier = 0; tier < 3; tier++) {
            List<Player> watching = tiers[tier];
            if (watching.isEmpty()) {
                continue;
            }
            for (int part = 0; part < ids.length; part++) {
                if (drawsAt(part, tier)) {
                    sink.pose(watching, ids[part], drawn[part], keyframe(part), (int) (FADE_MS / 50));
                }
            }
        }
        endsAt = Long.MAX_VALUE;
        Location at = world == null ? null : new Location(world, x, y, z);
        if (at == null) {
            remove();
            return;
        }
        tasks.runAtLocationLater(at, FADE_MS / 50 + 1, this::remove);
    }

    /**
     * Every cube thrown out from the middle and away from the blow, bouncing off
     * the blocks around it and coming to rest before it melts; the model itself
     * goes at once.
     */
    private void apart() {
        if (removed) {
            return;
        }
        if (!posed) {
            pose(System.currentTimeMillis());
        }
        Location at = world == null ? null : new Location(world, x, y, z);
        if (at != null && !viewers().isEmpty()) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            java.util.Random dice = new java.util.Random(random.nextLong());
            double scale = options.scale() * carrierScale * size;
            double middle = (rig.max()[1] + rig.min()[1]) / 32.0 * scale + offset[1];
            double[] centre = {0, middle, 0};
            double[] origin = {x, y, z};
            Debris.Solid solid = solidity(at.getWorld());
            // The biggest cubes are the ones worth working out; past the budget the rest fly the simple way.
            Integer[] order = new Integer[ids.length];
            for (int part = 0; part < ids.length; part++) {
                order[part] = part;
            }
            Arrays.sort(order, Comparator.comparingDouble(part -> -volume(poses[part])));
            int worked = 0;
            for (int part : order) {
                if (ids[part] == 0) {
                    continue;
                }
                List<Player> watching = viewersOf(part);
                if (watching.isEmpty()) {
                    continue;
                }
                DisplayKeyframe from = keyframe(part);
                double[] pose = poses[part];
                double[] extent = {pose[Poser.SX], pose[Poser.SY], pose[Poser.SZ]};
                DisplayMotion motion = worked++ < Debris.MAX_PIECES
                        ? Debris.fly(from, extent, origin, push, centre, solid, dice)
                        : flight(from, middle, random);
                DisplayRuntime.show(owner, drawn[part].glow(-1), motion, at, watching);
            }
        }
        remove();
    }

    private static double volume(double[] pose) {
        return Math.abs(pose[Poser.SX] * pose[Poser.SY] * pose[Poser.SZ]);
    }

    /** The blocks around a break, read as a cube asks for them and remembered for the rest of the break. */
    private Debris.Solid solidity(World where) {
        Map<Long, Double> known = new HashMap<>();
        return (bx, by, bz) -> {
            long key = ((long) bx & 0x3FFFFFFL) << 38 | ((long) bz & 0x3FFFFFFL) << 12 | (by & 0xFFFL);
            Double top = known.get(key);
            if (top == null) {
                top = 0.0;
                if (by >= where.getMinHeight() && by < where.getMaxHeight() && where.isChunkLoaded(bx >> 4, bz >> 4)
                        && tasks.isOwnedBy(new Location(where, bx, by, bz))) {
                    org.bukkit.block.Block block = where.getBlockAt(bx, by, bz);
                    if (!block.isPassable()) {
                        org.bukkit.util.BoundingBox box = block.getBoundingBox();
                        if (box.getVolume() > 0) {
                            top = Math.clamp(box.getMaxY() - by, 0.05, 1.0);
                        }
                    }
                }
                known.put(key, top);
            }
            return top;
        };
    }

    /** Out from the model's middle, up, under gravity to the ground, then melting where it lands. */
    static DisplayMotion flight(DisplayKeyframe from, double middle, ThreadLocalRandom random) {
        double outX = from.x();
        double outZ = from.z();
        double length = Math.hypot(outX, outZ);
        if (length < 1e-3) {
            double angle = random.nextDouble(Math.PI * 2);
            outX = Math.cos(angle);
            outZ = Math.sin(angle);
            length = 1;
        }
        double push = random.nextDouble(1.2, 2.6);
        double dx = outX / length * push * (0.6 + Math.abs(from.y() - middle) * 0.2);
        double dz = outZ / length * push * (0.6 + Math.abs(from.y() - middle) * 0.2);
        double gravity = 16;
        long flying = APART_MS * 3 / 5;
        double seconds = flying / 1000.0;
        double land = Math.max(0.05, Math.min(from.scaleY(), 1) / 2);
        double climb = 0.5 * gravity * seconds * seconds + land - from.y();
        double[] size = {from.scaleX(), from.scaleY(), from.scaleZ()};
        double[] flat = {from.scaleX() * 0.9, 0.01, from.scaleZ() * 0.9};
        DisplayMotion fly = DisplayMotion.builder().life(flying)
                .from(from.x(), from.y(), from.z())
                .to(from.x() + dx, from.y() + climb, from.z() + dz)
                .rotation(from.rotation())
                .spin(random.nextInt(-2, 3), random.nextInt(-1, 2), random.nextInt(-2, 3))
                .scale(size, size)
                .gravity(gravity)
                .build();
        DisplayMotion melt = DisplayMotion.builder().life(APART_MS - flying)
                .from(from.x() + dx, land, from.z() + dz).to(from.x() + dx, land, from.z() + dz)
                .rotation(from.rotation())
                .scale(size, flat)
                .ease(DisplayMotion.Easing.IN)
                .build();
        return DisplayMotion.chain(fly, melt);
    }
}
