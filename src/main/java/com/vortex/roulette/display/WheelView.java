package com.vortex.roulette.display;

import com.vortex.roulette.RoulettePlugin;
import com.vortex.roulette.game.Round;
import com.vortex.roulette.game.RoundResult;
import com.vortex.roulette.model.Pocket;
import com.vortex.roulette.model.WheelType;
import com.vortex.roulette.table.TableAttachment;
import com.vortex.roulette.table.TableAttachmentFactory;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The wheel of one table: a bowl that stays still, a rotor that turns and a ball, three flat pictures on display
 * entities. It only shows what {@link SpinCurve} says for the current tick, so it cannot change a result and a
 * skipped tick only skips a frame.
 *
 * <p>Both moving parts sit at the wheel's centre and are moved by rotation, which the client carries out as a
 * real turn between two updates. The ball's model holds the ball away from its centre (see the pack generator),
 * so a rotation swings it along the track; only its fall towards the pockets uses a translation.
 */
public final class WheelView implements TableAttachment {

    /** Blocks the wheel picture covers. */
    static final float WIDTH = 3f;
    /** Blocks between the ball and the centre of its model at scale 1: resourcepack/generate_placeholders.py. */
    static final float BALL_ORBIT = 22f / 16;
    static final float BALL_SCALE = (float) (SpinCurve.TRACK_RADIUS * WIDTH / BALL_ORBIT);
    /** Each layer lies this much above the one below it. */
    static final double LIFT = 1.0 / 128;
    /** Ticks between two updates. The ball turns at most about 75 degrees in that time. */
    private static final int PERIOD = 2;
    private static final double QUARTER = Math.PI / 2;

    private final RoulettePlugin plugin;
    private final WheelType wheel;
    private final Location centre;
    private final NumberBoard board;

    private boolean shown;
    private ItemDisplay bowl;
    private ItemDisplay rotor;
    private ItemDisplay ball;
    private BukkitTask task;

    /** The spin being shown or the one whose ball still lies in its pocket; null while there is no ball. */
    private SpinCurve curve;
    private int startTick;
    private double lastShown;
    private double rotorAngle;
    private SoundSpec trackSound;
    private SoundSpec pocketSound;
    private SoundSpec restSound;

    /** One wheel per table. Register with {@code TableManager.addAttachment}. */
    public static TableAttachmentFactory factory(RoulettePlugin plugin) {
        return table -> new WheelView(plugin, table.wheel(), table.wheelCentre(), table.displayYaw());
    }

    /**
     * @param centre the middle of the 3 x 3 wheel area, at the height of the table top
     * @param yaw    the entity yaw that turns the felt picture's +x onto the direction the layout runs in
     */
    public WheelView(RoulettePlugin plugin, WheelType wheel, Location centre, float yaw) {
        this.plugin = plugin;
        this.wheel = wheel;
        this.centre = centre.clone();
        this.centre.setYaw(yaw);
        this.centre.setPitch(0);
        this.board = new NumberBoard(plugin, this.centre);
    }

    /** Spawns the wheel. Safe to call again. */
    @Override
    public void show() {
        shown = true;
        if (ready()) {
            spawnMissing();
            pose(elapsed(), 0);
        }
    }

    /** Removes every entity. The rotor keeps its angle for the next time; the ball is put away. */
    @Override
    public void hide() {
        shown = false;
        rotorAngle = currentRotorAngle();
        curve = null;
        stopTask();
        for (Display display : new Display[] {bowl, rotor, ball}) {
            if (display != null) {
                display.remove();
            }
        }
        bowl = null;
        rotor = null;
        ball = null;
        board.hide();
    }

