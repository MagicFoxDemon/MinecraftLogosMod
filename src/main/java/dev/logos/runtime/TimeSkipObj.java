package dev.logos.runtime;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.lang.Node;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * 时间加速: pushes time forward by a set amount, ever faster up to a cap, braking hard near the end.
 * Where the cast's dimension has a clock (whatever its type declares, so other mods' dimensions too),
 * the clock's rate is raised: clients get the rate and turn the sky smoothly. Random ticks in that one
 * dimension speed up along the same curve, under a much lower cap; a dimension without a clock gets
 * only those. Paid as it goes, 4 mana for each second (20 ticks) pushed through.
 */
public final class TimeSkipObj extends SpellObject {
    public static final double COST_PER_SECOND = 4;
    /** How fast the rate climbs: × this every tick. */
    static final double GROWTH = 1.05;
    /** Braking starts when what is left would pass in this many ticks at the current rate, then decays over about as many. */
    static final double BRAKE_TICKS = 6;

    /** The skip running on each clock (or, for a dimension without one, on that dimension). */
    private static final Map<Object, TimeSkipObj> RUNNING = new HashMap<>();
    /** Random tick multiplier of each dimension a skip is running in. */
    private static final Map<ServerLevel, Double> RANDOM_TICKS = new HashMap<>();

    private final double total;
    private final @Nullable Holder<WorldClock> clock;
    private final Object key;
    private final float originalRate;
    private double done;
    private double rate = 1;
    private boolean braking;
    /** The rate last handed to the clock, and its tick count then; a clock that does not move (paused) is pushed by hand. */
    private float setRate = 1;
    private long lastTotal = -1;
    private boolean byHand;

    private TimeSkipObj(Node node, Ctx ctx, Vec3 pos, double ticks, @Nullable Holder<WorldClock> clock, Object key) {
        super(node, ctx, pos, Integer.MAX_VALUE);
        this.total = ticks;
        this.clock = clock;
        this.key = key;
        this.originalRate = clock == null ? 1 : clocks(level.getServer()).getRate(clock);
    }

    /** Starts a skip of {@code seconds} where {@code ctx} is; refused while another runs on the same clock. */
    public static void start(Node node, Ctx ctx, Vec3 pos, double seconds) {
        ServerLevel level = ctx.level();
        Holder<WorldClock> clock = level.dimensionType().hasFixedTime() ? null : level.dimensionType().defaultClock().orElse(null);
        Object key = clock != null ? clock : level;
        if (RUNNING.containsKey(key)) {
            ctx.inst().terminate("时间已在加速之中，无法叠加");
            return;
        }
        TimeSkipObj skip = new TimeSkipObj(node, ctx, pos, seconds * 20, clock, key);
        RUNNING.put(key, skip);
        if (clock != null) Saved.of(level.getServer()).remember(clock, skip.originalRate);
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
            dissolve(); // the clock slows back at once, not a tick after the spell is gone
            return;
        }
        done += step;
        RANDOM_TICKS.put(level, Math.min(step, LogosConfig.timeSkipMaxRandomTicks()));
        if (clock != null) push(step);
    }

    private void push(double step) {
        ServerClockManager clocks = clocks(level.getServer());
        long now = clocks.getTotalTicks(clock);
        if (!byHand && lastTotal >= 0 && setRate > 1.5f && now - lastTotal < setRate * 0.5) byHand = true;
        lastTotal = now;
        if (byHand) {
            clocks.addTicks(clock, (int) Math.round(step));
        } else if (Math.abs(step - setRate) > setRate * 0.02) {
            // Each change goes to every player; small ones are left out.
            setRate = (float) step;
            clocks.setRate(clock, setRate);
        }
    }

    @Override
    protected void onEnded() {
        if (RUNNING.get(key) != this) return;
        RUNNING.remove(key);
        RANDOM_TICKS.remove(level);
        if (clock != null) {
            clocks(level.getServer()).setRate(clock, originalRate);
            Saved.of(level.getServer()).forget(clock);
        }
    }

    private static ServerClockManager clocks(MinecraftServer server) {
        return server.clockManager();
    }

    /** Ends every skip before the world is saved, so no raised rate is written. */
    @SubscribeEvent
    static void onStopping(ServerStoppingEvent event) {
        for (TimeSkipObj skip : RUNNING.values().toArray(TimeSkipObj[]::new)) skip.onEnded();
        RANDOM_TICKS.clear();
    }

    /** A server that stopped without ending its skips (a crash) puts their clocks' rates back when it starts again. */
    @SubscribeEvent
    static void onStarted(ServerStartedEvent event) {
        Saved.of(event.getServer()).restoreAll(event.getServer());
    }

    /**
     * Each clock's rate from before a running skip raised it. The raised rate is saved with the clock,
     * so this is saved too, to undo it after a crash.
     */
    static final class Saved extends SavedData {
        static final SavedDataType<Saved> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "time_skip"),
                Saved::new, Codec.unboundedMap(Identifier.CODEC, Codec.FLOAT).xmap(Saved::new, s -> s.original));

        private final Map<Identifier, Float> original;

        Saved() {
            this(Map.of());
        }

        Saved(Map<Identifier, Float> original) {
            this.original = new HashMap<>(original);
        }

        static Saved of(MinecraftServer server) {
            return server.getDataStorage().computeIfAbsent(TYPE);
        }

        void remember(Holder<WorldClock> clock, float rate) {
            clock.unwrapKey().ifPresent(k -> original.put(k.identifier(), rate));
            setDirty();
        }

        void forget(Holder<WorldClock> clock) {
            clock.unwrapKey().ifPresent(k -> original.remove(k.identifier()));
            setDirty();
        }

        void restoreAll(MinecraftServer server) {
            if (original.isEmpty()) return;
            var registry = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK);
            original.forEach((id, rate) -> registry.get(ResourceKey.create(Registries.WORLD_CLOCK, id))
                    .ifPresent(clock -> server.clockManager().setRate(clock, rate)));
            original.clear();
            setDirty();
        }
    }
}
