package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.client.WardSpheres;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Effects;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * The shield sphere from real {@code shield} casts: winding up on a cow, held by day and night, on a large iron
 * golem, on the caster seen in third person from behind and in front, unseen from the caster's own eyes, and
 * breaking when the cow's shield is knocked off.
 */
@LDLRegisterClient(name = "logos_ward", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class WardScenario implements UIScenario {
    private static LivingEntity cow, golem;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "burst", "ward").guiScale(2); }

    private static void cast(ServerPlayer player, String source) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        if (Casting.cast(player, Casting.compile(source), null, null) == null)
            throw new IllegalStateException("real test spell was refused: " + source);
    }

    private static <T extends Mob> T spawn(net.minecraft.server.level.ServerLevel level, EntityType<T> type, double x, double z, float yaw) {
        T mob = type.create(level, EntitySpawnReason.COMMAND);
        if (mob == null) throw new IllegalStateException("could not create " + type);
        mob.snapTo(x, 101, z, yaw, 0);
        mob.setYHeadRot(yaw);
        mob.setNoAi(true);
        level.addFreshEntity(mob);
        return mob;
    }

    private static void held(Entity body, boolean expected, String what) {
        Entity client = Minecraft.getInstance().level.getEntity(body.getId());
        if (client == null || WardSpheres.holds(client) != expected)
            throw new AssertionError(what + (expected ? " should hold a ward sphere" : " should have lost its ward sphere"));
    }

    private static void camera(CameraType type) { Minecraft.getInstance().options.setCameraType(type); }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 104, 3), 10)
                .fill(new BlockPos(-10, 100, -6), new BlockPos(10, 100, 14), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(10);

        s.server("a cow ahead", sc -> cow = spawn(sc.level(), EntityType.COW, .5, 3, 160))
                .ticks(5)
                .server("real shield on the cow", sc -> cast(sc.player(), "(shield (nearest :radius 12) 4)"))
                .ticks(2).screenshot("ward_forming_early")
                .ticks(5).screenshot("ward_forming")
                .ticks(10).screenshot("ward_formed")
                .ticks(30).screenshot("ward_held_day")
                .step("the cow holds a sphere", ctx -> held(cow, true, "the shielded cow"))
                .runCommand("time set midnight").ticks(5).screenshot("ward_held_night")
                .runCommand("time set noon").ticks(3);

        // A large body: cast from beside the golem so it is the nearest, then look at it from back at the start.
        s.server("an iron golem to the side", sc -> golem = spawn(sc.level(), EntityType.IRON_GOLEM, 4.5, 6, 200))
                .teleportPlayer(4.5, 101, 3.5, 0, 0).ticks(5)
                .server("real shield on the golem", sc -> cast(sc.player(), "(shield (nearest :radius 3) 4)"))
                .teleportPlayer(-.5, 101, -3, -25, 5).ticks(30).screenshot("ward_large")
                .step("the golem holds a sphere", ctx -> held(golem, true, "the shielded golem"));

        // The caster's own ward.
        s.teleportPlayer(-4.5, 101, 1, -90, 0).ticks(3)
                .server("real shield on the caster", sc -> cast(sc.player(), "(shield caster 4)"))
                .step("third person, from behind", ctx -> camera(CameraType.THIRD_PERSON_BACK))
                .ticks(4).screenshot("ward_self_forming")
                .ticks(30).screenshot("ward_self_back")
                .step("third person, from the front", ctx -> camera(CameraType.THIRD_PERSON_FRONT))
                .ticks(3).screenshot("ward_self_front")
                .step("first person", ctx -> camera(CameraType.FIRST_PERSON))
                .ticks(3).screenshot("ward_self_first_person")
                .step("the caster holds a sphere", ctx -> held(Minecraft.getInstance().player, true, "the shielded caster"));

        // Knock the cow's shield off: 6 damage against 4 absorption.
        s.teleportPlayer(.5, 101, -2, 0, 0).ticks(3)
                .server("break the cow's shield", sc -> {
                    cow.hurtServer(sc.level(), sc.level().damageSources().generic(), 6);
                    if (Effects.shielded(cow)) throw new IllegalStateException("the cow's shield should be broken");
                })
                .ticks(2).screenshot("ward_break")
                .ticks(5).screenshot("ward_break_late")
                .step("the cow lost its sphere", ctx -> held(cow, false, "the cow"))
                .ticks(20).screenshot("ward_after_break");

        s.teardown("remove preview targets", ctx -> {
            camera(CameraType.FIRST_PERSON);
            if (cow != null) { cow.discard(); cow = null; }
            if (golem != null) { golem.discard(); golem = null; }
        });
    }
}
