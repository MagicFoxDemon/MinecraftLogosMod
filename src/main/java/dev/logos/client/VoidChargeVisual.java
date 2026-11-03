package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.lang.Element;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;

/**
 * The void charge (CHARGE_VFX.md): the shared charge in void's colours, its grand and colossal inner circle
 * the ringed eye of MagicCircle 07, and at the head, in place of the shared ball, a sphere of open void in
 * the style of every void effect ({@link VoidChargeBall}: the void beam's sphere, the screen-projected
 * starfield rimmed in violet), with a see-through one closing in on a colossal charge. A colossal charge wraps
 * the caster in its own aura in place of the shared pillar: the violet flame of {@link ChargeAuraEvoke} (after
 * NS_Charge_EvokeEnergy_Root) with the starfield showing through it and solid void smoke rising behind, and
 * wisps of open void flying in to the caster ({@link VoidChargeAura}, after NS_Charge_DarkSummon).
 */
final class VoidChargeVisual extends ChargeCore {
    private final ChargeAuraEvoke aura;
    private final VoidChargeAura summon = new VoidChargeAura();

    VoidChargeVisual() {
        super(ChargeStyle.of(Element.VOID).withCircles("charge/circle_17", "charge/circle_18", "void_charge/circle_07",
                "charge/circle_03", "charge/circle_11", "void_charge/circle_07", "charge/circle_17").withOwnAura(), false);
        aura = new ChargeAuraEvoke(new ChargeAuraEvoke.Look(style.light(), style.main(), style.deep(), ChargeAuraEvokeParts.Accent.STARRY,
                VoidChargeAura.WISP, true));
    }

    @Override
    public boolean tick(Level level, LiveCharge charge) {
        boolean core = super.tick(level, charge);
        if (charge.tier() != 3) return core;
        boolean a = aura.tick(level.getRandom(), charge);
        boolean b = summon.tick(level.getRandom(), charge);
        return core || a || b;
    }

    @Override
    public void submit(Level level, LiveCharge charge, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        super.submit(level, charge, camera, partialTick, pose, out);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        ChargeParts.View view = ChargeParts.View.of(camera);
        VoidChargeBall.submit(charge, view, partialTick, pose, out);
        if (charge.tier() == 3) {
            aura.submit(charge, view, partialTick, pose, out);
            summon.submit(charge, view, partialTick, pose, out, style.main());
        }
        pose.popPose();
    }

    @Override
    public void end() {
        super.end();
        aura.end();
        summon.end();
    }
}
