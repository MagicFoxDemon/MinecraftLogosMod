package dev.logos.client.uitest;

import java.util.Map;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.client.LogosFx;
import dev.logos.lang.Element;
import dev.logos.net.FxPayload;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.Vfx;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The push / pull / disarm burst: its stages, night, size, side views of both directions, many small pulls at once,
 * and real push, pull and disarm spells on mobs.
 */
@LDLRegisterClient(name = "logos_force_burst", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ForceBurstScenario implements UIScenario {
    private static final Vec3 EYE = new Vec3(.5, 102.62, -2);
    private static Cow cow;
    private static Zombie zombie;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "burst").guiScale(2); }

    /** A force on a body centred at (x, y, z) acting along {@code dir}, drawn the way {@code LogosFx} draws it. */
    private static void burst(float x, float y, float z, Vec3 dir, float scale) {
        Vec3 d = dir.normalize();
        LogosFx.handle(new FxPayload((byte) FxPayload.Kind.BURST.ordinal(), (byte) Element.PHYSICAL.ordinal(),
                (byte) Vfx.Burst.FORCE.ordinal(), x, y, z, (float) d.x, (float) d.y, (float) d.z, scale, 0, -1, 0, -1));
    }

    /** A close push straight away from the player and a farther one to the side. */
    private static void pair(float scale) {
        burst(-.6f, 102.2f, 2.5f, new Vec3(-.6, 102.2, 2.5).subtract(EYE), scale);
        burst(2.6f, 102.2f, 6, new Vec3(2.6, 102.2, 6).subtract(EYE), scale);
    }

    private static Map<?, ?> cache() {
        try {
            var field = LogosFx.class.getDeclaredField("CACHE");
            field.setAccessible(true);
            return (Map<?, ?>) field.get(null);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static void cast(ServerPlayer player, String spell) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        if (Casting.cast(player, Casting.compile(spell), null, null) == null)
            throw new IllegalStateException("real test spell was refused: " + spell);
    }

    private static boolean routed() {
        return cache().containsKey("effect_burst/PHYSICAL/" + Vfx.Burst.FORCE.ordinal());
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 104, 3), 12)
                .fill(new BlockPos(-14, 100, -8), new BlockPos(14, 100, 18), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(10);
        // Fresh bursts for every age sample keep screenshot readback time out of the next sample.
        s.step("contact", ctx -> pair(1)).ticks(1).screenshot("force_contact").ticks(24)
                .step("peak", ctx -> pair(1)).ticks(2).screenshot("force_peak").ticks(24)
                .step("travel", ctx -> pair(1)).ticks(4).screenshot("force_travel").ticks(24)
                .step("fade", ctx -> pair(1)).ticks(5).screenshot("force_fade")
                .ticks(20).screenshot("force_cleared");
        s.runCommand("time set midnight").ticks(3)
                .step("night", ctx -> pair(1)).ticks(3).screenshot("force_night").ticks(24)
                .step("large targets", ctx -> pair(2)).ticks(3).screenshot("force_large").ticks(24);
        s.runCommand("time set noon").ticks(3)
                .step("a dozen small targets pulled toward the player", ctx -> {
                    for (int i = 0; i < 12; i++) {
                        float x = -2.5f + (i % 4) * 1.6f, z = 2 + (i / 4) * 1.5f;
                        burst(x, 101.15f, z, EYE.subtract(x, 101.15, z), .6f);
                    }
                }).ticks(3).screenshot("force_many_small").ticks(24);
        s.teleportPlayer(-5.5, 101, 5, -90, 0).ticks(5)
                .step("side view of a push along +Z", ctx -> burst(.5f, 102.2f, 5, new Vec3(0, 0, 1), 1))
                .ticks(2).screenshot("force_side_push").ticks(24)
                .step("side view of a pull along -Z", ctx -> burst(.5f, 102.2f, 5, new Vec3(0, 0, -1), 1))
                .ticks(2).screenshot("force_side_pull").ticks(24)
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(5);

        s.step("reset routing observation", ctx -> cache().clear())
                .server("a cow to push and pull", sc -> {
                    cow = EntityType.COW.create(sc.level(), EntitySpawnReason.COMMAND);
                    if (cow == null) throw new IllegalStateException("cow creation failed");
                    cow.setPos(.5, 101, 3);
                    sc.level().addFreshEntity(cow);
                }).ticks(3)
                .server("real push", sc -> cast(sc.player(), "(push (nearest :radius 12) 0.5)"))
                .ticks(2).screenshot("force_real_push").ticks(20)
                .check("push takes the force burst", ctx -> routed())
                .server("the push moved the cow", sc -> {
                    if (cow == null || cow.position().distanceTo(new Vec3(.5, 101, 3)) < .05)
                        throw new IllegalStateException("push did not move the cow");
                })
                .server("real pull", sc -> cast(sc.player(), "(pull (nearest :radius 12) 0.5)"))
                .ticks(2).screenshot("force_real_pull").ticks(20)
                .server("remove the cow", sc -> {
                    if (cow != null) cow.discard();
                    cow = null;
                });
        s.runCommand("difficulty normal")
                .step("reset routing observation", ctx -> cache().clear())
                .server("a zombie holding a sword", sc -> {
                    zombie = EntityType.ZOMBIE.create(sc.level(), EntitySpawnReason.COMMAND);
                    if (zombie == null) throw new IllegalStateException("zombie creation failed");
                    zombie.snapTo(.5, 101, 3, 180, 0);
                    zombie.setNoAi(true);
                    zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                    sc.level().addFreshEntity(zombie);
                }).ticks(3)
                .server("real disarm", sc -> cast(sc.player(), "(disarm (nearest :radius 12))"))
                .ticks(4).screenshot("force_real_disarm").ticks(20)
                .check("disarm takes the force burst", ctx -> routed())
                .server("the sword was dropped", sc -> {
                    if (zombie == null || !zombie.getMainHandItem().isEmpty())
                        throw new IllegalStateException("disarm did not take the sword");
                    if (sc.level().getEntitiesOfClass(ItemEntity.class, new AABB(zombie.blockPosition()).inflate(4),
                            i -> i.getItem().is(Items.IRON_SWORD)).isEmpty())
                        throw new IllegalStateException("the sword was not dropped");
                });
        s.teardown("remove preview targets", ctx -> {
            if (cow != null) { cow.discard(); cow = null; }
            if (zombie != null) { zombie.discard(); zombie = null; }
        });
    }
}
