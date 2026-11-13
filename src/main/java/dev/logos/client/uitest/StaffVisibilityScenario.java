package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.mixin.ItemStackRenderStateAccessor;
import dev.logos.mixin.LayerRenderStateAccessor;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Solid staff geometry must stay in the solid pass when its orbit is translucent (notably Photon 1.4). */
@LDLRegisterClient(name = "logos_staff_visibility", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public final class StaffVisibilityScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "staff", "rendering").guiScale(3);
    }

    @Override
    public void define(ScenarioBuilder s) {
        var origin = new BlockPos(0, 100, 0);
        s.setGameMode(GameType.SURVIVAL).runCommand("time set noon").runCommand("weather clear")
                .clearArea(origin.offset(0, 7, 0), 10)
                .fill(origin.offset(-9, 0, -9), origin.offset(9, 0, 9), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(0.5, 101, 0.5, 0, 0)
                .server("the seven actual staffs", sc -> {
                    sc.player().getInventory().clearContent();
                    for (int i = 0; i < 7; i++) {
                        var stack = new ItemStack(LogosMod.TIER_STAFFS.get(i).get());
                        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false);
                        sc.player().getInventory().setItem(i, stack);
                    }
                    sc.player().getInventory().setSelectedSlot(0);
                }).step("third person without charging effects", ctx -> {
                    Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                    Minecraft.getInstance().options.hideGui = true;
                }).ticks(10);
        for (int i = 0; i < 7; i++) {
            final int slot = i;
            s.server("hold staff " + (i + 2), sc -> sc.player().getInventory().setSelectedSlot(slot))
                    .step("select on the client", ctx -> Minecraft.getInstance().player.getInventory().setSelectedSlot(slot))
                    .ticks(6).screenshot("staff_" + (i + 2) + "_third_person");
        }
        s.teleportPlayer(0.5, 101, -6.5, 0, 24)
                .server("lay out all seven dropped staffs", sc -> {
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    for (int i = 0; i < 7; i++) {
                        double x = i < 4 ? -3 + i * 2 : -2 + (i - 4) * 2;
                        double z = i < 4 ? -1 : 2;
                        var stack = new ItemStack(LogosMod.TIER_STAFFS.get(i).get());
                        var drop = new ItemEntity(sc.player().level(), x, 101.05, z, stack);
                        drop.setNoGravity(true);
                        drop.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                        drop.setPickUpDelay(32767);
                        sc.player().level().addFreshEntity(drop);
                    }
                }).step("look at dropped items", ctx -> {
                    Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
                    Minecraft.getInstance().options.hideGui = true;
                }).ticks(15).screenshot("all_dropped_staffs")
                .step("solid bodies and translucent orbits use separate submissions", ctx -> {
                    var mc = Minecraft.getInstance();
                    for (var staff : LogosMod.TIER_STAFFS) {
                        for (var display : new ItemDisplayContext[] {ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                                ItemDisplayContext.GROUND, ItemDisplayContext.FIXED, ItemDisplayContext.GUI,
                                ItemDisplayContext.FIRST_PERSON_RIGHT_HAND}) {
                            var state = new ItemStackRenderState();
                            mc.getItemModelResolver().updateForTopItem(state, new ItemStack(staff.get()), display, mc.level, mc.player, 0);
                            var access = (ItemStackRenderStateAccessor) state;
                            int solid = 0;
                            for (int i = 0; i < access.logos$activeLayerCount(); i++) {
                                var quads = ((LayerRenderStateAccessor) access.logos$layers()[i]).logos$quads();
                                boolean opaque = quads.stream().anyMatch(q -> !q.materialInfo().itemRenderType().hasBlending());
                                boolean blended = quads.stream().anyMatch(q -> q.materialInfo().itemRenderType().hasBlending());
                                if (!blended) solid += quads.size();
                                ctx.check(staff.getId() + " " + display + " layer " + i + " has one transparency class", !(opaque && blended));
                            }
                            ctx.check(staff.getId() + " " + display + " retains a solid body", solid > 100);
                        }
                    }
                }).step("restore HUD", ctx -> Minecraft.getInstance().options.hideGui = false);
    }
}