    @Override
    public void spinStarted(Round round, Pocket result, int durationTicks) {
        int index = wheel.indexOf(result);
        if (index < 0) {
            return;
        }
        var sounds = plugin.config().raw();
        trackSound = SoundSpec.parse(sounds.getString("wheel.sounds.track"));
        pocketSound = SoundSpec.parse(sounds.getString("wheel.sounds.pocket"));
        restSound = SoundSpec.parse(sounds.getString("wheel.sounds.rest"));

        curve = new SpinCurve(wheel.size(), index, durationTicks, currentRotorAngle(),
                ThreadLocalRandom.current().nextDouble());
        startTick = Bukkit.getCurrentTick();
        lastShown = 0;
        if (!shown) {
            return;
        }
        if (ready()) {
            spawnMissing();
            pose(0, 0);   // the croupier puts the ball on the track: no glide from the old pocket
        }
        stopTask();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, PERIOD);
    }

    @Override
    public void resultSettled(Round round, RoundResult result) {
        board.add(result.pocket());
    }

    private void tick() {
        if (curve == null || !shown) {
            stopTask();
            return;
        }
        double now = elapsed();
        if (ready()) {
            spawnMissing();
            double next = Math.min(now + PERIOD, curve.endTick());
            pose(next, PERIOD);
            playSounds(lastShown, next);
            lastShown = next;
        }
        if (now >= curve.endTick()) {
            stopTask();
        }
    }

    /** Entities can only be spawned and moved while the wheel's chunk is loaded. */
    private boolean ready() {
        return centre.isWorldLoaded() && centre.isChunkLoaded();
    }

    /** Ticks since the spin started, never past its end. 0 without a spin. */
    private double elapsed() {
        return curve == null ? 0 : Math.min(Bukkit.getCurrentTick() - startTick, curve.endTick());
    }

    private double currentRotorAngle() {
        return curve == null ? rotorAngle : Math.IEEEremainder(curve.rotorAngle(elapsed()), Math.TAU);
    }

    /** Moves rotor and ball to where they are at {@code tick}, gliding there over {@code glideTicks}. */
    private void pose(double tick, int glideTicks) {
        double rotorNow = curve == null ? rotorAngle : curve.rotorAngle(tick);
        glide(rotor, glideTicks, turned(rotorNow, WIDTH, 0));
        if (curve != null && ball != null) {
            float inward = (float) ((curve.ballRadius(tick) - SpinCurve.TRACK_RADIUS) * WIDTH);
            glide(ball, glideTicks, turned(curve.ballAngle(tick), BALL_SCALE, inward));
        }
    }

    private static void glide(ItemDisplay display, int ticks, Transformation to) {
        if (display == null) {
            return;
        }
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(ticks);
        display.setTransformation(to);
    }

    /**
     * A picture turned clockwise by {@code angle} (seen from above) and moved {@code outward} blocks along the
     * direction its 12 o'clock then points in. With no turn, 12 o'clock is the picture's -y, which is -Z.
     */
    private static Transformation turned(double angle, float scale, float outward) {
        float a = (float) Math.IEEEremainder(angle, Math.TAU);
        Vector3f offset = new Vector3f((float) Math.sin(a) * outward, 0, (float) -Math.cos(a) * outward);
        return new Transformation(offset, new Quaternionf().rotationY(-a), new Vector3f(scale, 1, scale),
                new Quaternionf());
    }

    private void spawnMissing() {
        if (bowl == null || !bowl.isValid()) {
            bowl = spawn("wheel_base", 1);
            bowl.setTransformation(turned(0, WIDTH, 0));
        }
        if (rotor == null || !rotor.isValid()) {
            rotor = spawn(wheel == WheelType.AMERICAN ? "wheel_american" : "wheel_european", 2);
            rotor.setTransformation(turned(currentRotorAngle(), WIDTH, 0));
        }
        if (curve != null && (ball == null || !ball.isValid())) {
            ball = spawn("ball", 3);
        } else if (curve == null && ball != null) {
            ball.remove();
            ball = null;
        }
        board.show();
    }

    private ItemDisplay spawn(String model, int layer) {
        Location at = centre.clone().add(0, layer * LIFT, 0);
        return at.getWorld().spawn(at, ItemDisplay.class, display -> {
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            meta.setItemModel(new NamespacedKey("roulette", model));
            item.setItemMeta(meta);
            display.setItemStack(item);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setPersistent(false);
            display.addScoreboardTag(ENTITY_TAG);
        });
    }

    /** The clicks of the ball between two shown moments: quarter marks on the track, pockets in the fall, rest. */
    private void playSounds(double from, double to) {
        if (to <= curve.dropTick()) {
            if (Math.floor(curve.ballAngle(from) / QUARTER) != Math.floor(curve.ballAngle(to) / QUARTER)) {
                play(trackSound);
            }
        } else if (to < curve.settleTick()) {
            if (curve.pocketUnderBall(from) != curve.pocketUnderBall(to)) {
                play(pocketSound);
            }
        } else if (from < curve.settleTick()) {
            play(restSound);
        }
    }

    private void play(SoundSpec sound) {
        if (sound != null) {
            centre.getWorld().playSound(centre, sound.key(), SoundCategory.BLOCKS, sound.volume(), sound.pitch());
        }
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** A sound from config: {@code "<sound> <volume> <pitch>"}; volume and pitch default to 1. */
    record SoundSpec(String key, float volume, float pitch) {

        /** Null for an empty or unreadable entry, which means silence. */
        static SoundSpec parse(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            String[] parts = text.trim().split("\\s+");
            try {
                float volume = parts.length > 1 ? Float.parseFloat(parts[1]) : 1;
                float pitch = parts.length > 2 ? Float.parseFloat(parts[2]) : 1;
                return new SoundSpec(parts[0], volume, Math.clamp(pitch, 0.5f, 2f));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
