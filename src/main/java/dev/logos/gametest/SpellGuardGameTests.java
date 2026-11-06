package dev.logos.gametest;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import dev.logos.LogosMod;
import dev.logos.lang.Node;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Ctx;
import dev.logos.runtime.Effects;
import dev.logos.runtime.ManaWrap;
import dev.logos.runtime.SpellInstance;
import dev.logos.runtime.SpellObject;
import dev.logos.runtime.SpellRuntime;
import dev.logos.runtime.WrapObj;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Spell code that crashes where the scheduler is not watching: the server keeps running, the spell goes out and the log
 * says why. Without the guards most of these would stop the whole test server. What throws is a damage listener, as
 * another mod's entity or listener might, and only for a spell's damage to the creatures a test has {@linkplain #trap trapped}.
 */
final class SpellGuardGameTests {
    private static final String CRASH = "guard test crash";
    private static final String OVERFLOW = "栈溢出";
    /** The creatures a spell's damage crashes on; whether as a stack overflow. */
    private static final Map<Entity, Boolean> TRAPPED = new WeakHashMap<>();
    /** What the mod logged at ERROR with a stack since {@link #arm}. */
    private static final List<LogEvent> ERRORS = new CopyOnWriteArrayList<>();
    private static boolean armed;

    private SpellGuardGameTests() {}

    private static void arm() {
        if (armed) return;
        armed = true;
        NeoForge.EVENT_BUS.addListener((LivingIncomingDamageEvent event) -> {
            Boolean overflow = Effects.inSpell() ? TRAPPED.get(event.getEntity()) : null;
            if (overflow == null) return;
            if (overflow) throw new StackOverflowError(CRASH);
            throw new IllegalStateException(CRASH);
        });
        AbstractAppender errors = new AbstractAppender("logos-spell-guard-test", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent e) {
                if (e.getLevel() == Level.ERROR && e.getThrown() != null && LogosMod.LOGGER.getName().equals(e.getLoggerName())) ERRORS.add(e.toImmutable());
            }
        };
        errors.start();
        // On the root, as a new config for the mod's own logger would cut it off from the console (the root is not additive).
        ((Logger) LogManager.getRootLogger()).addAppender(errors);
    }

    private static void trap(Entity e, boolean overflow) {
        arm();
        TRAPPED.put(e, overflow);
    }

    private static long logged(String what) {
        return ERRORS.stream().filter(e -> e.getMessage().getFormattedMessage().contains(what)).count();
    }

    private static ServerPlayer caster(GameTestHelper h) {
        arm();
        return ConstructGameTests.caster(h);
    }

    private static <T extends Mob> T mob(GameTestHelper h, EntityType<T> type, double x, double z) {
        T e = h.spawn(type, new Vec3(x, 2, z));
        e.setNoAi(true);
        return e;
    }

    private static SpellInstance cast(GameTestHelper h, ServerPlayer p, String name, String body) {
        SpellInstance inst = Casting.cast(p, Casting.compile("(spell :name \"" + name + "\" :mana 200 " + body + ")"), null, null);
        h.assertTrue(inst != null && !inst.dead, name + " should start, got " + (inst == null ? "nothing" : inst.endReason()));
        return inst;
    }

    private static void assertCrashed(GameTestHelper h, SpellInstance inst, String name, String reason) {
        h.assertTrue(inst.dead && inst.endReason() != null && inst.endReason().contains(reason), name + " should go out over the crash, got " + inst.endReason());
        h.assertTrue(inst.objects == 0, name + " should have no bodies left, has " + inst.objects);
        h.assertTrue(logged(name) > 0, name + " should have logged its stack");
    }

    /** A wrap's on-hit crashes in the deferred queue: the spell goes out, the hand is cleared, the next task still runs. */
    static void wrapOnHit(GameTestHelper h) {
        wrapCrash(h, "guard-wrap-hit", false, CRASH);
    }

    /** The same for a stack overflow, which is not a RuntimeException. */
    static void wrapOverflow(GameTestHelper h) {
        wrapCrash(h, "guard-wrap-overflow", true, OVERFLOW);
    }

    private static void wrapCrash(GameTestHelper h, String name, boolean overflow, String reason) {
        ServerPlayer p = caster(h);
        var holder = mob(h, EntityType.HUSK, 4.5, 3.5);
        var victim = mob(h, EntityType.PIG, 4.5, 6.5);
        trap(victim, overflow);
        SpellInstance inst = cast(h, p, name, "(imbue (nearest :radius 2.5) :life 10 (on-hit (damage target 3 arcane)))");
        h.assertTrue(WrapObj.on(holder) != null, "the husk's hand should be wrapped");
        boolean[] next = {false};
        holder.doHurtTarget(h.getLevel(), victim);
        LogosMod.defer(() -> next[0] = true);
        h.succeedWhen(() -> {
            h.assertTrue(next[0], "the task deferred after the crash should still run");
            assertCrashed(h, inst, name, reason);
            h.assertTrue(WrapObj.on(holder) == null && ManaWrap.of(holder) == null, "the hand should be cleared");
        });
    }

    /** A wrap's on-expire crashes on its way out: it still leaves the hand and settles its books. */
    static void wrapOnExpire(GameTestHelper h) {
        String name = "guard-wrap-expire";
        ServerPlayer p = caster(h);
        var holder = mob(h, EntityType.HUSK, 4.5, 3.5);
        trap(holder, false);
        SpellInstance inst = cast(h, p, name, "(imbue (nearest :radius 2.5) :life 0.5 (on-expire (damage self 1 arcane)))");
        h.succeedWhen(() -> {
            assertCrashed(h, inst, name, CRASH);
            h.assertTrue(WrapObj.on(holder) == null && ManaWrap.of(holder) == null, "the hand should be cleared");
        });
    }

    /** A new wrap replaces an old one whose on-expire crashes: the old spell pays for it, not the one that replaced it. */
    static void wrapReplaced(GameTestHelper h) {
        String name = "guard-wrap-replaced";
        ServerPlayer p = caster(h);
        var holder = mob(h, EntityType.HUSK, 4.5, 3.5);
        trap(holder, false);
        SpellInstance old = cast(h, p, name, "(imbue (nearest :radius 2.5) :life 30 (on-expire (damage self 1 arcane)))");
        SpellInstance next = cast(h, p, "guard-wrap-next", "(imbue (nearest :radius 2.5) :life 30)");
        assertCrashed(h, old, name, CRASH);
        WrapObj worn = WrapObj.on(holder);
        h.assertTrue(!next.dead && worn != null, "the new wrap should stand, got " + next.endReason());
        worn.disperse();
        h.succeed();
    }

    /** A construct's on-block crashes inside the arrow's own tick: the arrow is still stopped and the spell goes out. */
    static void constructOnBlock(GameTestHelper h) {
        constructCrash(h, "guard-construct-block", "(on-block (damage (nearest :radius 6) 1 arcane))");
    }

    /** The same for on-pass, as an arrow goes through. */
    static void constructOnPass(GameTestHelper h) {
        constructCrash(h, "guard-construct-pass", ":solid (kind none) (on-pass (damage (nearest :radius 6) 1 arcane))");
    }

    private static void constructCrash(GameTestHelper h, String name, String events) {
        ServerPlayer p = caster(h);
        var near = mob(h, EntityType.HUSK, 7.5, 8.5);
        trap(near, false);
        ConstructGameTests.inTickingLane(h, () -> {
            SpellInstance inst = Casting.cast(p, Casting.compile("(spell :name \"" + name + "\" :mana 200 (construct :shape wall :at here :width 5 :height 3 :life 12 " + events + "))"),
                    null, Vec3.atCenterOf(h.absolutePos(new BlockPos(4, 3, 6))));
            h.assertTrue(inst != null && !inst.dead, name + " should start");
            ConstructGameTests.arrow(h, 3.5, 3.5, 12.5, -1.2);
            h.succeedWhen(() -> assertCrashed(h, inst, name, CRASH));
        });
    }

    /** One failing deferred task costs neither the others nor the scheduler its tick; each failure is logged. */
    static void deferredTasks(GameTestHelper h) {
        arm();
        long before = SpellRuntime.now();
        boolean[] next = {false};
        LogosMod.defer(() -> {
            throw new IllegalStateException(CRASH);
        });
        LogosMod.defer(() -> {
            throw new StackOverflowError(CRASH);
        });
        LogosMod.defer(() -> next[0] = true);
        h.succeedWhen(() -> {
            h.assertTrue(next[0], "the task deferred after the failures should still run");
            h.assertTrue(SpellRuntime.now() > before, "the scheduler should still tick");
            h.assertTrue(logged("Deferred task") >= 2, "both failures should be logged");
        });
    }

    /** A crash in a scheduled continuation is logged too; the other spells' continuations go on. */
    static void scheduledTask(GameTestHelper h) {
        String name = "guard-scheduled";
        ServerPlayer p = caster(h);
        SpellInstance bad = instance(h, p, name);
        boolean[] next = {false};
        SpellRuntime.schedule(bad, 0, () -> {
            throw new IllegalStateException(CRASH);
        });
        SpellRuntime.schedule(instance(h, p, "guard-scheduled-neighbour"), 0, () -> next[0] = true);
        h.succeedWhen(() -> {
            assertCrashed(h, bad, name, CRASH);
            h.assertTrue(next[0], "the other spell's continuation should still run");
        });
    }

    /** A body that crashes while cleaning up (once its tick has crashed, or ended by hand) takes only its own spell with it. */
    static void cleanupCrash(GameTestHelper h) {
        String name = "guard-cleanup";
        String direct = "guard-cleanup-direct";
        ServerPlayer p = caster(h);
        SpellInstance bad = instance(h, p, name);
        SpellInstance good = instance(h, p, "guard-cleanup-neighbour");
        Body crashing = body(h, bad, true);
        Body neighbour = body(h, good, false);
        SpellRuntime.spawn(crashing);
        SpellRuntime.spawn(neighbour);

        SpellInstance byHand = instance(h, p, direct);
        Body ended = body(h, byHand, true);
        SpellRuntime.spawn(ended);
        ended.disperse();
        assertCrashed(h, byHand, direct, CRASH);

        h.succeedWhen(() -> {
            assertCrashed(h, bad, name, CRASH);
            h.assertTrue(crashing.cleanups == 1, "cleanup should have run once, ran " + crashing.cleanups);
            h.assertTrue(neighbour.ticks > 0 && !good.dead, "the body after it should still tick");
            neighbour.disperse();
        });
    }

    /** A spell that crashes on every cast logs one stack, not one per cast. */
    static void logThrottled(GameTestHelper h) {
        String name = "guard-throttled";
        ServerPlayer p = caster(h);
        trap(mob(h, EntityType.HUSK, 4.5, 3.5), false);
        Spell spell = Casting.compile("(spell :name \"" + name + "\" :mana 5 (damage (nearest :radius 3) 1 arcane))");
        for (int k = 0; k < 3; k++) {
            SpellInstance inst = Casting.cast(p, spell, null, null);
            h.assertTrue(inst != null && inst.dead && inst.endReason().contains(CRASH), "every cast should go out over the crash");
        }
        h.assertTrue(logged(name) == 1, "three crashes should log one stack, logged " + logged(name));
        h.succeed();
    }

    private static SpellInstance instance(GameTestHelper h, ServerPlayer p, String name) {
        return new SpellInstance(Casting.compile("(spell :name \"" + name + "\" :mana 100 (field))"), p, h.getLevel(), 100, 1);
    }

    private static Body body(GameTestHelper h, SpellInstance inst, boolean crashes) {
        Ctx c = new Ctx(inst, h.getLevel(), h.absoluteVec(new Vec3(1.5, 3, 1.5)), new Vec3(0, 0, 1), null, Map.of(), 0, 0);
        return new Body(inst.spell.body().getFirst(), c, crashes);
    }

    /** A spell body that can crash both in its tick and in its cleanup. */
    private static final class Body extends SpellObject {
        private final boolean crashes;
        int ticks;
        int cleanups;

        Body(Node node, Ctx ctx, boolean crashes) {
            super(node, ctx, ctx.here(), 100);
            this.crashes = crashes;
        }

        @Override
        public void tick() {
            ticks++;
            if (crashes) throw new IllegalStateException(CRASH);
            if (++age > life) end();
        }

        @Override
        protected void onEnded() {
            cleanups++;
            if (crashes) throw new IllegalStateException(CRASH);
        }
    }
}
