package dev.logos.runtime;

import java.util.HashMap;
import java.util.Map;

import dev.logos.LogosConfig;
import dev.logos.lang.Node;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * 时间加速: pushes time forward by a set amount, ever faster up to a cap, braking hard near the end.
 * Where the cast's dimension keeps a time of day (not one of fixed time), the time is pushed on each
 * tick and sent to the players there, so their sky turns smoothly. Random ticks in that one dimension
 * speed up along the same curve, under a much lower cap; a dimension of fixed time gets only those.
 * Paid as it goes, 4 mana for each second (20 ticks) pushed through.
 */
public final class TimeSkipObj extends SpellObject {
    public static final double COST_PER_SECOND = 4;
    /** How fast the rate climbs: × this every tick. */
    static final double GROWTH = 1.05;
    /** Braking starts when what is left would pass in this many ticks at the current rate, then decays over about as many. */
    static final double BRAKE_TICKS = 6;

    /** The skip running on each time of day (dimensions that share one share the key), or on a dimension of fixed time. */
    private static final Map<Object, TimeSkipObj> RUNNING = new HashMap<>();
    /** Random tick multiplier of each dimension a skip is running in. */
    private static final Map<ServerLevel, Double> RANDOM_TICKS = new HashMap<>();

    private final double total;
    private final boolean hasTime;
    private final Object key;
    private double done;
    private double rate = 1;
    private boolean braking;
    /** What was pushed but not yet a whole tick. */
    private double carried;

    private TimeSkipObj(Node node, Ctx ctx, Vec3 pos, double ticks, boolean hasTime, Object key) {
        super(node, ctx, pos, Integer.MAX_VALUE);
        this.total = ticks;
        this.hasTime = hasTime;
        this.key = key;
    }

    /** Starts a skip of {@code seconds} where {@code ctx} is; refused while another runs on the same time of day. */
    public static void start(Node node, Ctx ctx, Vec3 pos, double seconds) {
        ServerLevel level = ctx.level();
        boolean hasTime = !level.dimensionType().hasFixedTime();
        Object key = hasTime ? level.getLevelData() : level;
        if (RUNNING.containsKey(key)) {
            ctx.inst().terminate(net.minecraft.network.chat.Component.translatable("logos.spell.end.time_skip_running"));
            return;
        }
        TimeSkipObj skip = new TimeSkipObj(node, ctx, pos, seconds * 20, hasTime, key);
        RUNNING.put(key, skip);
        SpellRuntime.spawn(skip);
        if (ctx.inst().dead) skip.onEnded(); // refused by the object limits
    }

    /** Random ticks per section this tick in {@code level}: vanilla's speed, times a running skip's multiplier. */
    public static int randomTickSpeed(ServerLevel level, int speed) {
        if (RANDOM_TICKS.isEmpty()) return speed;
        Double m = RANDOM_TICKS.get(level);
        return m == null ? speed : (int) Math.round(speed * m);
    }

    @Override
    public void tick() {
        double left = total - done;
        if (left <= 1e-6) {
            end();
            return;
        }
        if (!braking && left <= rate * BRAKE_TICKS) braking = true;
        rate = braking ? Math.clamp(left / BRAKE_TICKS, 1, rate) : Math.min(LogosConfig.timeSkipMaxRate(), rate * GROWTH);
        double step = Math.min(rate, left);
        if (!inst.spend(step / 20 * COST_PER_SECOND, "time-skip")) {
            dissolve();
            return;
        }
        done += step;
        RANDOM_TICKS.put(level, Math.min(step, LogosConfig.timeSkipMaxRandomTicks()));
        if (hasTime) push(step);
    }

    /** Moves the time of day on by {@code step} ticks (the tick that passes anyway is one of them) and tells the players here. */
    private void push(double step) {
        carried += step - 1;
        long whole = (long) Math.floor(carried);
        if (whole <= 0) return;
        carried -= whole;
        level.setDayTime(level.getDayTime() + whole);
        level.getServer().getPlayerList().broadcastAll(new ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(),
                level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)), level.dimension());
    }

    @Override
    protected void onEnded() {
        if (RUNNING.get(key) != this) return;
        RUNNING.remove(key);
        RANDOM_TICKS.remove(level);
    }

    /** Ends every skip when the server stops. */
    @SubscribeEvent
    static void onStopping(ServerStoppingEvent event) {
        for (TimeSkipObj skip : RUNNING.values().toArray(TimeSkipObj[]::new)) skip.onEnded();
        RANDOM_TICKS.clear();
    }
}
