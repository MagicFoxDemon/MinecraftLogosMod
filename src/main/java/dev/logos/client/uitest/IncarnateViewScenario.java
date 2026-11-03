package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.IncarnationView;
import dev.logos.runtime.Incarnation;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Real-client check of incarnation: the player becomes a zombie (hands: it holds their sword and
 * wears their helmet, seen from behind and walking), then a pig (no hand in first person, a pig
 * from behind), then a bat that flies, and is themself again once it wears off.
 * Run: {@code gradlew runClient -PldTest=logos_incarnate_view}.
 */
@LDLRegisterClient(name = "logos_incarnate_view", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class IncarnateViewScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    private Vec3 walkedFrom;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "incarnate").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 4), 10)
                .fill(ORIGIN.offset(-10, 0, -8), ORIGIN.offset(10, 0, 14), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 0.5, 0, 10)
                .server("become a zombie holding a sword", sc -> {
                    var p = sc.player();
                    p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                    p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
                    if (!Incarnation.start(p, EntityType.ZOMBIE, 20)) throw new AssertionError("the incarnation did not start");
                })
                .ticks(10)
                .step("the client draws a zombie", ctx -> {
                    var p = Minecraft.getInstance().player;
                    if (Incarnation.formType(p) != EntityType.ZOMBIE) throw new AssertionError("the client does not see the form");
                    if (!(IncarnationView.shell(p) instanceof Zombie z)) throw new AssertionError("no zombie shell: " + IncarnationView.shell(p));
                    if (!z.getMainHandItem().is(Items.IRON_SWORD)) throw new AssertionError("the zombie does not hold the sword");
                    System.out.printf("[logos] incarnate: zombie %.2f x %.2f, eyes %.2f%n", p.getBbWidth(), p.getBbHeight(), p.getEyeHeight());
                    if (Math.abs(p.getBbHeight() - EntityType.ZOMBIE.getHeight()) > 1e-3) throw new AssertionError("the client player is not a zombie's height: " + p.getBbHeight());
                })
                .screenshot("zombie_first_person")
                .step("walk forward, seen from behind", ctx -> {
                    walkedFrom = Minecraft.getInstance().player.position();
                    Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK);
                    Minecraft.getInstance().options.keyUp.setDown(true);
                })
                .ticks(12)
                .screenshot("zombie_walking")
                .step("stop", ctx -> {
                    Minecraft.getInstance().options.keyUp.setDown(false);
                    double moved = Minecraft.getInstance().player.position().distanceTo(walkedFrom);
                    System.out.printf("[logos] incarnate: zombie walked %.2f blocks in 12 ticks%n", moved);
                    if (moved < 0.5) throw new AssertionError("the zombie barely moved: " + moved);
                })
                .server("become a pig", sc -> {
                    if (!Incarnation.start(sc.player(), EntityType.PIG, 20)) throw new AssertionError("no pig");
                })
                .ticks(10)
                .screenshot("pig_third_person")
                .step("first person: no hand", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                .ticks(5)
                .screenshot("pig_first_person")
                .server("become a bat", sc -> {
                    if (!Incarnation.start(sc.player(), EntityType.BAT, 2)) throw new AssertionError("no bat");
                    sc.player().getAbilities().flying = true;
                    sc.player().onUpdateAbilities();
                    sc.player().teleportTo(ORIGIN.getX() + 0.5, ORIGIN.getY() + 3, ORIGIN.getZ() + 0.5);
                })
                .step("look from behind", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                .ticks(10)
                .screenshot("bat_flying")
                .ticks(40)
                .step("worn off", ctx -> {
                    Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
                    var p = Minecraft.getInstance().player;
                    if (Incarnation.active(p) || IncarnationView.shell(p) != null) throw new AssertionError("still a bat");
                    if (Math.abs(p.getBbHeight() - 1.8f) > 1e-3) throw new AssertionError("not their own height: " + p.getBbHeight());
                })
                .screenshot("back");
    }
}
