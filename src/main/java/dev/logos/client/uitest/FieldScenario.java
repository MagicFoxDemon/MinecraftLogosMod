package dev.logos.client.uitest;

import java.util.ArrayList;
import java.util.List;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.FieldVisual;
import dev.logos.client.FieldVisuals;
import dev.logos.client.LiveField;
import dev.logos.lang.Element;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * The shared field entry (FIELD_VFX.md), from real casts: with a recording visual standing in for every
 * element's, a field is kept with its radius, life and pulse period, lies on the ground under its centre,
 * pulses, notices a cow walking in, ends and is dropped once played out; a following field stays on its
 * caster. Then, with the elements' own visuals back, a legacy element is not kept here.
 */
@LDLRegisterClient(name = "logos_field", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FieldScenario implements UIScenario {
    /** What the recording visuals saw: "pulse", "enter", "ended", "end" (dropped). */
    private static final List<String> SEEN = new ArrayList<>();
    private static final int LINGER = 10;
    private static Cow cow;

    private static final class Recording implements FieldVisual {
        @Override
        public boolean legacy() { return false; }

        @Override
        public boolean tick(Level level, LiveField f) {
            for (int i = 0; i < f.newPulses().size(); i++) SEEN.add("pulse");
            for (int i = 0; i < f.newEntries().size(); i++) SEEN.add("enter");
            if (f.phase() == LiveField.Phase.ENDED && !SEEN.contains("ended")) SEEN.add("ended");
            return f.phase() == LiveField.Phase.ACTIVE || f.sinceEnd(0) < LINGER;
        }

        @Override
        public void end() { SEEN.add("end"); }
    }

    private static void cast(ServerPlayer player, String source) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        if (Casting.cast(player, Casting.compile(source), null, null) == null)
            throw new IllegalStateException("real test spell was refused: " + source);
    }

    private static LiveField only() {
        List<LiveField> all = FieldVisuals.all();
        if (all.size() != 1) throw new AssertionError("expected one field, got " + all.size() + "; seen " + SEEN);
        return all.getFirst();
    }

    private static void expect(boolean ok, String what) {
        if (!ok) throw new AssertionError(what + "; seen " + SEEN);
    }

    private static long count(String what) {
        return SEEN.stream().filter(what::equals).count();
    }

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("logos", "vfx", "field").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon")
                .clearArea(new BlockPos(0, 106, 0), 10)
                .fill(new BlockPos(-8, 100, -8), new BlockPos(8, 100, 8), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, .5, 0, 10)
                .step("record every element's field", ctx -> {
                    FieldVisuals.substitute(e -> new Recording());
                    SEEN.clear();
                })
                .ticks(2);

        // A field here: kept with its shape, laid on the ground, pulsing, noticing a cow, then ended and dropped.
        s.server("cast a field with a cow nearby", sc -> {
                    cow = EntityType.COW.create(sc.level(), EntitySpawnReason.COMMAND);
                    if (cow == null) throw new IllegalStateException("cow creation failed");
                    cow.setPos(7.5, 101, .5);
                    cow.setNoAi(true);
                    sc.level().addFreshEntity(cow);
                    cast(sc.player(), "(field :radius 4 :life 3 :every 0.5 (on-enter (glow target 1)))");
                })
                .ticks(6)
                .check("the field is kept with its shape", ctx -> {
                    LiveField f = only();
                    expect(f.phase() == LiveField.Phase.ACTIVE && Math.abs(f.radius() - 4) < 1e-3 && f.life() == 60 && f.every() == 10,
                            "radius 4, life 60, every 10: got " + f.radius() + ", " + f.life() + ", " + f.every());
                    expect(!f.follows(), "it does not follow");
                    expect(Math.abs(f.ground() - 101) < 1e-3, "it lies on the stone at 101, got " + f.ground());
                    float p = f.progress(0);
                    expect(p > 0 && p < .25f, "its clock has just started: " + p);
                    return true;
                })
                .server("the cow walks in", sc -> cow.setPos(2.5, 101, .5))
                .ticks(20)
                .check("it pulsed and noticed the cow", ctx -> {
                    expect(count("pulse") >= 2, "two pulses in the first second and a half");
                    expect(count("enter") == 1, "the cow entered once");
                    return only().pulses() >= 2;
                })
                .ticks(40)
                .check("it ended", ctx -> {
                    expect(SEEN.contains("ended"), "its end was heard");
                    return true;
                })
                .ticks(LINGER + 4)
                .check("and was dropped once played out", ctx -> FieldVisuals.all().isEmpty() && SEEN.getLast().equals("end"))
                .server("remove the cow", sc -> { if (cow != null) { cow.discard(); cow = null; } });

        // A following field stays on its caster.
        s.step("clear", ctx -> SEEN.clear())
                .server("cast a following field", sc -> cast(sc.player(), "(field :radius 3 :life 2 :every 1 :follow)"))
                .ticks(6)
                .teleportPlayer(3.5, 101, 2.5, 0, 10)
                .ticks(3)
                .check("it follows its caster", ctx -> {
                    LiveField f = only();
                    var player = Minecraft.getInstance().player;
                    expect(f.follows(), "it follows");
                    expect(f.centre(0).distanceTo(player.position().add(0, 1, 0)) < .05, "its centre is on the caster: " + f.centre(0));
                    return true;
                })
                .ticks(40)
                .check("it ended", ctx -> SEEN.contains("ended"))
                .ticks(LINGER + 4)
                .check("and was dropped", ctx -> FieldVisuals.all().isEmpty());

        // The elements' own visuals again: a legacy element is not kept here.
        s.step("the elements' own visuals", ctx -> FieldVisuals.substitute(null))
                .server("an arcane field", sc -> cast(sc.player(), "(field :radius 2 :life 1 :every 0.5)"))
                .ticks(6)
                .check("a legacy element is left to the particles", ctx -> FieldVisuals.drawsOwn(Element.ARCANE) || FieldVisuals.all().isEmpty());
        s.teardown("restore", ctx -> {
            FieldVisuals.substitute(null);
            if (cow != null) { cow.discard(); cow = null; }
        });
    }
}
