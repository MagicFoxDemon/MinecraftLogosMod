package dev.logos.client.uitest;

import java.util.Map;
import java.util.Set;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.runtime.ManaField;
import dev.logos.runtime.ManaWrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * What a client may not hold on to, in a real client: its own attack leaves {@code ManaWrap}'s statics alone; the mana
 * field's copy forgets chunks out of view (the server tells them again on return) and lets go of a level it leaves. The
 * last goes through a change of dimension, whose {@code LevelEvent.Unload} is the one a disconnect posts too.
 * Run: {@code gradlew runClient -PldTest=logos_state_lifecycle -PldTestHeadless}.
 */
@LDLRegisterClient(name = "logos_state_lifecycle", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class StateLifecycleScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(8, 100, 8);
    private Object swingingAfterHit;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "lifecycle").defaultTimeoutMs(60000).scenarioTimeoutMs(300000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .clearArea(ORIGIN.offset(0, 6, 0), 8)
                .fill(ORIGIN.offset(-5, 0, -5), ORIGIN.offset(5, 0, 5), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 3.5, 180, 0)
                .server("a pig to hit, and mana in the air", sc -> {
                    ServerLevel level = (ServerLevel) sc.player().level();
                    Pig pig = EntityType.PIG.create(level, EntitySpawnReason.TRIGGERED);
                    pig.setNoAi(true);
                    pig.snapTo(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 1.5, 0, 0);
                    level.addFreshEntity(pig);
                    for (int i = 0; i < 60; i++) {
                        ManaField.pour(level, ORIGIN, ManaField.CAP);
                        ManaField.work(level);
                    }
                })
                .waitUntil("the client sees the pig", ctx -> pig() != null)
                .waitUntil("the client knows the air holds mana", ctx -> known() > 0)
                .step("hit the pig", ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.gameMode.attack(mc.player, pig());
                    swingingAfterHit = read(ManaWrap.class, "swinging");
                })
                .check("the client's own attack is not kept", ctx -> !(swingingAfterHit instanceof LocalPlayer))
                .ticks(5)
                .teleportPlayer(4000.5, 120, 8.5, 0, 0)
                .waitUntil("out of view, the chunk is forgotten", ctx -> known() == 0)
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 3.5, 180, 0)
                .waitUntil("back in view, the server tells it again", ctx -> known() > 0)
                .server("off to the Nether", sc -> sc.player().teleportTo(sc.server().getLevel(Level.NETHER), 0.5, 120, 0.5, Set.of(), 0, 0, true))
                .waitUntil("the client is in the Nether", ctx -> Minecraft.getInstance().level.dimension() == Level.NETHER)
                .check("nothing of the last level is held", ctx -> read(ManaField.class, "clientLevel") == null
                        && ((Map<?, ?>) read(ManaField.class, "CLIENT")).isEmpty());
        s.teardownServer("back, and the pig away", sc -> {
            ServerLevel overworld = sc.server().overworld();
            sc.player().teleportTo(overworld, ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 3.5, Set.of(), 180, 0, true);
            overworld.getEntitiesOfClass(Pig.class, new AABB(ORIGIN).inflate(6)).forEach(Entity::discard);
        });
    }

    private static int known() {
        return ManaField.clientLevel(Minecraft.getInstance().level, ORIGIN);
    }

    private static Pig pig() {
        var pigs = Minecraft.getInstance().level.getEntitiesOfClass(Pig.class, new AABB(ORIGIN).inflate(6));
        return pigs.isEmpty() ? null : pigs.getFirst();
    }

    private static Object read(Class<?> owner, String name) {
        try {
            var field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
