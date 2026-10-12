package dev.logos.client.uitest;

import java.util.EnumSet;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.runtime.Familiars;
import dev.logos.runtime.ManaLeech;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Real-client charge, physical lunge, face attachment, fullbright veins, and natural-mother splitting. */
@LDLRegisterClient(name = "logos_mana_leech", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ManaLeechScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    private static final EnumSet<ManaLeech.State> SEEN = EnumSet.noneOf(ManaLeech.State.class);
    private static int watched = -1;
    private ManaLeech leech;
    private Husk prey;

    static {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            ManaLeech current = clientLeech();
            if (current != null) SEEN.add(current.state());
        });
    }

    private static ManaLeech clientLeech() {
        var level = Minecraft.getInstance().level;
        return level != null && level.getEntity(watched) instanceof ManaLeech current ? current : null;
    }

    private static boolean state(ManaLeech.State state, int ticks) {
        ManaLeech current = clientLeech();
        return current != null && current.state() == state && current.stateTicks() >= ticks;
    }

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("logos", "mana_leech", "rendering").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder scenario) {
        scenario.setGameMode(GameType.CREATIVE)
                .runCommand("difficulty normal")
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 5, 5), 10)
                .fill(ORIGIN.offset(-12, 0, -8), ORIGIN.offset(12, 0, 20), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -.5, 0, 15)
                .server("a natural leech and a stationary ordinary familiar", context -> {
                    var level = context.level();
                    prey = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
                    prey.snapTo(.5, 101, 5.5, 180, 0);
                    prey.setYHeadRot(180);
                    prey.setNoAi(true);
                    prey.getAttribute(Attributes.MAX_HEALTH).setBaseValue(400);
                    prey.setHealth(400);
                    Familiars.bindForTest(prey, context.player());
                    level.addFreshEntity(prey);
                    leech = LogosMod.MANA_LEECH.get().create(level, EntitySpawnReason.NATURAL);
                    leech.snapTo(.5, 101, 3.3, 0, 0);
                    leech.finalizeSpawn(level, level.getCurrentDifficultyAt(leech.blockPosition()), EntitySpawnReason.NATURAL, null);
                    leech.setNoAi(true);
                    level.addFreshEntity(leech);
                })
                .step("observe the synchronized states", context -> {
                    watched = leech.getId();
                    SEEN.clear();
                })
                .ticks(20)
                .screenshot("leech_original_idle")
                .server("start the real attack sequence", context -> leech.setNoAi(false))
                .waitUntil("charge is visible", context -> state(ManaLeech.State.CHARGE, 12))
                .screenshot("leech_charge")
                .waitUntil("the entity actually lunges", context -> SEEN.contains(ManaLeech.State.POUNCE))
                .screenshot("leech_pounce")
                .waitUntil("mouth attached to the target", context -> state(ManaLeech.State.ATTACHED, 8))
                .screenshot("leech_face_attached")
                .server("target looks down while attached", context -> prey.setXRot(35))
                .ticks(4)
                .screenshot("leech_face_pitch")
                .runCommand("time set midnight")
                .ticks(4)
                .screenshot("leech_fullbright_veins")
                .waitUntilServer("three actual drains finish", context -> leech.mana() >= 30)
                .server("ordinary familiar loses only the absorbed mana", context -> {
                    if (Math.abs(prey.getHealth() - 370) > .01) throw new AssertionError("ordinary familiar received extra damage: " + prey.getHealth());
                    if (leech.mana() != 30) throw new AssertionError("attachment did not drain exactly 30 mana");
                    if (!leech.canSplit()) throw new AssertionError("natural mother must be able to split");
                    prey.discard();
                    leech.setStoredMana(60);
                    leech.setXRot(0);
                })
                .runCommand("time set noon")
                .waitUntil("split animation starts", context -> state(ManaLeech.State.SPLIT, 12))
                .screenshot("leech_split_charge")
                .waitUntilServer("natural mother becomes two zero-mana children", context ->
                        !leech.isAlive() && context.level().getEntitiesOfClass(ManaLeech.class,
                                leech.getBoundingBox().inflate(8), child -> child.isAlive() && child.isSplitOffspring()).size() == 2)
                .server("offspring pools and origin are correct", context -> {
                    for (var child : context.level().getEntitiesOfClass(ManaLeech.class, leech.getBoundingBox().inflate(8), e -> e.isAlive())) {
                        if (child.mana() != 0 || child.canSplit()) throw new AssertionError("split child must have zero mana and cannot split again");
                        child.setNoAi(true);
                    }
                })
                .ticks(5)
                .screenshot("leech_split_children")
                .step("all synchronized attack states were rendered", context -> {
                    for (var required : new ManaLeech.State[] {ManaLeech.State.CHARGE, ManaLeech.State.POUNCE, ManaLeech.State.ATTACHED, ManaLeech.State.SPLIT}) {
                        if (!SEEN.contains(required)) throw new AssertionError("client missed state " + required);
                    }
                })
                .teardownServer("remove the scene creatures", context -> {
                    if (prey != null) prey.discard();
                    if (leech != null) leech.discard();
                    context.level().getEntitiesOfClass(ManaLeech.class, new net.minecraft.world.phys.AABB(ORIGIN).inflate(24)).forEach(e -> e.discard());
                })
                .teardown("stop observing", context -> watched = -1);
    }
}
